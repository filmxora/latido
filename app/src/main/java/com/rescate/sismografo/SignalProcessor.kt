package com.rescate.sismografo

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Procesa el flujo del acelerómetro en tiempo real.
 *
 * Pasos:
 *  1. Obtiene la magnitud de aceleración lineal (sin gravedad).
 *     - Si el dispositivo expone TYPE_LINEAR_ACCELERATION, la gravedad ya viene restada.
 *     - Si no, estimamos la gravedad con un filtro paso-bajo y la restamos (paso-alto).
 *  2. Mantiene un piso de ruido ADAPTATIVO (media + k·desviación) calculado SOLO con
 *     muestras tranquilas, para que los propios golpes no inflen el umbral.
 *  3. Detecta "onsets" (impactos) que superan el umbral con un periodo refractario.
 *  4. Analiza si los últimos impactos forman un PATRÓN RÍTMICO (regularidad),
 *     que es lo que distingue una señal de auxilio del ruido aleatorio.
 */
class SignalProcessor {

    companion object {
        const val DISPLAY_POINTS = 240
        private const val ONSET_REFRACTORY_MS = 110L
        private const val NOISE_ALPHA = 0.006f      // adaptación lenta (~2-3 s) del piso de ruido
        private const val GRAVITY_ALPHA = 0.92f     // filtro paso-bajo para estimar gravedad
        private const val ONSET_WINDOW_MS = 6000L   // ventana de análisis de ritmo
        private const val CALIBRATION_SAMPLES = 120 // ignorar onsets durante el arranque
        private const val MIN_ONSETS_FOR_RHYTHM = 4
        private const val INTRA_BURST_MAX_MS = 700f // separación máx. dentro de un mismo grupo
    }

    /** Multiplicador del umbral (sensibilidad ajustable por el usuario). Mayor = menos sensible. */
    @Volatile var sensitivityK: Float = 3.0f

    /** true si la fuente ya entrega aceleración lineal (gravedad removida por el sistema). */
    @Volatile var usesLinearAccel: Boolean = true

    // --- Buffer de visualización (osciloscopio) ---
    private val display = FloatArray(DISPLAY_POINTS)
    private var dispIdx = 0

    // --- Estado del filtro de gravedad ---
    private var gravX = 0f
    private var gravY = 0f
    private var gravZ = 0f
    private var gravInit = false

    // --- Estadística de ruido ---
    private var meanMag = 0f
    private var varMag = 0f
    private var sampleCount = 0

    // --- Detección de impactos ---
    private var lastOnsetTs = 0L
    private val onsets = ArrayDeque<Long>()

    // --- Snapshots para la UI (volátiles, leídos desde el hilo principal) ---
    @Volatile var lastMagnitude = 0f; private set
    @Volatile var noiseFloor = 0f; private set
    @Volatile var threshold = 0f; private set
    /** Última marca de tiempo monótona vista (mismo reloj que los onsets). */
    @Volatile var lastSampleTs = 0L; private set

    fun reset() {
        synchronized(display) {
            display.fill(0f)
            dispIdx = 0
        }
        gravInit = false
        meanMag = 0f
        varMag = 0f
        sampleCount = 0
        lastOnsetTs = 0L
        synchronized(onsets) { onsets.clear() }
        lastMagnitude = 0f
        noiseFloor = 0f
        threshold = 0f
        lastSampleTs = 0L
    }

    /**
     * Procesa una muestra. Devuelve un [Onset] si se detectó un impacto, o null.
     * @param tMillis marca de tiempo de la muestra en ms (monótona).
     */
    fun process(x: Float, y: Float, z: Float, tMillis: Long): Onset? {
        val mag: Float = if (usesLinearAccel) {
            sqrt(x * x + y * y + z * z)
        } else {
            if (!gravInit) {
                gravX = x; gravY = y; gravZ = z; gravInit = true
            }
            gravX = GRAVITY_ALPHA * gravX + (1 - GRAVITY_ALPHA) * x
            gravY = GRAVITY_ALPHA * gravY + (1 - GRAVITY_ALPHA) * y
            gravZ = GRAVITY_ALPHA * gravZ + (1 - GRAVITY_ALPHA) * z
            val lx = x - gravX
            val ly = y - gravY
            val lz = z - gravZ
            sqrt(lx * lx + ly * ly + lz * lz)
        }

        lastMagnitude = mag
        lastSampleTs = tMillis
        synchronized(display) {
            display[dispIdx] = mag
            dispIdx = (dispIdx + 1) % DISPLAY_POINTS
        }

        sampleCount++
        val std = sqrt(varMag) + 1e-4f
        val thr = meanMag + sensitivityK * std
        noiseFloor = meanMag
        threshold = thr

        val calibrating = sampleCount < CALIBRATION_SAMPLES
        val isImpact = !calibrating && mag > thr && (tMillis - lastOnsetTs) > ONSET_REFRACTORY_MS

        // Actualiza la estadística de ruido SOLO con muestras tranquilas
        // (así un golpe no eleva el piso de ruido y "se esconde" a sí mismo).
        if (mag <= thr) {
            val dev = mag - meanMag
            meanMag += NOISE_ALPHA * dev
            varMag = (1 - NOISE_ALPHA) * (varMag + NOISE_ALPHA * dev * dev)
        }

        if (isImpact) {
            lastOnsetTs = tMillis
            synchronized(onsets) {
                onsets.addLast(tMillis)
                while (onsets.size > 24) onsets.removeFirst()
                while (onsets.isNotEmpty() && tMillis - onsets.first() > ONSET_WINDOW_MS) {
                    onsets.removeFirst()
                }
            }
            return Onset(tMillis, mag)
        }
        return null
    }

