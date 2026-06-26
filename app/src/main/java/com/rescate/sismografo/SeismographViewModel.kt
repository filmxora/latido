package com.rescate.sismografo

import android.app.Application
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DetectionLevel { IDLE, NOISE, IMPACT, RHYTHMIC }

data class LogEntry(val time: String, val magnitude: Float, val rhythmic: Boolean)

data class SeisUiState(
    val listening: Boolean = false,
    val waveform: FloatArray = FloatArray(SignalProcessor.DISPLAY_POINTS),
    val level: DetectionLevel = DetectionLevel.IDLE,
    val noiseFloor: Float = 0f,
    val threshold: Float = 0f,
    val lastMagnitude: Float = 0f,
    val onsetCount: Int = 0,
    val periodMs: Float = 0f,
    val confidence: Float = 0f,
    val pattern: String = "—",
    val grouped: Boolean = false,
    val usingLinearAccel: Boolean = true,
    val sensorAvailable: Boolean = true,
    val logs: List<LogEntry> = emptyList()
)

class SeismographViewModel(app: Application) : AndroidViewModel(app), SensorEventListener {

    private val sensorManager =
        app.getSystemService(Application.SENSOR_SERVICE) as SensorManager

    private val processor = SignalProcessor()

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val pendingLogs = ArrayDeque<LogEntry>()
    private val logLock = Any()

    private var sensorThread: HandlerThread? = null
    private var activeSensor: Sensor? = null
    private var uiJob: Job? = null
    private var lastAlertTs = 0L

    private val _state = MutableStateFlow(SeisUiState())
    val state: StateFlow<SeisUiState> = _state.asStateFlow()

    init {
        val linear = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        activeSensor = linear ?: accel
        processor.usesLinearAccel = linear != null
        _state.value = _state.value.copy(
            usingLinearAccel = linear != null,
            sensorAvailable = activeSensor != null
        )
    }

    fun setSensitivity(k: Float) {
        processor.sensitivityK = k
    }

    val sensitivity: Float get() = processor.sensitivityK

    fun toggle() {
        if (_state.value.listening) stop() else start()
    }

    private fun start() {
        val sensor = activeSensor ?: return
        processor.reset()
        synchronized(logLock) { pendingLogs.clear() }

        val thread = HandlerThread("sensor-sampler").also { it.start() }
        sensorThread = thread
        sensorManager.registerListener(
            this,
            sensor,
            SensorManager.SENSOR_DELAY_FASTEST,
            Handler(thread.looper)
        )

        _state.value = _state.value.copy(listening = true, logs = emptyList())

        uiJob = viewModelScope.launch {
            while (isActive) {
                pushUiFrame()
                delay(60)
            }
        }
    }

    private fun stop() {
        uiJob?.cancel(); uiJob = null
        sensorManager.unregisterListener(this)
        sensorThread?.quitSafely(); sensorThread = null
        _state.value = _state.value.copy(listening = false, level = DetectionLevel.IDLE)
    }

    override fun onSensorChanged(event: SensorEvent) {
        // event.timestamp está en nanosegundos desde el arranque.
        val tMs = event.timestamp / 1_000_000L
        val onset = processor.process(event.values[0], event.values[1], event.values[2], tMs)
        if (onset != null) {
            val rhythm = processor.analyzeRhythm(tMs)
            val entry = LogEntry(
                time = timeFmt.format(Date()),
                magnitude = onset.magnitude,
                rhythmic = rhythm.rhythmic
            )
            synchronized(logLock) {
                pendingLogs.addFirst(entry)
                while (pendingLogs.size > 50) pendingLogs.removeLast()
            }
            if (rhythm.rhythmic) maybeAlert(tMs)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun pushUiFrame() {
        val now = System.currentTimeMillis()
        val rhythm = processor.analyzeRhythm(now)
        val mag = processor.lastMagnitude
        val thr = processor.threshold

        val level = when {
            !_state.value.listening -> DetectionLevel.IDLE
            rhythm.rhythmic -> DetectionLevel.RHYTHMIC
            mag > thr && rhythm.onsetCount > 0 -> DetectionLevel.IMPACT
            else -> DetectionLevel.NOISE
        }

        val logsCopy = synchronized(logLock) { pendingLogs.toList() }

        _state.value = _state.value.copy(
            waveform = processor.displaySnapshot(),
            level = level,
            noiseFloor = processor.noiseFloor,
            threshold = thr,
            lastMagnitude = mag,
            onsetCount = rhythm.onsetCount,
            periodMs = rhythm.periodMs,
            confidence = rhythm.confidence,
            pattern = rhythm.pattern,
            grouped = rhythm.grouped,
            logs = logsCopy
        )
    }

    private fun maybeAlert(tMs: Long) {
        if (tMs - lastAlertTs < 1200) return
        lastAlertTs = tMs
        val vib = vibrator() ?: return
        if (vib.hasVibrator()) {
            vib.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun vibrator(): Vibrator? {
        val ctx = getApplication<Application>()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = ctx.getSystemService(Application.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Application.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    override fun onCleared() {
        super.onCleared()
        stop()
    }
}
