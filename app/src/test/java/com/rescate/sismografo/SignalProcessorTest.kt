package com.rescate.sismografo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * Pruebas del motor de detección con señales sintéticas (acelerómetro simulado a 100 Hz).
 * Verifican que:
 *  - Un golpeteo deliberado y regular se identifica con confianza ≥ 40 %.
 *  - El ruido aleatorio NO se confirma como señal de vida.
 *  - Una vibración continua rápida (máquina/realimentación) NO se confirma.
 */
class SignalProcessorTest {

    private val dtMs = 10L          // 100 Hz
    private val spike = 4.0f        // m/s² de un golpe
    private val ambient = 0.02f     // ruido ambiental tranquilo

    private val p = SignalProcessor()
    private var t = 0L

    private fun step(mag: Float): Onset? {
        val o = p.process(mag, 0f, 0f, t)
        t += dtMs
        return o
    }

    private fun calibrate() = repeat(220) { step(ambient) }  // ~2.2 s de reposo

    @Test
    fun golpeteoRegular_seDetectaConConfianzaAlta() {
        calibrate()

        var best = 0f
        var detected = false
        // 8 golpes separados 600 ms (≈1.7 golpes/s): 1 muestra de pico + 59 de calma = 600 ms.
        repeat(8) {
            val onset = step(spike)
            if (onset != null) {
                if (onset.confirmed) detected = true
                val c = onset.rhythm?.confidence ?: 0f
                if (c > best) best = c
            }
            repeat(59) { step(ambient) }
        }

        println("Golpeteo regular -> detectado=$detected, confianza máx=${(best * 100).toInt()}%")
        assertTrue("Debe detectar el patrón rítmico de forma sostenida", detected)
        assertTrue(
            "La confianza debe ser ≥ 40 % (fue ${(best * 100).toInt()}%)",
            best >= SignalProcessor.TRIGGER_CONFIDENCE
        )
    }

    @Test
    fun ruidoAleatorio_noSeConfunde() {
        calibrate()
        val rnd = java.util.Random(42)

        var falseDetections = 0
        repeat(3000) {
            val v = ambient + rnd.nextFloat() * 0.1f +
                if (rnd.nextInt(40) == 0) (1f + rnd.nextFloat() * 4f) else 0f
            val onset = step(v)
            if (onset?.confirmed == true) falseDetections++
        }
        println("Ruido aleatorio -> falsas detecciones=$falseDetections")
        assertEquals("El ruido no debe confirmarse como señal de vida", 0, falseDetections)
    }

    @Test
    fun vibracionContinuaRapida_noSeConfunde() {
        calibrate()

        var detections = 0
        repeat(3000) { i ->
            val v = 2.0f + 1.5f * sin(i * 0.75f)  // senoide sostenida ~12 Hz, siempre alta
            val onset = step(v)
            if (onset?.confirmed == true) detections++
        }
        println("Vibración continua rápida -> detecciones=$detections")
        assertEquals("Una vibración continua rápida no es una persona", 0, detections)
    }
}
