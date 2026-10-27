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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
fun SettingsScreen(
    deviceAddress: String?,
    deviceName: String?,
    viewModel: SettingsViewModel = viewModel(),
    mainViewModel: MainViewModel = viewModel()
) {
    LaunchedEffect(deviceAddress) {
        viewModel.setTarget(deviceAddress)
    }

    val s by viewModel.sensor.collectAsState()

    LaunchedEffect(s.loading, s.saving, s.timeReading, s.timeSetting, s.clearingHistory) {
        if (!s.loading && !s.saving && !s.timeReading && !s.timeSetting && !s.clearingHistory) {
            mainViewModel.startListening()
        }
    }

    val tabs = listOf(
        stringResource(R.string.tab_sensor),
        stringResource(R.string.tab_app)
    )
    var tab by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
            }
        }
        when (tab) {
            0 -> SensorTab(viewModel, mainViewModel, deviceAddress, deviceName)
            1 -> AppTab(mainViewModel)
        }
    }
}

/* -------------------------- Настроить датчик ---------------------------- */

@Composable
private fun SensorTab(
    vm: SettingsViewModel,
    mainViewModel: MainViewModel,
    deviceAddress: String?,
    deviceName: String?
) {
    val s by vm.sensor.collectAsState()
    val context = LocalContext.current
    val timeFmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()) }

    var coefficientsExpanded by remember(deviceAddress) { mutableStateOf(false) }
    var showWarningDialog by remember(deviceAddress) { mutableStateOf(false) }
    var warningShownThisSession by remember(deviceAddress) { mutableStateOf(false) }
    var showClearHistoryDialog by remember(deviceAddress) { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = deviceName ?: deviceAddress ?: stringResource(R.string.device_not_selected),
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
        if (deviceAddress != null) {
            Text(
                deviceAddress,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            val wifiText = remember { WifiStatus.text(context) }
            Text(
                text = wifiText,
                fontSize = 12.sp,
                color = if (wifiText.contains(stringResource(R.string.wifi_on)))
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.wifi_warning),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(16.dp))

        // Коэффициенты
        if (!coefficientsExpanded) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (warningShownThisSession) coefficientsExpanded = true
                        else showWarningDialog = true
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.coeff_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.coeff_warning_short),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    if (warningShownThisSession) coefficientsExpanded = true
                    else showWarningDialog = true
                }
            ) { Text(stringResource(R.string.btn_show_coeff)) }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { coefficientsExpanded = false }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.coeff_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = null)
            }
            Text(
                stringResource(R.string.coeff_warning_red),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(8.dp))

            if (s.mid == 0 && s.vid == 0 && !s.loaded) {
                Text(
                    stringResource(R.string.press_read_coeff),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(12.dp))
            } else {
                Text(
                    stringResource(R.string.sensor_id_fmt, s.mid, s.vid, s.i2cAddr),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
            }

            NumField("temp_k (uint32, ×0.01)", s.tempK.toString()) {
                it.toLongOrNull()?.let { v -> vm.updateSensor(tempK = v) }
            }
            NumField("humi_k (uint32, ×0.01)", s.humiK.toString()) {
                it.toLongOrNull()?.let { v -> vm.updateSensor(humiK = v) }
            }
            NumField("temp_z (sint16, ×0.01 °C)", s.tempZ.toString()) {
                it.toIntOrNull()?.let { v -> vm.updateSensor(tempZ = v) }
            }
            NumField("humi_z (sint16, ×0.01 %)", s.humiZ.toString()) {
                it.toIntOrNull()?.let { v -> vm.updateSensor(humiZ = v) }
            }

            Spacer(Modifier.height(16.dp))
            ReadWriteDefault(
                loading = s.loading,
                saving = s.saving,
                onRead = {
                    mainViewModel.stopListening()
                    vm.readSensor()
                },
                onWrite = {
                    mainViewModel.stopListening()
                    vm.writeSensor()
                },
                onDefault = {
                    mainViewModel.stopListening()
                    vm.resetSensor()
                }
            )

            if (s.loading || s.saving || (s.phase != null && !s.isFinished)) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (s.loading || s.saving) {
                        CircularProgressIndicator(Modifier.height(18.dp).width(18.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = s.phase ?: if (s.loading) stringResource(R.string.btn_reading) 
                                       else stringResource(R.string.btn_write),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            s.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.error_prefix, it), color = MaterialTheme.colorScheme.error)
            }
        }

        // Время
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text(stringResource(R.string.time_title), fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.time_desc),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    mainViewModel.stopListening()
                    vm.readDeviceTime()
                },
                enabled = !s.timeReading && !s.timeSetting,
                modifier = Modifier.weight(1f)
            ) {
                if (s.timeReading) {
                    CircularProgressIndicator(Modifier.height(18.dp).width(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.btn_reading))
                } else {
                    Text(stringResource(R.string.btn_read_time))
                }
            }
            Button(
                onClick = {
                    mainViewModel.stopListening()
                    vm.setDeviceTime()
                },
                enabled = !s.timeReading && !s.timeSetting,
                modifier = Modifier.weight(1f)
            ) {
                if (s.timeSetting) {
                    CircularProgressIndicator(Modifier.height(18.dp).width(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.time_setting))
                } else {
                    Text(stringResource(R.string.btn_set_time))
                }
            }
        }

        if (s.timeReading || s.timeSetting || (s.phase != null && !s.isFinished)) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (s.timeReading || s.timeSetting) {
                    CircularProgressIndicator(Modifier.height(18.dp).width(18.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = s.phase ?: if (s.timeReading) stringResource(R.string.btn_reading) 
                                   else stringResource(R.string.time_setting),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        if (s.timeReadOk) {
            Spacer(Modifier.height(8.dp))
            val curSec = s.timeCur
            val isValid = curSec > 1_000_000_000L
            val offsetMs = TimeZone.getDefault().getOffset(System.currentTimeMillis())
            val offsetSign = if (offsetMs >= 0) "+" else "-"
            val absOffsetMs = kotlin.math.abs(offsetMs)
            val offsetHours = absOffsetMs / 3_600_000
            val offsetMinutes = (absOffsetMs % 3_600_000) / 60_000
            val utcSuffix = "UTC%s%02d:%02d".format(offsetSign, offsetHours, offsetMinutes)

            val curText = if (isValid) timeFmt.format(Date(curSec * 1000L))
                          else stringResource(R.string.time_not_set)
            Text(
                stringResource(R.string.time_device_fmt, curText, utcSuffix),
                fontSize = 13.sp,
                color = if (isValid) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
            )
            if (isValid) {
                val localText = timeFmt.format(Date((curSec + offsetMs / 1000L) * 1000L))
                Text(
                    stringResource(R.string.time_local_fmt, localText),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (s.timeCurSet > 1_000_000_000L) {
                Text(
                    stringResource(R.string.time_last_set_fmt, timeFmt.format(Date(s.timeCurSet * 1000L)), utcSuffix),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (s.timeSetOk) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.time_set_ok), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        }
        s.timeReadError?.let {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.time_read_error, it), fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
        }
        s.timeSetError?.let {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.time_set_error, it), fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
        }

        // История
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text(stringResource(R.string.history_title), fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.history_desc),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = { showClearHistoryDialog = true },
            enabled = !s.clearingHistory,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            if (s.clearingHistory) {
                CircularProgressIndicator(Modifier.height(18.dp).width(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.clearing_history))
            } else {
                Text(stringResource(R.string.btn_clear_history))
            }
        }

        if (s.clearingHistory || (s.phase != null && !s.isFinished)) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (s.clearingHistory) {
                    CircularProgressIndicator(Modifier.height(18.dp).width(18.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = s.phase ?: stringResource(R.string.clearing_history),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        if (s.clearHistoryOk) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.history_cleared), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        }
        s.clearHistoryError?.let {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.history_clear_error, it), fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
        }
    }

    // Диалоги
    if (showWarningDialog) {
        AlertDialog(
            onDismissRequest = { showWarningDialog = false },
            title = { Text(stringResource(R.string.dialog_warning_title)) },
            text = { Text(stringResource(R.string.coeff_warning_long)) },
            confirmButton = {
                TextButton(onClick = {
                    warningShownThisSession = true
                    coefficientsExpanded = true
                    showWarningDialog = false
                }) { Text(stringResource(R.string.dialog_understand_open)) }
            },
            dismissButton = {
                TextButton(onClick = { showWarningDialog = false }) { Text(stringResource(R.string.dialog_cancel)) }
            }
        )
    }

    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = { Text(stringResource(R.string.dialog_clear_history_title)) },
            text = { Text(stringResource(R.string.dialog_clear_history_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearHistoryDialog = false
                    mainViewModel.stopListening()
                    vm.clearHistory()
                }) { Text(stringResource(R.string.dialog_clear), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) { Text(stringResource(R.string.dialog_cancel)) }
            }
        )
    }
}

