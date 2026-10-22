package com.example.bthviewing

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class JournalUiState(
    val deviceAddress: String? = null,
    val deviceName: String? = null,
    val isReading: Boolean = false,
    val isCancelling: Boolean = false,
    val phase: String? = null,
    val records: List<MemoRecord> = emptyList(),
    val error: String? = null,
    val done: Boolean = false,
    val isExporting: Boolean = false,
    val exportMessage: String? = null
)

@SuppressLint("MissingPermission")
class JournalViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "JournalViewModel"
    }

    private val client = GattMemoClient(application)
    private val _uiState = MutableStateFlow(JournalUiState())
    val uiState: StateFlow<JournalUiState> = _uiState.asStateFlow()
    private var job: Job? = null

    fun setTarget(address: String?, name: String?) {
        if (_uiState.value.deviceAddress != address && _uiState.value.isReading) {
            client.cancel()
        }
        _uiState.value = _uiState.value.copy(
            deviceAddress = address,
            deviceName = name,
            records = emptyList(),
            done = false,
            error = null,
            isReading = false,
            isCancelling = false,
            phase = null,
            exportMessage = null
        )
    }

    @SuppressLint("MissingPermission")
    fun startRead() {
        val address = _uiState.value.deviceAddress ?: return
        if (_uiState.value.isReading) {
            Log.w(TAG, "startRead: уже читаем, игнор")
            return
        }

        val mgr = getApplication<Application>()
            .getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val device = mgr?.adapter?.getRemoteDevice(address) ?: run {
            _uiState.value = _uiState.value.copy(error = "Устройство не найдено")
            return
        }

        job?.cancel()
        _uiState.value = _uiState.value.copy(
            isReading = true,
            isCancelling = false,
            phase = "Подключение…",
            records = emptyList(),
            done = false,
            error = null,
            exportMessage = null
        )

        job = viewModelScope.launch {
            try {
                client.readHistory(device, GattMemoClient.ALL_RECORDS).collect { ev ->
                    when (ev) {
                        GattMemoClient.Event.Probe -> {
                            _uiState.value = _uiState.value.copy(phase = "Проверка отклика…")
                        }
                        is GattMemoClient.Event.Record -> {
                            val cur = _uiState.value
                            val newPhase = if (cur.phase == "Чтение истории…") cur.phase
                            else "Чтение истории…"
                            _uiState.value = cur.copy(
                                phase = newPhase,
                                records = cur.records + ev.record
                            )
                        }
                        is GattMemoClient.Event.Progress -> Unit
                        GattMemoClient.Event.Done -> {
                            _uiState.value = _uiState.value.copy(
                                isReading = false,
                                isCancelling = false,
                                phase = "Готово",
                                done = true
                            )
                        }
                        GattMemoClient.Event.Cancelled -> {
                            _uiState.value = _uiState.value.copy(
                                isReading = false,
                                isCancelling = false,
                                phase = null
                            )
                        }
                        is GattMemoClient.Event.Error -> {
                            _uiState.value = _uiState.value.copy(
                                isReading = false,
                                isCancelling = false,
                                phase = null,
                                error = ev.message
                            )
                        }
                    }
                }
            } catch (ce: CancellationException) {
                _uiState.value = _uiState.value.copy(
                    isReading = false,
                    isCancelling = false,
                    phase = null
                )
                throw ce
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isReading = false,
                    isCancelling = false,
                    phase = null,
                    error = e.message ?: "Ошибка чтения"
                )
            }
        }
    }

    fun cancelRead() {
        if (!_uiState.value.isReading) return
        _uiState.value = _uiState.value.copy(
            isCancelling = true,
            phase = "Остановка…"
        )
        client.cancel()
    }

    fun exportCsv(uri: Uri) {
        val records = _uiState.value.records
        if (records.isEmpty()) {
            _uiState.value = _uiState.value.copy(exportMessage = "Нет данных для экспорта")
            return
        }
        _uiState.value = _uiState.value.copy(isExporting = true, exportMessage = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    CsvExporter.writeTo(
                        contentResolver = getApplication<Application>().contentResolver,
                        uri = uri,
                        records = records,
                        deviceName = _uiState.value.deviceName ?: _uiState.value.deviceAddress
                    )
                }
                _uiState.value = _uiState.value.copy(
                    isExporting = false,
                    exportMessage = "Экспортировано ${records.size} записей"
                )
            } catch (e: Exception) {
                Log.w(TAG, "exportCsv", e)
                _uiState.value = _uiState.value.copy(
                    isExporting = false,
                    exportMessage = "Ошибка экспорта: ${e.message}"
                )
            }
        }
    }

    fun clearExportMessage() {
        _uiState.value = _uiState.value.copy(exportMessage = null)
    }

    override fun onCleared() {
        super.onCleared()
        client.cancel()
        job?.cancel()
    }
}