package com.rescate.sismografo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Estado compartido entre el servicio (productor) y la UI (consumidor).
 *
 * El escaneo vive en [ScanService] —un servicio en primer plano con WakeLock— para
 * que el muestreo del acelerómetro continúe aunque la pantalla esté apagada o la
 * actividad en segundo plano. La UI solo observa [state] y envía órdenes.
 */
object ScanController {
    val state: MutableStateFlow<SeisUiState> = MutableStateFlow(SeisUiState())

    /** Sensibilidad persistente (umbral = ruido + k·σ). Fuente de verdad fuera del servicio. */
    @Volatile var sensitivity: Float = 3.0f

    fun start(ctx: Context) {
        val i = Intent(ctx, ScanService::class.java).setAction(ScanService.ACTION_START)
        ContextCompatStartForeground(ctx, i)
    }

    fun stop(ctx: Context) {
        val i = Intent(ctx, ScanService::class.java).setAction(ScanService.ACTION_STOP)
        ctx.startService(i)
    }

    fun dismissAlarm(ctx: Context) {
        val i = Intent(ctx, ScanService::class.java).setAction(ScanService.ACTION_DISMISS_ALARM)
        ctx.startService(i)
    }

    fun updateSensitivity(k: Float) {
        sensitivity = k
        ScanService.instance?.applySensitivity(k)
    }

    private fun ContextCompatStartForeground(ctx: Context, i: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
        else ctx.startService(i)
    }
}

class ScanService : Service(), SensorEventListener {

    companion object {
        const val ACTION_START = "com.rescate.sismografo.START"
        const val ACTION_STOP = "com.rescate.sismografo.STOP"
        const val ACTION_DISMISS_ALARM = "com.rescate.sismografo.DISMISS_ALARM"

        private const val CHANNEL_ID = "latido_scan"
        private const val NOTIF_ID = 42
        private const val ALARM_REARM_MS = 8000L  // tras silenciar, espera antes de volver a sonar

        @Volatile var instance: ScanService? = null
            private set
    }

    private lateinit var sensorManager: SensorManager
    private val processor = SignalProcessor()

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val pendingLogs = ArrayDeque<LogEntry>()
    private val logLock = Any()

    private var sensorThread: HandlerThread? = null
    private var activeSensor: Sensor? = null
    private var uiJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaPlayer: MediaPlayer? = null

