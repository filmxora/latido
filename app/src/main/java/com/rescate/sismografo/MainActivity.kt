package com.rescate.sismografo

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.max

private val Bg = Color(0xFF0A0A0A)
private val Panel = Color(0xFF161616)
private val Red = Color(0xFFFF3B30)
private val Green = Color(0xFF28A745)
private val Amber = Color(0xFFFF9F0A)
private val Gray = Color(0xFF888888)
private val Trace = Color(0xFF00FFCC)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Mantener la pantalla encendida durante el escaneo de rescate.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { SeismographScreen() }
    }
}

@Composable
fun SeismographScreen(vm: SeismographViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var sensitivity by remember { mutableFloatStateOf(vm.sensitivity) }

    val mono = FontFamily.Monospace

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // --- Cabecera ---
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(
                "LATIDO",
                color = Red, fontFamily = mono, fontWeight = FontWeight.Bold, fontSize = 26.sp
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Detector sísmico de señales de vida",
                color = Gray, fontFamily = mono, fontSize = 11.sp
            )
        }

        if (!state.sensorAvailable) {
            Text(
                "Este dispositivo no tiene acelerómetro disponible.",
                color = Red, fontFamily = mono, fontSize = 14.sp
            )
            return@Column
        }

        // --- Banner de detección ---
        DetectionBanner(state)

        // --- Botón iniciar / detener ---
        Button(
            onClick = { vm.toggle() },
            modifier = Modifier.fillMaxWidth().height(60.dp),
            shape = RoundedCornerShape(6.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (state.listening) Green else Color(0xFF222222)
            )
        ) {
            Text(
                if (state.listening) "DETENER ESCANEO" else "INICIAR ESCANEO",
                fontFamily = mono, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White
            )
        }

        // --- Sensibilidad ---
        Column {
            Text(
                "Sensibilidad (umbral = ruido + ${"%.1f".format(sensitivity)}σ)",
                color = Color(0xFFBBBBBB), fontFamily = mono, fontSize = 12.sp
            )
            Slider(
                value = sensitivity,
                onValueChange = { sensitivity = it; vm.setSensitivity(it) },
                valueRange = 1.5f..6f,
                colors = SliderDefaults.colors(
                    thumbColor = Red, activeTrackColor = Red, inactiveTrackColor = Color(0xFF333333)
                )
            )
            Text(
                "Mín. sensible ←  → Máx. sensible (menos σ = más sensible)",
                color = Gray, fontFamily = mono, fontSize = 10.sp
            )
        }

        // --- Osciloscopio ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .background(Color(0xFF000800))
                .border(2.dp, Color(0xFF333333), RoundedCornerShape(6.dp))
        ) {
            Oscilloscope(state)
        }

        // --- Métricas en vivo ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Metric("Magnitud", "%.2f".format(state.lastMagnitude), mono)
            Metric("Umbral", "%.2f".format(state.threshold), mono)
            Metric("Impactos", "${state.onsetCount}", mono)
            Metric("Confianza", "${(state.confidence * 100).toInt()}%", mono)
        }

        // --- Registro ---
        Text(
            "REGISTRO DE PATRONES",
            color = Amber, fontFamily = mono, fontWeight = FontWeight.Bold, fontSize = 14.sp
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .background(Panel, RoundedCornerShape(6.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.logs) { log ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(log.time, color = Gray, fontFamily = mono, fontSize = 13.sp)
                    Text(
                        if (log.rhythmic) "♦ RÍTMICO %.2f".format(log.magnitude)
                        else "%.2f m/s²".format(log.magnitude),
                        color = if (log.rhythmic) Green else Red,
                        fontFamily = mono, fontWeight = FontWeight.Bold, fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun DetectionBanner(state: SeisUiState) {
    val (text, color) = when (state.level) {
        DetectionLevel.RHYTHMIC -> "⚠ POSIBLE SEÑAL DE VIDA — patrón rítmico" to Green
        DetectionLevel.IMPACT -> "Impacto detectado — esperando ritmo…" to Amber
        DetectionLevel.NOISE -> "Escuchando… ruido de fondo" to Gray
        DetectionLevel.IDLE -> "Apoya el teléfono sobre la estructura y pulsa INICIAR" to Gray
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
            .border(1.dp, color, RoundedCornerShape(6.dp))
            .padding(12.dp)
    ) {
        Text(text, color = color, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Metric(label: String, value: String, font: FontFamily) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(label, color = Gray, fontFamily = font, fontSize = 10.sp)
    }
}

@Composable
private fun Oscilloscope(state: SeisUiState) {
    Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
        val data = state.waveform
        if (data.isEmpty()) return@Canvas

        // Escala automática: el mayor entre el umbral y el pico reciente.
        var peak = state.threshold
        for (v in data) if (v > peak) peak = v
        val scaleMax = max(peak * 1.2f, 0.5f)
        val w = size.width
        val h = size.height
        val step = w / (data.size - 1)

        // Línea del umbral
        val thrY = h - (state.threshold / scaleMax * h).coerceIn(0f, h)
        drawLine(
            color = Red.copy(alpha = 0.5f),
            start = Offset(0f, thrY),
            end = Offset(w, thrY),
            strokeWidth = 1.5f
        )

        // Traza de la señal
        var prev = Offset(0f, h - (data[0] / scaleMax * h).coerceIn(0f, h))
        for (i in 1 until data.size) {
            val y = h - (data[i] / scaleMax * h).coerceIn(0f, h)
            val cur = Offset(i * step, y)
            drawLine(color = Trace, start = prev, end = cur, strokeWidth = 2f)
            prev = cur
        }
    }
}
