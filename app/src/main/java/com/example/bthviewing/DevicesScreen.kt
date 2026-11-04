package com.example.bthviewing

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.material.icons.filled.Stop // дубликат импорта, оставлен для совместимости

@Composable
fun DevicesScreen(
    viewModel: MainViewModel,
    onRequestPermissions: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    // Запрос разрешений при первом показе. Прослушивание запускается в MainViewModel.init.
    LaunchedEffect(Unit) {
        onRequestPermissions()
        viewModel.startListening()
    }

    // При уходе с экрана — остановить обновление списка (прослушивание продолжается).
    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopRefresh()
        }
    }

    var devicesExpanded by rememberSaveable { mutableStateOf(true) }

    val filteredDevices = remember(state.devices, state.showOnlyBthome) {
        state.devices.values
            .filter { !state.showOnlyBthome || it.hasBthomeData }
            .sortedByDescending { it.rssi }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 1. Кнопка «Обновить список устройств» + таймер + статус прослушивания
        item(key = "header_refresh") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        if (state.isRefreshingList) {
                            viewModel.stopRefresh()
                        } else {
                            viewModel.refreshDeviceList()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (state.isRefreshingList) Icons.Default.Stop
                        else Icons.Default.Refresh,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (state.isRefreshingList) stringResource(R.string.btn_stop)
                        else stringResource(R.string.btn_refresh_list)
                    )
                }
                if (state.isRefreshingList && state.refreshSecondsLeft > 0) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.time_sec, state.refreshSecondsLeft),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            ListeningStatus(
                bluetoothReady = state.bluetoothReady,
                hasPermissions = state.hasPermissions,
                locationEnabled = state.locationEnabled,
                isListening = state.isListening,
                selectedName = state.selectedName,
                selectedAddress = state.selectedAddress
            )
            Spacer(Modifier.height(16.dp))
        }

        // 3. Чекбокс + список (только если раскрыто)
        if (devicesExpanded) {
            item(key = "only_bthome") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setShowOnlyBthome(!state.showOnlyBthome) }
                        .padding(vertical = 4.dp)
                ) {
                    Checkbox(
                        checked = state.showOnlyBthome,
                        onCheckedChange = { viewModel.setShowOnlyBthome(it) }
                    )
                    Text(stringResource(R.string.show_only_bthome))
                }
            }

            if (filteredDevices.isEmpty()) {
                item(key = "empty_list") {
                    Text(
                        text = if (state.isRefreshingList) stringResource(R.string.searching_devices)
                        else stringResource(R.string.list_empty),
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(filteredDevices, key = { it.address }) { dev ->
                    val selected = dev.address == state.selectedAddress
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { viewModel.selectDevice(dev.address) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(Modifier.padding(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    dev.name ?: dev.address,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                RssiBadge(dev.rssi)
                            }
                            Text(
                                "${dev.address} · ${if (dev.hasBthomeData) "BTHome" else "—"}",
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        // 4. Разделитель и «Выбранное устройство»
        item(key = "divider") {
            Spacer(Modifier.height(16.dp))
            Divider()
            Spacer(Modifier.height(16.dp))
            AccordionHeader(
                title = stringResource(R.string.selected_device),
                expanded = true,
                onToggle = { },
                showArrow = false
            )
        }

        // 5. Карточка выбранного
        item(key = "selected_card") {
            val reading = state.lastReading
            val selectedAddress = state.selectedAddress // <--- ДЕЛАЕМ СНИМОК

            if (reading == null) {
                if (selectedAddress != null) { // Теперь компилятор точно знает, что внутри selectedAddress не null
                    val label = state.selectedName ?: selectedAddress // <--- ИСПОЛЬЗУЕМ СНИМОК
                    Text(
                        text = if (state.isListening)
                            stringResource(R.string.waiting_for_data, label)
                        else
                            stringResource(R.string.no_data_from, label),


                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = stringResource(R.string.choose_device_hint),
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                SelectedDeviceCard(reading)
            }
        }

        // 6. Ошибка
        if (state.error != null) {
            item(key = "error") {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.error_prefix, state.error!!),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/* ------------------------------------------------------------------------- */
/*                     Вспомогательные composable-функции                    */
/* ------------------------------------------------------------------------- */

@Composable
fun ListeningStatus(
    bluetoothReady: Boolean,
    hasPermissions: Boolean,
    locationEnabled: Boolean,
    isListening: Boolean,
    selectedName: String?,
    selectedAddress: String?
) {
    if (selectedAddress == null) {
        Text(
            text = stringResource(R.string.status_no_device_selected),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    val label = selectedName ?: selectedAddress

    var hasIssue = false

    if (!locationEnabled) {
        Text(
            text = stringResource(R.string.status_location_off),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold
        )
        hasIssue = true
    }
    if (!bluetoothReady) {
        Text(
            text = stringResource(R.string.status_bluetooth_off),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold
        )
        hasIssue = true
    }
    if (!hasPermissions) {
        Text(
            text = stringResource(R.string.status_no_permissions),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold
        )
        hasIssue = true
    }

    if (hasIssue) {
        return
    }

    if (isListening) {
        Text(
            text = stringResource(R.string.status_listening, label),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary
        )
    } else {
        Text(
            text = stringResource(R.string.status_not_listening),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
fun AccordionHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    showArrow: Boolean = true,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = showArrow) { onToggle() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            modifier = Modifier.weight(1f)
        )
        action?.invoke()
        if (showArrow) {
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp
                else Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(
                    if (expanded) R.string.cd_collapse else R.string.cd_expand
                )
            )
        }
    }
}

@Composable
fun RssiBadge(rssi: Int) {
    val color = when {
        rssi >= -60 -> MaterialTheme.colorScheme.primary
        rssi >= -80 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    Text(
        text = stringResource(R.string.label_rssi, rssi),
        fontSize = 12.sp,
        color = color,
        fontWeight = FontWeight.Medium
    )
}

@Composable
fun SelectedDeviceCard(reading: BthomeData) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val elapsedSec = ((now - reading.timestamp) / 1000).coerceAtLeast(0)
    val isStale = MainViewModel.isStale(reading)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                reading.deviceName ?: reading.deviceAddress,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            Spacer(Modifier.height(4.dp))

            val updatedText = formatElapsed(elapsedSec)
            if (isStale) {
                Text(
                    text = stringResource(R.string.stale_updated_ago, updatedText),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium
                )
            } else {
                Text(
                    text = stringResource(R.string.updated_ago, updatedText),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                BigValue(stringResource(R.string.label_temp), reading.temperature?.let { "%.1f °C".format(it) } ?: "—", isStale)
                BigValue(stringResource(R.string.label_humi), reading.humidity?.let { "%.1f %%".format(it) } ?: "—", isStale)
                BigValue(stringResource(R.string.label_battery), reading.battery?.let { "$it %" } ?: "—", isStale)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(
                    R.string.label_voltage,
                    reading.voltage?.let { "%.3f V".format(it) } ?: "—"
                ) + "   ·   " + stringResource(R.string.label_rssi, reading.rssi),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun BigValue(label: String, value: String, isStale: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = if (isStale) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun formatElapsed(sec: Long): String {
    return when {
        sec < 60 -> stringResource(R.string.time_sec, sec.toInt())
        sec < 3600 -> stringResource(R.string.time_min, (sec / 60).toInt())
        sec < 86400 -> stringResource(R.string.time_hour, (sec / 3600).toInt())
        else -> stringResource(R.string.time_day, (sec / 86400).toInt())
    }
}