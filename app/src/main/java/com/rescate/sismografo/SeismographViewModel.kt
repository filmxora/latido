package com.rescate.sismografo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow

enum class DetectionLevel { IDLE, NOISE, IMPACT, RHYTHMIC }

data class LogEntry(
    val time: String,
    val magnitude: Float,
    val rhythmic: Boolean,
    val pattern: String = "—",
    val confidence: Float = 0f
)

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
    val alarmActive: Boolean = false,
    val usingLinearAccel: Boolean = true,
    val sensorAvailable: Boolean = true,
    val logs: List<LogEntry> = emptyList()
)

/**
 * Fachada delgada sobre [ScanController]. Toda la lógica de sensor/alarma vive en
 * [ScanService] (servicio en primer plano), de modo que el escaneo sobrevive a la
 * pantalla apagada y a que la actividad pase a segundo plano.
 */
class SeismographViewModel(app: Application) : AndroidViewModel(app) {

    val state: StateFlow<SeisUiState> = ScanController.state

    val sensitivity: Float get() = ScanController.sensitivity

    fun setSensitivity(k: Float) = ScanController.updateSensitivity(k)

    fun toggle() {
        val ctx = getApplication<Application>()
        if (state.value.listening) ScanController.stop(ctx) else ScanController.start(ctx)
    }

    fun dismissAlarm() = ScanController.dismissAlarm(getApplication())
}
