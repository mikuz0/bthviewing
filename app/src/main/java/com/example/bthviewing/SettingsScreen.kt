package com.example.bthviewing

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
    appViewModel: AppViewModel = viewModel(),
    mainViewModel: MainViewModel = viewModel()
) {
    LaunchedEffect(deviceAddress) {
        viewModel.setTarget(deviceAddress)
    }

    val s by viewModel.sensor.collectAsState()

    LaunchedEffect(s.loading, s.saving, s.timeReading, s.timeSetting) {
        if (!s.loading && !s.saving && !s.timeReading && !s.timeSetting) {
            mainViewModel.startListening()
        }
    }

    val tabs = listOf("Настроить датчик", "Настроить приложение")
    var tab by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
            }
        }
        when (tab) {
            0 -> SensorTab(viewModel, mainViewModel, deviceAddress, deviceName)
            1 -> AppTab(appViewModel, mainViewModel)
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
    val timeFmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // ------------------ Шапка: с каким датчиком работаем ------------------
        Text(
            text = deviceName ?: deviceAddress ?: "Устройство не выбрано",
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
        Spacer(Modifier.height(12.dp))

        // ------------------ Коэффициенты ------------------
        if (s.mid == 0 && s.vid == 0 && !s.loaded) {
            Text(
                "Нажмите «Прочитать», чтобы получить коэффициенты калибровки",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(12.dp))
        } else {
            Text(
                "ID: %04X-%04X, I2C: 0x%02X".format(s.mid, s.vid, s.i2cAddr),
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
        s.error?.let {
            Spacer(Modifier.height(8.dp))
            Text("Ошибка: $it", color = MaterialTheme.colorScheme.error)
        }

        // ------------------ Время датчика ------------------

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text("Время датчика", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Прочитать текущее время с датчика или установить его. " +
                    "Новые записи истории будут с правильной датой. " +
                    "Уже записанные — не изменятся.",
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
                    Text("Чтение…")
                } else {
                    Text("Прочитать время")
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
                    Text("Установка…")
                } else {
                    Text("Установить время")
                }
            }
        }

        if (s.timeReadOk) {
            Spacer(Modifier.height(8.dp))
            val curSec = s.timeCur
            val isValid = curSec > 1_000_000_000L

            // Смещение текущей таймзоны телефона (то же, что setDeviceTime вычитает)
            val offsetMs = TimeZone.getDefault().getOffset(System.currentTimeMillis())
            val offsetSign = if (offsetMs >= 0) "+" else "-"
            val absOffsetMs = kotlin.math.abs(offsetMs)
            val offsetHours = absOffsetMs / 3_600_000
            val offsetMinutes = (absOffsetMs % 3_600_000) / 60_000
            val utcSuffix = "UTC%s%02d:%02d".format(offsetSign, offsetHours, offsetMinutes)

            val curText = if (isValid) {
                timeFmt.format(Date(curSec * 1000L))
            } else {
                "не установлено (1970)"
            }
            Text(
                "Время устройства: $curText ($utcSuffix)",
                fontSize = 13.sp,
                color = if (isValid)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.error
            )
            if (isValid) {
                val localText = timeFmt.format(Date((curSec + offsetMs / 1000L) * 1000L))
                Text(
                    "Локально у вас: $localText",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (s.timeCurSet > 1_000_000_000L) {
                Text(
                    "Последняя установка: ${timeFmt.format(Date(s.timeCurSet * 1000L))} ($utcSuffix)",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (s.timeSetOk) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Время установлено",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }

        s.timeReadError?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                "Ошибка чтения времени: $it",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error
            )
        }
        s.timeSetError?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                "Ошибка установки времени: $it",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/* ------------------------ Настроить приложение -------------------------- */

@Composable
private fun AppTab(
    vm: AppViewModel,
    mainViewModel: MainViewModel
) {
    val dark by vm.darkTheme.collectAsState()
    val lockPortrait by vm.lockPortrait.collectAsState()

    var showClearDialog by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Оформление", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Тёмная тема")
                Text(
                    if (dark) "Включена" else "Выключена",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = dark, onCheckedChange = { vm.setDarkTheme(it) })
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text("Экран", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Запретить поворот экрана")
                Text(
                    if (lockPortrait) "Только книжная ориентация" else "Свободный поворот",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = lockPortrait, onCheckedChange = { vm.setLockPortrait(it) })
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text("Данные", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = { showClearDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Очистить список устройств")
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Удаляет сохранённый список найденных устройств. " +
                    "Выбранный датчик и текущие показания остаются.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text(
            "Настройки хранятся на устройстве и применяются сразу.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Очистить список устройств?") },
            text = {
                Text(
                    "Список найденных устройств будет удалён. " +
                            "При необходимости его можно будет обновить кнопкой «Обновить список устройств». " +
                            "Выбранный датчик останется."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    mainViewModel.clearDeviceList()
                    showClearDialog = false
                }) {
                    Text("Очистить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Отмена")
                }
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
    ) {
        Text("Применить")
    }
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
        OutlinedButton(onClick = onRead, enabled = !loading && !saving) { Text("Прочитать") }
        Button(onClick = onWrite, enabled = !loading && !saving) { Text("Записать") }
        if (onDefault != null) {
            OutlinedButton(onClick = onDefault, enabled = !loading && !saving) { Text("Default") }
        }
        if (loading || saving) {
            CircularProgressIndicator(Modifier.height(24.dp).width(24.dp))
        }
    }
}