    /** Copia ordenada del buffer del osciloscopio (más antiguo -> más reciente). */
    fun displaySnapshot(): FloatArray {
        val out = FloatArray(DISPLAY_POINTS)
        synchronized(display) {
            for (i in 0 until DISPLAY_POINTS) {
                out[i] = display[(dispIdx + i) % DISPLAY_POINTS]
            }
        }
        return out
    }

    /**
     * Analiza la regularidad de los últimos impactos para decidir si hay un patrón rítmico.
     * Un humano que pide auxilio golpea de forma relativamente regular; el ruido no.
     */
    fun analyzeRhythm(now: Long): RhythmResult {
        val ts: LongArray
        synchronized(onsets) {
            while (onsets.isNotEmpty() && now - onsets.first() > ONSET_WINDOW_MS) {
                onsets.removeFirst()
            }
            ts = onsets.toLongArray()
        }
        if (ts.size < MIN_ONSETS_FOR_RHYTHM) {
            return RhythmResult(false, ts.size, 0f, 0f)
        }

        // Intervalos entre impactos consecutivos.
        val intervals = FloatArray(ts.size - 1) { (ts[it + 1] - ts[it]).toFloat() }
        var mean = 0f
        for (v in intervals) mean += v
        mean /= intervals.size
        if (mean < 120f || mean > 2500f) {
            // demasiado rápido (eco/vibración) o demasiado lento (impactos sueltos)
            return RhythmResult(false, ts.size, 0f, 0f)
        }
        var variance = 0f
        for (v in intervals) {
            val d = v - mean
            variance += d * d
        }
        variance /= intervals.size
        val cv = sqrt(variance) / mean   // coeficiente de variación: bajo = regular

        // Confianza base: regularidad alta + suficientes impactos.
        val regularity = (1f - (cv / 0.45f)).coerceIn(0f, 1f)
        val countFactor = ((ts.size - MIN_ONSETS_FOR_RHYTHM + 1).toFloat() / 4f).coerceIn(0f, 1f)
        var confidence = regularity * countFactor
        val rhythmic = cv < 0.40f && ts.size >= MIN_ONSETS_FOR_RHYTHM

        // --- Detección de patrón en GRUPOS (tipo auxilio: "golpe-golpe-golpe, pausa, ...") ---
        // Separa los golpes en ráfagas: intervalos cortos = dentro de un grupo,
        // intervalos largos = pausa entre grupos. Es la firma de una señal deliberada.
        val groups = ArrayList<Int>()
        val gaps = ArrayList<Float>()
        var count = 1
        for (iv in intervals) {
            if (iv <= INTRA_BURST_MAX_MS) {
                count++
            } else {
                groups.add(count); count = 1; gaps.add(iv)
            }
        }
        groups.add(count)

        var grouped = false
        if (groups.size >= 2 && groups.all { it >= 2 } && gaps.isNotEmpty()) {
            // ¿Los grupos tienen tamaño parecido y las pausas son consistentes?
            val avgSize = groups.average()
            val sizeOk = groups.all { kotlin.math.abs(it - avgSize) <= 1.0 }
            var gMean = 0f
            for (g in gaps) gMean += g
            gMean /= gaps.size
            var gVar = 0f
            for (g in gaps) { val d = g - gMean; gVar += d * d }
            gVar /= gaps.size
            val gapCv = if (gMean > 0f) sqrt(gVar) / gMean else 1f
            val gapsOk = gaps.size == 1 || gapCv < 0.45f
            grouped = sizeOk && gapsOk && avgSize >= 2.0
            if (grouped) confidence = (confidence + 0.4f).coerceAtMost(1f)
        }

        val pattern = when {
            grouped -> "GRUPOS (auxilio)"
            rhythmic -> "rítmico"
            else -> "irregular"
        }
        return RhythmResult(rhythmic || grouped, ts.size, mean, confidence, pattern, grouped)
    }
}

data class Onset(val timestampMs: Long, val magnitude: Float)

data class RhythmResult(
    val rhythmic: Boolean,
    val onsetCount: Int,
    val periodMs: Float,
    val confidence: Float,
    val pattern: String = "—",
    val grouped: Boolean = false
)
