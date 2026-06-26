package com.rescate.sismografo

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Procesa el flujo del acelerómetro en tiempo real. App de rescate: la prioridad es
 * NO gritar por ruido y SÍ confirmar un patrón humano deliberado.
 *
 *  1. Magnitud de aceleración lineal (sin gravedad).
 *  2. CALIBRACIÓN inicial: mide el ruido ambiental real (media y desviación) durante
 *     ~1.8 s antes de detectar nada, ignorando el manipuleo de los primeros 0.4 s.
 *     Esto evita el "impacto detectado" falso nada más iniciar.
 *  3. Umbral = media + k·σ, con un PISO físico de σ (MIN_EFFECTIVE_STD): nunca se asume
 *     un ruido menor, de modo que un teléfono inmóvil (σ≈0) no dispara con cualquier roce.
 *  4. Detección de impactos con periodo refractario (anti-eco).
 *  5. Análisis de RITMO (regularidad de intervalos) y de PATRÓN EN GRUPOS (tipo auxilio).
 */
class SignalProcessor {

    companion object {
        const val DISPLAY_POINTS = 240

        private const val ONSET_REFRACTORY_MS = 120L
        private const val ONSET_WINDOW_MS = 7000L      // ventana de análisis de ritmo
        private const val MIN_ONSETS_FOR_RHYTHM = 4    // 4 golpes = 3 intervalos para juzgar
        private const val INTRA_BURST_MAX_MS = 700f    // separación máx. dentro de un grupo
        private const val RHYTHM_CV_MAX = 0.42f        // regularidad: CV bajo = rítmico

        // Ritmo humano plausible de auxilio: entre ~0.4 y ~4 golpes por segundo.
        // Por debajo de 250 ms (≥4/s) suele ser maquinaria, eco o la propia vibración
        // del teléfono (realimentación), no una persona golpeando deliberadamente.
        private const val MIN_HUMAN_PERIOD_MS = 250f
        private const val MAX_HUMAN_PERIOD_MS = 2500f
        /** Histéresis: para rearmar el detector la señal debe caer a esta fracción del umbral. */
        private const val RELEASE_FRACTION = 0.5f

        private const val SETTLE_MS = 400L             // ignorar el manipuleo inicial
        private const val CALIBRATION_MS = 1800L       // medir el ruido ambiental real
        private const val NOISE_ALPHA = 0.01f          // adaptación lenta del piso de ruido
        private const val GRAVITY_ALPHA = 0.92f        // filtro paso-bajo para estimar gravedad

        /** m/s²: nunca se asume un ruido por debajo de esto (evita umbral ≈ 0 con teléfono inmóvil). */
        private const val MIN_EFFECTIVE_STD = 0.15f
    }

    /** Multiplicador del umbral. Mayor = menos sensible (umbral más alto). */
    @Volatile var sensitivityK: Float = 3.0f

    /** true si la fuente ya entrega aceleración lineal (gravedad removida por el sistema). */
    @Volatile var usesLinearAccel: Boolean = true

    /** Pausa la generación de impactos (p. ej. mientras suena la alarma) para evitar
     *  que la propia vibración del teléfono se realimente como nuevos golpes. */
    @Volatile var detectionPaused: Boolean = false

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

    // --- Calibración ---
    private var t0 = 0L
    private var calibN = 0
    private var calibSum = 0.0
    private var calibSumSq = 0.0

    // --- Detección de impactos (por flanco de subida con histéresis) ---
    private val onsets = ArrayDeque<Long>()
    private var armed = true   // listo para registrar un nuevo golpe

    // --- Snapshots para la UI (volátiles, leídos desde el hilo principal) ---
    @Volatile var lastMagnitude = 0f; private set
    @Volatile var noiseFloor = 0f; private set
    @Volatile var threshold = 0f; private set
    @Volatile var lastSampleTs = 0L; private set
    @Volatile var lastOnsetTs = 0L; private set
    /** true mientras se mide el ruido ambiental inicial (no se detecta nada todavía). */
    @Volatile var calibrating = true; private set

    fun reset() {
        synchronized(display) { display.fill(0f); dispIdx = 0 }
        gravInit = false
        meanMag = 0f; varMag = 0f
        t0 = 0L; calibN = 0; calibSum = 0.0; calibSumSq = 0.0
        armed = true
        synchronized(onsets) { onsets.clear() }
        lastMagnitude = 0f; noiseFloor = 0f; threshold = 0f
        lastSampleTs = 0L; lastOnsetTs = 0L
        calibrating = true
    }

