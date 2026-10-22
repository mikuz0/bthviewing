package com.example.bthviewing

import android.content.ContentResolver
import android.net.Uri
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экспорт истории в CSV, совместимый с GraphMemo.html.
 *
 * Формат:
 *   Time-Data=(A2/86400)+25569;Temp;Humi;Vbat
 *   1700000000;21.34;55.20;3010
 *
 * Разделитель ';' — открывается в Excel (RU) двойным кликом.
 */
object CsvExporter {

    fun writeTo(
        contentResolver: ContentResolver,
        uri: Uri,
        records: List<MemoRecord>,
        deviceName: String?
    ) {
        contentResolver.openOutputStream(uri, "wt")?.use { os ->
            OutputStreamWriter(os, Charsets.UTF_8).use { w ->
                // BOM — чтобы Excel не портил кириллицу в названиях колонок
                w.write("\uFEFF")
                if (!deviceName.isNullOrBlank()) {
                    w.write(deviceName)
                    w.write("\r\n")
                }
                w.write("Time-Data=(A2/86400)+25569;Temp;Humi;Vbat\r\n")
                for (r in records) {
                    w.write(r.timeSec.toString())
                    w.write(";")
                    w.write("%.2f".format(r.temperature))
                    w.write(";")
                    w.write("%.2f".format(r.humidity))
                    w.write(";")
                    w.write(r.batteryMv.toString())
                    w.write("\r\n")
                }
            }
        }
    }

    fun defaultFileName(deviceName: String?): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val base = (deviceName ?: "bthome").replace(Regex("[^A-Za-z0-9_\\-]"), "_")
        return "${base}_$stamp.csv"
    }
}