    private var lastVibrateTs = 0L
    private var alarmSilencedUntil = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        val linear = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        activeSensor = linear ?: accel
        processor.usesLinearAccel = linear != null
        ScanController.state.value = ScanController.state.value.copy(
            usingLinearAccel = linear != null,
            sensorAvailable = activeSensor != null
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startScanning()
            ACTION_STOP -> { stopScanning(); stopSelf() }
            ACTION_DISMISS_ALARM -> dismissAlarm()
            else -> startScanning()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun applySensitivity(k: Float) { processor.sensitivityK = k }

    // --- Ciclo de escaneo ---

    private fun startScanning() {
        if (uiJob?.isActive == true) return
        val sensor = activeSensor ?: run {
            ScanController.state.value =
                ScanController.state.value.copy(sensorAvailable = false)
            return
        }

        startForeground(NOTIF_ID, buildNotification("Escuchando… apoya el teléfono sobre la estructura"))

        processor.sensitivityK = ScanController.sensitivity
        processor.reset()
        synchronized(logLock) { pendingLogs.clear() }
        alarmSilencedUntil = 0L

        acquireWakeLock()

        val thread = HandlerThread("sensor-sampler").also { it.start() }
        sensorThread = thread
        sensorManager.registerListener(
            this, sensor, SensorManager.SENSOR_DELAY_FASTEST, Handler(thread.looper)
        )

        ScanController.state.value = ScanController.state.value.copy(
            listening = true, logs = emptyList(), alarmActive = false
        )

        uiJob = scope.launch {
            while (isActive) {
                pushUiFrame()
                delay(60)
            }
        }
    }

    private fun stopScanning() {
        uiJob?.cancel(); uiJob = null
        if (activeSensor != null) sensorManager.unregisterListener(this)
        sensorThread?.quitSafely(); sensorThread = null
        stopAlarmSound()
        releaseWakeLock()
        ScanController.state.value = ScanController.state.value.copy(
            listening = false, level = DetectionLevel.IDLE, alarmActive = false
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val tMs = event.timestamp / 1_000_000L
        val onset = processor.process(event.values[0], event.values[1], event.values[2], tMs)
        if (onset != null) {
            val rhythm = processor.analyzeRhythm(tMs)
            val entry = LogEntry(
                time = timeFmt.format(Date()),
                magnitude = onset.magnitude,
                rhythmic = rhythm.rhythmic,
                pattern = rhythm.pattern,
                confidence = rhythm.confidence
            )
            synchronized(logLock) {
                pendingLogs.addFirst(entry)
                while (pendingLogs.size > 200) pendingLogs.removeLast()
            }
            if (rhythm.rhythmic) onRhythmConfirmed(tMs, rhythm)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun onRhythmConfirmed(tMs: Long, rhythm: RhythmResult) {
        // Vibración breve (siempre que se confirma ritmo).
        if (tMs - lastVibrateTs >= 1200) {
            lastVibrateTs = tMs
            vibrateOnce()
        }
        // Alarma + ventana emergente: solo si no fue silenciada hace poco.
        if (System.currentTimeMillis() >= alarmSilencedUntil) {
            startAlarmSound()
            ScanController.state.value = ScanController.state.value.copy(alarmActive = true)
            updateNotification(
                if (rhythm.grouped) "⚠ SEÑAL DE VIDA — golpes en grupos (auxilio)"
                else "⚠ SEÑAL DE VIDA — patrón rítmico"
            )
        }
    }

    private fun dismissAlarm() {
        stopAlarmSound()
        alarmSilencedUntil = System.currentTimeMillis() + ALARM_REARM_MS
        ScanController.state.value = ScanController.state.value.copy(alarmActive = false)
        if (ScanController.state.value.listening) {
            updateNotification("Escuchando… ruido de fondo")
        }
    }

    private fun pushUiFrame() {
        val rhythm = processor.analyzeRhythm(processor.lastSampleTs)
        val mag = processor.lastMagnitude
        val thr = processor.threshold

        val level = when {
            rhythm.rhythmic -> DetectionLevel.RHYTHMIC
            mag > thr && rhythm.onsetCount > 0 -> DetectionLevel.IMPACT
            else -> DetectionLevel.NOISE
        }
        val logsCopy = synchronized(logLock) { pendingLogs.toList() }

        ScanController.state.value = ScanController.state.value.copy(
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

    // --- Alarma sonora (en bucle, canal de alarma para sonar incluso en silencio) ---

    private fun startAlarmSound() {
        if (mediaPlayer?.isPlaying == true) return
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return
        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@ScanService, uri)
                isLooping = true
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
        } catch (_: Exception) {
            mediaPlayer?.release(); mediaPlayer = null
        }
    }

    private fun stopAlarmSound() {
        mediaPlayer?.let {
            try { if (it.isPlaying) it.stop() } catch (_: Exception) {}
            it.release()
        }
        mediaPlayer = null
    }

    private fun vibrateOnce() {
        val vib = vibrator() ?: return
        if (vib.hasVibrator()) {
            vib.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }

    // --- WakeLock ---

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Latido:scan").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L) // tope de seguridad: 6 h
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // --- Notificación de primer plano ---

    private fun buildNotification(text: String): Notification {
        ensureChannel()
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pi = PendingIntent.getActivity(
            this, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("Latido — escaneo activo")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID, "Escaneo de rescate", NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Mantiene el detector activo en segundo plano" }
                nm.createNotificationChannel(ch)
            }
        }
    }

    override fun onDestroy() {
        stopScanning()
        scope.coroutineContext[Job]?.cancel()
        instance = null
        super.onDestroy()
    }
}
