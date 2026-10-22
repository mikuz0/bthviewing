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
        _uiState.value = _uiState.value.copy(
            deviceAddress = address,
            deviceName = name,
            records = emptyList(),
            done = false,
            error = null,
            isReading = false,
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
            records = emptyList(),
            done = false,
            error = null,
            exportMessage = null
        )

        job = viewModelScope.launch {
            try {
                client.readHistory(device, GattMemoClient.ALL_RECORDS).collect { ev ->
                    when (ev) {
                        is GattMemoClient.Event.Record -> {
                            _uiState.value = _uiState.value.copy(
                                records = _uiState.value.records + ev.record
                            )
                        }
                        is GattMemoClient.Event.Progress -> Unit
                        GattMemoClient.Event.Done -> {
                            _uiState.value = _uiState.value.copy(
                                isReading = false,
                                done = true
                            )
                        }
                        is GattMemoClient.Event.Error -> {
                            _uiState.value = _uiState.value.copy(
                                isReading = false,
                                error = ev.message
                            )
                        }
                    }
                }
            } catch (ce: CancellationException) {
                _uiState.value = _uiState.value.copy(isReading = false)
                throw ce
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isReading = false,
                    error = e.message ?: "Ошибка чтения"
                )
            }
        }
    }

    fun cancelRead() {
        job?.cancel()
        job = null
        _uiState.value = _uiState.value.copy(isReading = false)
    }

    /** Экспорт уже прочитанных записей в CSV по выбранному через SAF uri. */
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
        job?.cancel()
    }
}