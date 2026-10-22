package com.example.bthviewing

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class UiState(
    val devices: Map<String, ScannedDevice> = emptyMap(),
    val selectedAddress: String? = null,
    val selectedName: String? = null,
    val lastReading: BthomeData? = null,
    val error: String? = null,
    val isRefreshingList: Boolean = false,
    val refreshSecondsLeft: Int = 0,
    val isListening: Boolean = false,
    val showOnlyBthome: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
        private const val REFRESH_DURATION_SECONDS = 120
        private const val STALE_THRESHOLD_MS = 5 * 60 * 1000L   // 5 минут

        private const val PREFS = "main_prefs"
        private const val KEY_SELECTED_ADDRESS = "selected_address"
        private const val KEY_SELECTED_NAME = "selected_name"
        private const val KEY_SHOW_ONLY_BTHOME = "show_only_bthome"
        private const val KEY_DEVICES = "devices_json"
        private const val KEY_READINGS_CACHE = "readings_cache_json"

        /** Максимум записей в кэше lastReading. При превышении — удаляем самую старую. */
        private const val MAX_CACHE_ENTRIES = 50

        fun isStale(reading: BthomeData): Boolean =
            System.currentTimeMillis() - reading.timestamp > STALE_THRESHOLD_MS
    }

    private val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scanner = BthomeScanner(application)

    private val _uiState = MutableStateFlow(
        UiState(
            devices = loadDevices(),
            selectedAddress = prefs.getString(KEY_SELECTED_ADDRESS, null),
            selectedName = prefs.getString(KEY_SELECTED_NAME, null),
            lastReading = null,
            showOnlyBthome = prefs.getBoolean(KEY_SHOW_ONLY_BTHOME, false)
        ).let { s ->
            // lastReading для восстановленного адреса
            val cached = s.selectedAddress?.let { loadCachedReading(it) }
            s.copy(
                selectedName = s.selectedName ?: cached?.deviceName,
                lastReading = cached
            )
        }
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var listenJob: Job? = null
    private var listRefreshJob: Job? = null
    private var refreshTimerJob: Job? = null

    init {
        startListening()
    }

    // ---------------------------------------------------- Обновление списка -

    fun refreshDeviceList() {
        if (listRefreshJob?.isActive == true) return
        if (!scanner.isBluetoothReady()) {
            _uiState.value = _uiState.value.copy(error = "Bluetooth выключен")
            return
        }
        if (!scanner.hasPermissions()) {
            _uiState.value = _uiState.value.copy(error = "Нет разрешений на Bluetooth")
            return
        }

        stopListening()

        _uiState.value = _uiState.value.copy(
            isRefreshingList = true,
            error = null,
            refreshSecondsLeft = REFRESH_DURATION_SECONDS
        )

        listRefreshJob = viewModelScope.launch {
            try {
                scanner.scanAll().collect { dev ->
                    val current = _uiState.value
                    val updated = current.devices + (dev.address to dev)
                    _uiState.value = current.copy(devices = updated)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.w(TAG, "refreshDeviceList", e)
                _uiState.value = _uiState.value.copy(
                    isRefreshingList = false,
                    error = e.message ?: "Ошибка сканирования"
                )
            }
        }

        refreshTimerJob?.cancel()
        refreshTimerJob = viewModelScope.launch {
            var left = REFRESH_DURATION_SECONDS
            while (left > 0) {
                _uiState.value = _uiState.value.copy(refreshSecondsLeft = left)
                delay(1000)
                left--
            }
            stopRefreshInternal()
        }
    }

    fun stopRefresh() {
        if (listRefreshJob?.isActive != true) return
        stopRefreshInternal()
    }

    private fun stopRefreshInternal() {
        listRefreshJob?.cancel()
        listRefreshJob = null
        refreshTimerJob?.cancel()
        refreshTimerJob = null

        saveDevices(_uiState.value.devices.values)

        _uiState.value = _uiState.value.copy(
            isRefreshingList = false,
            refreshSecondsLeft = 0
        )

        startListening()
    }

    // ------------------------------------------------------ Прослушивание --

    fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        _uiState.value = _uiState.value.copy(isListening = false)
    }

    fun startListening() {
        val address = _uiState.value.selectedAddress ?: return
        if (listenJob?.isActive == true) return
        if (!scanner.isBluetoothReady()) return
        if (!scanner.hasPermissions()) return
        if (_uiState.value.isRefreshingList) return

        _uiState.value = _uiState.value.copy(isListening = true)

        listenJob = viewModelScope.launch {
            try {
                scanner.scanFor(address).collect { dev ->
                    if (dev.address == address && dev.bthomeData != null) {
                        _uiState.value = _uiState.value.copy(lastReading = dev.bthomeData)
                        saveCachedReading(dev.bthomeData, dev.name ?: _uiState.value.selectedName)
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.w(TAG, "startListening", e)
                _uiState.value = _uiState.value.copy(
                    isListening = false,
                    error = e.message ?: "Ошибка прослушивания"
                )
            }
        }
    }

    // ---------------------------------------------------------- Устройства -

    fun selectDevice(address: String) {
        val current = _uiState.value
        val name = current.devices[address]?.name

        // Приоритет: свежие данные из скана (devices[address].bthomeData), иначе кэш из prefs.
        val reading = current.devices[address]?.bthomeData
            ?: loadCachedReading(address)

        _uiState.value = current.copy(
            selectedAddress = address,
            selectedName = name ?: reading?.deviceName,
            lastReading = reading
        )

        prefs.edit()
            .putString(KEY_SELECTED_ADDRESS, address)
            .putString(KEY_SELECTED_NAME, name ?: reading?.deviceName)
            .apply()

        stopListening()
        startListening()
    }

    fun clearSelectedDevice() {
        stopListening()
        // Кэш для адреса НЕ стираем — если пользователь вернётся, данные будут.
        _uiState.value = _uiState.value.copy(
            selectedAddress = null,
            selectedName = null,
            lastReading = null
        )
        prefs.edit()
            .remove(KEY_SELECTED_ADDRESS)
            .remove(KEY_SELECTED_NAME)
            .apply()
    }

    fun clearDeviceList() {
        _uiState.value = _uiState.value.copy(devices = emptyMap())
        prefs.edit().remove(KEY_DEVICES).apply()
        // Кэш lastReading не трогаем — данные для адресов могут понадобиться.
    }

    fun setShowOnlyBthome(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(showOnlyBthome = enabled)
        prefs.edit().putBoolean(KEY_SHOW_ONLY_BTHOME, enabled).apply()
    }

    // ------------------------------------------- Кэш lastReading по адресу -

    private fun loadCacheMap(): JSONObject {
        val s = prefs.getString(KEY_READINGS_CACHE, null) ?: return JSONObject()
        return try { JSONObject(s) } catch (_: Exception) { JSONObject() }
    }

    private fun saveCacheMap(obj: JSONObject) {
        prefs.edit().putString(KEY_READINGS_CACHE, obj.toString()).apply()
    }

    private fun loadCachedReading(address: String): BthomeData? {
        val map = loadCacheMap()
        val o = map.optJSONObject(address) ?: return null
        val ts = o.optLong("ts", 0L)
        if (ts <= 0L) return null
        val temp = o.optDouble("temp", Double.NaN).toFloat()
        val humi = o.optDouble("humi", Double.NaN).toFloat()
        val bat = o.optInt("bat", -1)
        val volt = o.optDouble("volt", Double.NaN).toFloat()
        val rssi = o.optInt("rssi", 0)
        val name = o.optString("name", "").takeIf { it.isNotEmpty() }
        return BthomeData(
            deviceAddress = address,
            deviceName = name,
            temperature = temp.takeUnless { it.isNaN() },
            humidity = humi.takeUnless { it.isNaN() },
            battery = bat.takeIf { it >= 0 },
            voltage = volt.takeUnless { it.isNaN() },
            rssi = rssi,
            timestamp = ts
        )
    }

    private fun saveCachedReading(reading: BthomeData, name: String?) {
        try {
            val map = loadCacheMap()
            val o = JSONObject()
            o.put("name", name ?: reading.deviceName ?: "")
            o.put("ts", reading.timestamp)
            o.put("temp", reading.temperature ?: Double.NaN)
            o.put("humi", reading.humidity ?: Double.NaN)
            o.put("bat", reading.battery ?: -1)
            o.put("volt", reading.voltage ?: Double.NaN)
            o.put("rssi", reading.rssi)
            map.put(reading.deviceAddress, o)

            // Ограничиваем размер кэша: удаляем самую старую запись
            if (map.length() > MAX_CACHE_ENTRIES) {
                var oldestKey: String? = null
                var oldestTs = Long.MAX_VALUE
                val it = map.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val ts = map.optJSONObject(k)?.optLong("ts", 0L) ?: 0L
                    if (ts < oldestTs) {
                        oldestTs = ts
                        oldestKey = k
                    }
                }
                oldestKey?.let { map.remove(it) }
            }

            saveCacheMap(map)
        } catch (e: Exception) {
            Log.w(TAG, "saveCachedReading", e)
        }
    }

    // ------------------------------------------------- Хранение списка prefs -

    private fun saveDevices(list: Collection<ScannedDevice>) {
        try {
            val arr = JSONArray()
            list.forEach { d ->
                val o = JSONObject()
                o.put("address", d.address)
                o.put("name", d.name ?: "")
                o.put("hasBthome", d.hasBthomeData)
                o.put("rssi", d.rssi)
                arr.put(o)
            }
            prefs.edit().putString(KEY_DEVICES, arr.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "saveDevices", e)
        }
    }

    private fun loadDevices(): Map<String, ScannedDevice> {
        val s = prefs.getString(KEY_DEVICES, null) ?: return emptyMap()
        return try {
            val arr = JSONArray(s)
            val map = mutableMapOf<String, ScannedDevice>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val addr = o.optString("address", "")
                if (addr.isEmpty()) continue
                map[addr] = ScannedDevice(
                    address = addr,
                    name = o.optString("name", "").takeIf { it.isNotEmpty() },
                    rssi = o.optInt("rssi", 0),
                    hasBthomeData = o.optBoolean("hasBthome", false),
                    bthomeData = null
                )
            }
            map
        } catch (e: Exception) {
            Log.w(TAG, "loadDevices", e)
            emptyMap()
        }
    }

    override fun onCleared() {
        super.onCleared()
        saveDevices(_uiState.value.devices.values)
        stopListening()
        listRefreshJob?.cancel()
        refreshTimerJob?.cancel()
    }
}