/* ------------------------ Настроить приложение -------------------------- */

@Composable
private fun AppTab(mainViewModel: MainViewModel) {
    val state by mainViewModel.uiState.collectAsState()
    val dark = state.darkTheme
    val lockPortrait = state.lockPortrait

    var showClearDialog by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(stringResource(R.string.app_appearance), fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.app_dark_theme))
                Text(
                    if (dark) stringResource(R.string.app_dark_theme_on) 
                    else stringResource(R.string.app_dark_theme_off),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = dark, onCheckedChange = { mainViewModel.setDarkTheme(it) })
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text(stringResource(R.string.app_screen), fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.app_lock_portrait))
                Text(
                    if (lockPortrait) stringResource(R.string.app_lock_portrait_on) 
                    else stringResource(R.string.app_lock_portrait_off),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = lockPortrait, onCheckedChange = { mainViewModel.setLockPortrait(it) })
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text(stringResource(R.string.app_data), fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = { showClearDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.app_clear_devices)) }
        
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.app_clear_devices_desc),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text(
            stringResource(R.string.app_saved_locally),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.dialog_clear_devices_title)) },
            text = { Text(stringResource(R.string.dialog_clear_devices_text)) },
            confirmButton = {
                TextButton(onClick = {
                    mainViewModel.clearDeviceList()
                    showClearDialog = false
                }) { Text(stringResource(R.string.dialog_clear), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text(stringResource(R.string.dialog_cancel)) }
            }
        )
    }
}

/* ----------------------------- helpers ---------------------------------- */

@Composable
private fun NumField(label: String, value: String, onCommit: (String) -> Unit) {
    var txt by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = txt,
        onValueChange = { txt = it },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    )
    OutlinedButton(
        onClick = { onCommit(txt) },
        modifier = Modifier.height(36.dp)
    ) { Text(stringResource(R.string.btn_apply)) }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun ReadWriteDefault(
    loading: Boolean,
    saving: Boolean,
    onRead: () -> Unit,
    onWrite: () -> Unit,
    onDefault: (() -> Unit)?
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(onClick = onRead, enabled = !loading && !saving) { Text(stringResource(R.string.btn_read)) }
        Button(onClick = onWrite, enabled = !loading && !saving) { Text(stringResource(R.string.btn_write)) }
        if (onDefault != null) {
            OutlinedButton(onClick = onDefault, enabled = !loading && !saving) { Text(stringResource(R.string.btn_default)) }
        }
        if (loading || saving) {
            CircularProgressIndicator(Modifier.height(24.dp).width(24.dp))
        }
    }
}