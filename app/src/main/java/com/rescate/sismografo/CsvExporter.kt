package com.rescate.sismografo

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exporta el registro de detecciones a un CSV compartible (correo, WhatsApp, Drive…)
 * para el relevo entre rescatistas. El archivo se escribe en la caché de la app y se
 * comparte vía FileProvider, sin necesidad de permisos de almacenamiento.
 */
object CsvExporter {

    /** Devuelve un Intent de compartir listo para `startActivity`, o null si no hay datos. */
    fun buildShareIntent(ctx: Context, logs: List<LogEntry>): Intent? {
        if (logs.isEmpty()) return null

        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "latido_$stamp.csv")

        file.bufferedWriter().use { w ->
            w.write("hora,magnitud_m_s2,ritmico,patron,confianza_pct\n")
            // Más antiguo primero para que el CSV se lea cronológicamente.
            for (e in logs.asReversed()) {
                val patron = e.pattern.replace(",", " ")
                val conf = (e.confidence * 100).toInt()
                w.write("${e.time},${"%.2f".format(Locale.US, e.magnitude)},${e.rhythmic},$patron,$conf\n")
            }
        }

        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Latido — registro de detecciones $stamp")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
