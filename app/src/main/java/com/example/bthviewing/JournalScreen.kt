package com.example.bthviewing

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.Color
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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
private enum class Period(val resId: Int, val durationSec: Long) {
    H6(R.string.period_6h, 6 * 3600L),
    DAY(R.string.period_day, 24 * 3600L),
    WEEK(R.string.period_week, 7 * 86400L),
    MONTH(R.string.period_month, 30 * 86400L),
    YEAR(R.string.period_year, 365 * 86400L)
}

@Composable
fun JournalScreen(
    currentDeviceAddress: String?,
    currentDeviceName: String?,
    viewModel: JournalViewModel = viewModel(),
    mainViewModel: MainViewModel
) {
    val state by viewModel.uiState.collectAsState()
    val mainState by mainViewModel.uiState.collectAsState()
    val bluetoothReady = mainState.bluetoothReady
    val context = LocalContext.current
    val deviceAddress = state.deviceAddress

    LaunchedEffect(currentDeviceAddress, currentDeviceName) {
        viewModel.setTarget(currentDeviceAddress, currentDeviceName)
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

    var isWifiEnabled by remember { mutableStateOf(!WifiStatus.text(context).contains(context.getString(R.string.wifi_on))) }

    LaunchedEffect(state.exportMessage) {
        if (state.exportMessage != null) {
            delay(3000)
            viewModel.clearExportMessage()
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // ---------- Шапка: имя + адрес датчика ----------
        Text(
            text = state.deviceName ?: deviceAddress ?: stringResource(R.string.device_not_selected),
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
            var wifiText by remember { mutableStateOf(WifiStatus.text(context)) }

            DisposableEffect(context) {
                val filter = IntentFilter("android.net.wifi.WIFI_STATE_CHANGED")
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (intent?.action == "android.net.wifi.WIFI_STATE_CHANGED") {
                            val newText = WifiStatus.text(context ?: return)
                            wifiText = newText
                            isWifiEnabled = newText.contains(context.getString(R.string.wifi_on))
                        }
                    }
                }
                context.registerReceiver(receiver, filter)
                onDispose {
                    context.unregisterReceiver(receiver)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (wifiText.contains(context.getString(R.string.wifi_on)))
                        "⚠️ Wi-Fi включён"
                    else
                        "✅ Wi-Fi выключен",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (wifiText.contains(context.getString(R.string.wifi_on)))
                        MaterialTheme.colorScheme.error
                    else
                        Color(0xFF2E7D32)
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = if (bluetoothReady)
                        "🔵 Bluetooth включён"
                    else
                        "🔴 Bluetooth выключен",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (bluetoothReady)
                        Color(0xFF2E7D32)
                    else
                        MaterialTheme.colorScheme.error
                )
            }
            Text(
                text = stringResource(R.string.wifi_warning),
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
                enabled = state.deviceAddress != null && !state.isReading && !isWifiEnabled && bluetoothReady
            ) {
                Text(
                    if (state.isReading) stringResource(R.string.btn_reading)
                    else stringResource(R.string.btn_read_data)
                )
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
                    Text(
                        if (state.isCancelling) stringResource(R.string.btn_cancelling)
                        else stringResource(R.string.btn_cancel)
                    )
                }
            }
            if (!state.isReading) {
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
                    Text(stringResource(R.string.btn_export_csv))
                }
            }
        }

        state.exportMessage?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                it,
                fontSize = 13.sp,
                color = if (state.exportOk == false)
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
                        text = state.phase ?: stringResource(R.string.btn_reading),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (state.records.isNotEmpty()) {
                        Text(
                            stringResource(R.string.records_read, state.records.size),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        state.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.error_prefix, it), color = MaterialTheme.colorScheme.error)
        }

        // ---------- Сводка объёма ----------
        if (state.records.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            SummaryText(state.records)
        }

        if (state.records.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.tab_table)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.tab_chart)) })
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
                                Text(stringResource(p.resId), fontSize = 13.sp)
                            }
                        } else {
                            OutlinedButton(onClick = { selectedPeriod = p }) {
                                Text(stringResource(p.resId), fontSize = 13.sp)
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
                        stringResource(R.string.no_data_for_chart),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                } else {
                    HistoryChart(
                        records = chartRecords,
                        periodLabel = when (selectedPeriod) {
                            Period.H6 -> "6h"
                            Period.DAY -> "day"
                            Period.WEEK -> "week"
                            Period.MONTH -> "month"
                            Period.YEAR -> "year"
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        } else if (state.done) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.history_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/* ----------------------- Сводка объёма ----------------------- */

@Composable
private fun SummaryText(records: List<MemoRecord>) {
    val fmt = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }
    val valid = remember(records) {
        records.filter { it.timeSec > 1_000_000_000L }.sortedBy { it.timeSec }
    }
    if (valid.isEmpty()) {
        Text(
            stringResource(R.string.summary_time_not_set, records.size),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    val first = valid.first().timeSec
    val last = valid.last().timeSec
    val periodText = formatPeriod(last - first)
    Text(
        stringResource(
            R.string.summary_records,
            records.size,
            fmt.format(Date(first * 1000L)),
            fmt.format(Date(last * 1000L)),
            periodText
        ),
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/* ----------------------- Фильтр + усреднение ----------------------- */

private fun buildChartRecords(
    records: List<MemoRecord>,
    period: Period
): List<MemoRecord> {
    val valid = records.filter { it.timeSec > 1_000_000_000L }.sortedBy { it.timeSec }
    if (valid.isEmpty()) return emptyList()

    val lastTime = valid.last().timeSec
    val startTime = lastTime - period.durationSec
    val filtered = valid.filter { it.timeSec >= startTime }

    val base = if (filtered.isEmpty()) valid else filtered

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

@Composable
private fun formatPeriod(seconds: Long): String = when {
    seconds < 3600 -> stringResource(R.string.time_min, (seconds / 60).toInt().coerceAtLeast(1))
    seconds < 86400 -> stringResource(R.string.time_hour, (seconds / 3600).toInt())
    seconds < 7 * 86400 -> stringResource(R.string.time_day, (seconds / 86400).toInt())
    seconds < 30 * 86400 -> stringResource(R.string.time_week, (seconds / (7 * 86400)).toInt())
    seconds < 365 * 86400 -> stringResource(R.string.time_month, (seconds / (30 * 86400)).toInt())
    else -> stringResource(R.string.time_year, (seconds / (365 * 86400)).toInt())
}

/* ----------------------- Таблица ----------------------- */

@Composable
fun RecordsTable(records: List<MemoRecord>) {
    val fmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(stringResource(R.string.time_col), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.4f))
            Text(stringResource(R.string.temp_col), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.humi_col), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
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