    /**
     * Procesa una muestra. Devuelve un [Onset] si se detectó un impacto, o null.
     * @param tMillis marca de tiempo de la muestra en ms (reloj monótono).
     */
    fun process(x: Float, y: Float, z: Float, tMillis: Long): Onset? {
        val mag = magnitude(x, y, z)
        lastMagnitude = mag
        lastSampleTs = tMillis
        synchronized(display) {
            display[dispIdx] = mag
            dispIdx = (dispIdx + 1) % DISPLAY_POINTS
        }

        if (t0 == 0L) t0 = tMillis
        val elapsed = tMillis - t0

        // --- Fase de calibración: medir el ruido ambiental, sin detectar nada ---
        if (calibrating) {
            if (elapsed in SETTLE_MS..CALIBRATION_MS) {
                calibN++
                calibSum += mag
                calibSumSq += mag.toDouble() * mag
            }
            if (elapsed > CALIBRATION_MS && calibN >= 20) {
                val m = calibSum / calibN
                meanMag = m.toFloat()
                varMag = (calibSumSq / calibN - m * m).coerceAtLeast(0.0).toFloat()
                calibrating = false
            }
            noiseFloor = meanMag
            threshold = meanMag + sensitivityK * max(sqrt(varMag), MIN_EFFECTIVE_STD)
            return null
        }

        val effStd = max(sqrt(varMag), MIN_EFFECTIVE_STD)
        val thr = meanMag + sensitivityK * effStd
        noiseFloor = meanMag
        threshold = thr

        // Detección por FLANCO DE SUBIDA con histéresis: un golpe genera UN solo impacto
        // (cuando la señal cruza el umbral hacia arriba). Una vibración sostenida —máquina,
        // tráfico o la propia alarma del teléfono— se queda alta y NO genera impactos repetidos.
        val releaseThr = meanMag + RELEASE_FRACTION * (thr - meanMag)
        val risingEdge = mag > thr && armed
        if (mag > thr) armed = false
        else if (mag < releaseThr) armed = true

        // Actualiza la estadística de ruido SOLO con muestras tranquilas
        // (así un golpe no eleva el piso de ruido y "se esconde" a sí mismo).
        if (mag <= thr) {
            val dev = mag - meanMag
            meanMag += NOISE_ALPHA * dev
            varMag = (1 - NOISE_ALPHA) * (varMag + NOISE_ALPHA * dev * dev)
        }

        val isImpact = risingEdge && !detectionPaused &&
            (tMillis - lastOnsetTs) > ONSET_REFRACTORY_MS
        if (isImpact) {
            lastOnsetTs = tMillis
            synchronized(onsets) {
                onsets.addLast(tMillis)
                while (onsets.size > 32) onsets.removeFirst()
                while (onsets.isNotEmpty() && tMillis - onsets.first() > ONSET_WINDOW_MS) {
                    onsets.removeFirst()
                }
            }
            return Onset(tMillis, mag)
        }
        return null
    }

    private fun magnitude(x: Float, y: Float, z: Float): Float {
        if (usesLinearAccel) return sqrt(x * x + y * y + z * z)
        if (!gravInit) { gravX = x; gravY = y; gravZ = z; gravInit = true }
        gravX = GRAVITY_ALPHA * gravX + (1 - GRAVITY_ALPHA) * x
        gravY = GRAVITY_ALPHA * gravY + (1 - GRAVITY_ALPHA) * y
        gravZ = GRAVITY_ALPHA * gravZ + (1 - GRAVITY_ALPHA) * z
        val lx = x - gravX; val ly = y - gravY; val lz = z - gravZ
        return sqrt(lx * lx + ly * ly + lz * lz)
    }

    /** Copia ordenada del buffer del osciloscopio (más antiguo -> más reciente). */
    fun displaySnapshot(): FloatArray {
        val out = FloatArray(DISPLAY_POINTS)
        synchronized(display) {
            for (i in 0 until DISPLAY_POINTS) out[i] = display[(dispIdx + i) % DISPLAY_POINTS]
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
            while (onsets.isNotEmpty() && now - onsets.first() > ONSET_WINDOW_MS) onsets.removeFirst()
            ts = onsets.toLongArray()
        }
        if (ts.size < MIN_ONSETS_FOR_RHYTHM) {
            return RhythmResult(false, ts.size, 0f, 0f, "—", false, 0f)
        }

        val intervals = FloatArray(ts.size - 1) { (ts[it + 1] - ts[it]).toFloat() }
        var mean = 0f
        for (v in intervals) mean += v
        mean /= intervals.size
        if (mean < MIN_HUMAN_PERIOD_MS || mean > MAX_HUMAN_PERIOD_MS) {
            // demasiado rápido (máquina/eco/realimentación) o demasiado lento (impactos sueltos)
            return RhythmResult(false, ts.size, mean, 0f, "irregular", false, 1f)
        }
        var variance = 0f
        for (v in intervals) { val d = v - mean; variance += d * d }
        variance /= intervals.size
        val cv = sqrt(variance) / mean   // coeficiente de variación: bajo = regular

        val regularity = (1f - cv / 0.5f).coerceIn(0f, 1f)
        val countFactor = ((ts.size - MIN_ONSETS_FOR_RHYTHM + 1).toFloat() / 4f).coerceIn(0f, 1f)
        var confidence = regularity * countFactor
        val rhythmic = cv < RHYTHM_CV_MAX

        // --- Detección de patrón en GRUPOS (tipo auxilio: "golpe-golpe-golpe, pausa, ...") ---
        val groups = ArrayList<Int>()
        val gaps = ArrayList<Float>()
        var count = 1
        for (iv in intervals) {
            if (iv <= INTRA_BURST_MAX_MS) count++
            else { groups.add(count); count = 1; gaps.add(iv) }
        }
        groups.add(count)

        var grouped = false
        if (groups.size >= 2 && groups.all { it >= 2 } && gaps.isNotEmpty()) {
            val avgSize = groups.average()
            val sizeOk = groups.all { abs(it - avgSize) <= 1.0 }
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
        return RhythmResult(rhythmic || grouped, ts.size, mean, confidence, pattern, grouped, cv)
    }
}

data class Onset(val timestampMs: Long, val magnitude: Float)

data class RhythmResult(
    val rhythmic: Boolean,
    val onsetCount: Int,
    val periodMs: Float,
    val confidence: Float,
    val pattern: String = "—",
    val grouped: Boolean = false,
    val cv: Float = 0f
)
