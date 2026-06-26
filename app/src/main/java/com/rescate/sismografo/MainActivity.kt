package com.rescate.sismografo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
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
        // Mantener la pantalla encendida mientras la app está en primer plano.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { SeismographScreen() }
    }
}

@Composable
fun SeismographScreen(vm: SeismographViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    // El slider expresa "sensibilidad" 0..1 (derecha = MÁS sensible). Internamente se
    // traduce a k (sigmas): nivel 0 → 6σ (menos sensible), nivel 1 → 1.5σ (más sensible).
    var sensLevel by remember { mutableFloatStateOf(kToLevel(vm.sensitivity)) }
    val ctx = LocalContext.current
    val mono = FontFamily.Monospace

    // Permiso de notificaciones (Android 13+) para la notificación de primer plano.
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* el escaneo sigue aunque se deniegue; solo afecta a la notificación visible */ }

    // Ventana emergente + (la alarma sonora la reproduce el servicio).
    if (state.alarmActive) {
        AlertDialog(
            onDismissRequest = { vm.dismissAlarm() },
            containerColor = Color(0xFF1A0A0A),
            titleContentColor = Green,
            textContentColor = Color.White,
            title = {
                Text(
                    "⚠ POSIBLE SEÑAL DE VIDA",
                    color = Green, fontFamily = mono, fontWeight = FontWeight.Bold, fontSize = 20.sp
                )
            },
            text = {
                Column {
                    Text(
                        if (state.grouped) "Golpes en GRUPOS (patrón de auxilio)"
                        else "Patrón RÍTMICO detectado",
                        color = Color.White, fontFamily = mono, fontWeight = FontWeight.Bold, fontSize = 15.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("Confianza: ${(state.confidence * 100).toInt()}%", color = Amber, fontFamily = mono, fontSize = 14.sp)
                    Text("Impactos: ${state.onsetCount}", color = Gray, fontFamily = mono, fontSize = 13.sp)
                    Text("Periodo: ${state.periodMs.toInt()} ms", color = Gray, fontFamily = mono, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Mantén el silencio y confirma manualmente antes de actuar.",
                        color = Gray, fontFamily = mono, fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { vm.dismissAlarm() },
                    colors = ButtonDefaults.buttonColors(containerColor = Red)
                ) {
                    Text("SILENCIAR", fontFamily = mono, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // --- Cabecera ---
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text("LATIDO", color = Red, fontFamily = mono, fontWeight = FontWeight.Bold, fontSize = 26.sp)
            Spacer(Modifier.height(2.dp))
            Text("Detector sísmico de señales de vida", color = Gray, fontFamily = mono, fontSize = 11.sp)
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
            onClick = {
                if (!state.listening && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                vm.toggle()
            },
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

        // --- Sensibilidad (derecha = más sensible) ---
        Column {
            Text(
                "Sensibilidad: ${(sensLevel * 100).toInt()}%  (umbral = ruido + ${"%.1f".format(levelToK(sensLevel))}σ)",
                color = Color(0xFFBBBBBB), fontFamily = mono, fontSize = 12.sp
            )
            Slider(
                value = sensLevel,
                onValueChange = { sensLevel = it; vm.setSensitivity(levelToK(it)) },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = Red, activeTrackColor = Red, inactiveTrackColor = Color(0xFF333333)
                )
            )
            Text(
                "← menos sensible        más sensible →",
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

        // --- Cabecera del registro + exportar ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "REGISTRO DE PATRONES",
                color = Amber, fontFamily = mono, fontWeight = FontWeight.Bold, fontSize = 14.sp
            )
            OutlinedButton(
                onClick = {
                    val intent = CsvExporter.buildShareIntent(ctx, state.logs)
                    if (intent != null) {
                        ctx.startActivity(Intent.createChooser(intent, "Exportar registro CSV"))
                    } else {
                        Toast.makeText(ctx, "No hay detecciones que exportar", Toast.LENGTH_SHORT).show()
                    }
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Trace)
            ) {
                Text("EXPORTAR CSV", fontFamily = mono, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
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
                        when {
                            log.grouped() -> "◆ GRUPOS %.2f".format(log.magnitude)
                            log.rhythmic -> "♦ RÍTMICO %.2f".format(log.magnitude)
                            else -> "%.2f m/s²".format(log.magnitude)
                        },
                        color = if (log.rhythmic) Green else Red,
                        fontFamily = mono, fontWeight = FontWeight.Bold, fontSize = 13.sp
                    )
                }
            }
        }
    }
}

private fun LogEntry.grouped(): Boolean = pattern.startsWith("GRUPOS")

// Mapeo slider <-> k (sigmas). nivel 1 = más sensible (k 1.5), nivel 0 = menos sensible (k 6).
private const val K_MIN = 1.5f
private const val K_MAX = 6f
private fun levelToK(level: Float): Float = K_MAX - level * (K_MAX - K_MIN)
private fun kToLevel(k: Float): Float = ((K_MAX - k) / (K_MAX - K_MIN)).coerceIn(0f, 1f)

@Composable
private fun DetectionBanner(state: SeisUiState) {
    val (text, color) = when (state.level) {
        DetectionLevel.RHYTHMIC ->
            (if (state.grouped) "⚠ POSIBLE SEÑAL DE VIDA — golpes en grupos (auxilio)"
            else "⚠ POSIBLE SEÑAL DE VIDA — patrón rítmico") to Green
        DetectionLevel.IMPACT -> "Impacto detectado — esperando ritmo…" to Amber
        DetectionLevel.CALIBRATING -> "Calibrando ruido ambiental… mantén el silencio" to Trace
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

        var peak = state.threshold
        for (v in data) if (v > peak) peak = v
        val scaleMax = max(peak * 1.2f, 0.5f)
        val w = size.width
        val h = size.height
        val step = w / (data.size - 1)

        val thrY = h - (state.threshold / scaleMax * h).coerceIn(0f, h)
        drawLine(
            color = Red.copy(alpha = 0.5f),
            start = Offset(0f, thrY),
            end = Offset(w, thrY),
            strokeWidth = 1.5f
        )

        var prev = Offset(0f, h - (data[0] / scaleMax * h).coerceIn(0f, h))
        for (i in 1 until data.size) {
            val y = h - (data[i] / scaleMax * h).coerceIn(0f, h)
            val cur = Offset(i * step, y)
            drawLine(color = Trace, start = prev, end = cur, strokeWidth = 2f)
            prev = cur
        }
    }
}
