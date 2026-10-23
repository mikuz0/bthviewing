package com.example.bthviewing

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.bthviewing.ui.chart.HistoryChart
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Периоды для графика. */
private enum class Period(val label: String, val durationSec: Long) {
    H6("6 часов", 6 * 3600L),
    DAY("Сутки", 24 * 3600L),
    WEEK("Неделя", 7 * 86400L),
    MONTH("Месяц", 30 * 86400L),
    YEAR("Год", 365 * 86400L)
}

@Composable
fun JournalScreen(
    currentDeviceAddress: String?,
    currentDeviceName: String?,
    viewModel: JournalViewModel = viewModel(),
    mainViewModel: MainViewModel
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val deviceAddress = state.deviceAddress

    LaunchedEffect(currentDeviceAddress, currentDeviceName) {
        viewModel.setTarget(currentDeviceAddress, currentDeviceName)
    }

    LaunchedEffect(state.isReading) {
        if (!state.isReading) {
            mainViewModel.startListening()
        }
    }

    var tab by remember { mutableStateOf(0) }
    var selectedPeriod by remember(state.deviceAddress) { mutableStateOf(Period.DAY) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(mimeType = "text/csv")
    ) { uri ->
        if (uri != null) {
            viewModel.exportCsv(uri)
        }
    }

    LaunchedEffect(state.exportMessage) {
        if (state.exportMessage != null) {
            delay(3000)
            viewModel.clearExportMessage()
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // ---------- Шапка: имя + адрес датчика ----------
        Text(
            text = state.deviceName ?: deviceAddress ?: "Устройство не выбрано",
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
        if (deviceAddress != null) {
            Text(
                deviceAddress,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // ---------- Предупреждение о Wi-Fi ----------
        if (deviceAddress != null) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            val wifiText = remember { WifiStatus.text(context) }
            Text(
                text = wifiText,
                fontSize = 12.sp,
                color = if (wifiText.contains("включён"))
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Отключите Wi-Fi (2.4 ГГц мешает BLE).",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(8.dp))

        // ---------- Кнопки действий ----------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    mainViewModel.stopListening()
                    viewModel.startRead()
                },
                enabled = state.deviceAddress != null && !state.isReading
            ) {
                Text(if (state.isReading) "Чтение…" else "Считать данные")
            }
            if (state.isReading) {
                Spacer(Modifier.width(12.dp))
                OutlinedButton(
                    onClick = { viewModel.cancelRead() },
                    enabled = !state.isCancelling,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(if (state.isCancelling) "Остановка…" else "Отменить")
                }
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(
                onClick = {
                    val suggested = CsvExporter.defaultFileName(
                        state.deviceName ?: state.deviceAddress
                    )
                    exportLauncher.launch(suggested)
                },
                enabled = state.records.isNotEmpty() && !state.isExporting
            ) {
                if (state.isExporting) {
                    CircularProgressIndicator(Modifier.height(18.dp).width(18.dp))
                } else {
                    Icon(Icons.Default.Share, contentDescription = null)
                }
                Spacer(Modifier.width(6.dp))
                Text("Экспорт CSV")
            }
        }

        state.exportMessage?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                it,
                fontSize = 13.sp,
                color = if (it.startsWith("Ошибка"))
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.primary
            )
        }

        if (state.isReading) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.height(24.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = state.phase ?: "Чтение…",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (state.records.isNotEmpty()) {
                        Text(
                            "Прочитано: ${state.records.size}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        state.error?.let {
            Spacer(Modifier.height(8.dp))
            Text("Ошибка: $it", color = MaterialTheme.colorScheme.error)
        }

        // ---------- Сводка объёма (видна на обеих вкладках) ----------
        if (state.records.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            SummaryText(state.records)
        }

        if (state.records.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Таблица") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("График") })
            }
            if (tab == 0) {
                RecordsTable(state.records)
            } else {
                // ---------- Периоды + график ----------
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Period.values().forEach { p ->
                        val selected = p == selectedPeriod
                        if (selected) {
                            Button(onClick = { selectedPeriod = p }) {
                                Text(p.label, fontSize = 13.sp)
                            }
                        } else {
                            OutlinedButton(onClick = { selectedPeriod = p }) {
                                Text(p.label, fontSize = 13.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                val chartRecords = remember(state.records, selectedPeriod) {
                    buildChartRecords(state.records, selectedPeriod)
                }

                if (chartRecords.isEmpty()) {
                    Text(
                        "Нет данных для отображения",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                } else {
                    HistoryChart(
                        records = chartRecords,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        } else if (state.done) {
            Spacer(Modifier.height(8.dp))
            Text("История пуста", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/* ----------------------- Сводка объёма ----------------------- */

@Composable
private fun SummaryText(records: List<MemoRecord>) {
    val fmt = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }
    val valid = remember(records) { records.filter { it.timeSec > 1_000_000_000L } }
    if (valid.isEmpty()) {
        Text(
            "${records.size} записей (время не установлено)",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    val first = valid.first().timeSec
    val last = valid.last().timeSec
    val periodText = formatPeriod(last - first)
    Text(
        "${records.size} записей · ${fmt.format(Date(first * 1000L))} — " +
                "${fmt.format(Date(last * 1000L))} ($periodText)",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/* ----------------------- Фильтр + усреднение ----------------------- */

private fun buildChartRecords(
    records: List<MemoRecord>,
    period: Period
): List<MemoRecord> {
    // Игнорируем записи с timeSec = 0 (1970)
    val valid = records.filter { it.timeSec > 1_000_000_000L }
    if (valid.isEmpty()) return emptyList()

    val lastTime = valid.last().timeSec
    val startTime = lastTime - period.durationSec
    val filtered = valid.filter { it.timeSec >= startTime }

    // Если за выбранный период пусто — показываем всё, что есть
    val base = if (filtered.isEmpty()) valid else filtered

    // Усреднение до 30 точек
    return if (base.size <= 30) base else downsampleToAvg(base, 30)
}

private fun downsampleToAvg(records: List<MemoRecord>, targetCount: Int): List<MemoRecord> {
    if (records.size <= targetCount) return records
    val bucketSize = records.size.toDouble() / targetCount
    val result = ArrayList<MemoRecord>(targetCount)
    for (i in 0 until targetCount) {
        val from = (i * bucketSize).toInt().coerceIn(0, records.size)
        val to = ((i + 1) * bucketSize).toInt().coerceIn(from, records.size)
        if (from >= to) continue
        val bucket = records.subList(from, to)
        val avgTemp = bucket.sumOf { it.temperature.toDouble() } / bucket.size
        val avgHumi = bucket.sumOf { it.humidity.toDouble() } / bucket.size
        val avgVbat = bucket.sumOf { it.batteryMv.toDouble() } / bucket.size
        val mid = bucket[bucket.size / 2]
        result.add(
            MemoRecord(
                index = bucket.first().index,
                timeSec = mid.timeSec,
                temperature = avgTemp.toFloat(),
                humidity = avgHumi.toFloat(),
                batteryMv = avgVbat.toInt()
            )
        )
    }
    return result
}

private fun formatPeriod(seconds: Long): String = when {
    seconds < 3600 -> "${(seconds / 60).coerceAtLeast(1)} мин"
    seconds < 86400 -> "${seconds / 3600} ч"
    seconds < 7 * 86400 -> "${seconds / 86400} дн"
    seconds < 30 * 86400 -> "${seconds / (7 * 86400)} нед"
    seconds < 365 * 86400 -> "${seconds / (30 * 86400)} мес"
    else -> "${seconds / (365 * 86400)} г"
}

/* ----------------------- Таблица ----------------------- */

@Composable
fun RecordsTable(records: List<MemoRecord>) {
    val fmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text("Время", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.4f))
            Text("T, °C", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("H, %", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        HorizontalDivider()
        LazyColumn {
            items(records) { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    val timeText = if (r.timeSec > 1_000_000_000L) {
                        fmt.format(Date(r.timeSec * 1000))
                    } else {
                        "—"
                    }
                    Text(timeText, modifier = Modifier.weight(1.4f), fontSize = 13.sp)
                    Text("%.1f".format(r.temperature), modifier = Modifier.weight(1f), fontSize = 13.sp)
                    Text("%.1f".format(r.humidity), modifier = Modifier.weight(1f), fontSize = 13.sp)
                }
            }
        }
    }
}