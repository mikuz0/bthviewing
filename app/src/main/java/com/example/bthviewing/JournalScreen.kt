package com.example.bthviewing

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.bthviewing.ui.chart.HistoryChart
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun JournalScreen(
    currentDeviceAddress: String?,
    currentDeviceName: String?,
    viewModel: JournalViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(currentDeviceAddress, currentDeviceName) {
        viewModel.setTarget(currentDeviceAddress, currentDeviceName)
    }

    var tab by remember { mutableStateOf(0) }

    // --- SAF launcher для сохранения CSV ---
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(mimeType = "text/csv")
    ) { uri ->
        if (uri != null) {
            viewModel.exportCsv(uri)
        }
    }

    // Автоскрытие сообщения об экспорте
    LaunchedEffect(state.exportMessage) {
        if (state.exportMessage != null) {
            delay(3000)
            viewModel.clearExportMessage()
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = state.deviceName ?: state.deviceAddress ?: "Устройство не выбрано",
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
        Spacer(Modifier.height(8.dp))

        // --- Кнопки действий ---
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { viewModel.startRead() },
                enabled = state.deviceAddress != null && !state.isReading
            ) {
                Text(if (state.isReading) "Чтение…" else "Считать данные")
            }
            if (state.isReading) {
                Spacer(Modifier.width(12.dp))
                OutlinedButton(
                    onClick = { viewModel.cancelRead() },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Отменить") }
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

        // Сообщение об экспорте
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
                Text("  Прочитано: ${state.records.size}")
            }
        }

        state.error?.let {
            Spacer(Modifier.height(8.dp))
            Text("Ошибка: $it", color = MaterialTheme.colorScheme.error)
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
                HistoryChart(
                    records = state.records,
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else if (state.done) {
            Spacer(Modifier.height(8.dp))
            Text("История пуста", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun RecordsTable(records: List<MemoRecord>) {
    val fmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text("Время", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.4f))
            Text("T, °C", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("H, %", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        Divider()
        LazyColumn {
            items(records) { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(fmt.format(Date(r.timeSec * 1000)), modifier = Modifier.weight(1.4f), fontSize = 13.sp)
                    Text("%.1f".format(r.temperature), modifier = Modifier.weight(1f), fontSize = 13.sp)
                    Text("%.1f".format(r.humidity), modifier = Modifier.weight(1f), fontSize = 13.sp)
                }
            }
        }
    }
}