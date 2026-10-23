package com.example.bthviewing

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Коэффициенты сенсора (CMD_ID_CFS, 0x25/0x26) + состояние времени (0x23). */
data class SensorState(
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val phase: String? = null,
    val tempK: Long = 0,
    val humiK: Long = 0,
    val tempZ: Int = 0,
    val humiZ: Int = 0,
    val mid: Int = 0,
    val vid: Int = 0,
    val i2cAddr: Int = 0,

    val timeReading: Boolean = false,
    val timeSetting: Boolean = false,
    val timeCur: Long = 0,
    val timeCurSet: Long = 0,
    val timeReadOk: Boolean = false,
    val timeReadError: String? = null,
    val timeSetOk: Boolean = false,
    val timeSetError: String? = null
)

@SuppressLint("MissingPermission")
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "SettingsVM"
    }

    private val client = GattConfigClient(application)

    private var lastDeviceAddress: String? = null

    private val _sensor = MutableStateFlow(SensorState())
    val sensor: StateFlow<SensorState> = _sensor.asStateFlow()

    private fun phaseCallback() = GattConfigClient.PhaseCallback { phase ->
        _sensor.value = _sensor.value.copy(phase = phase)
    }

    fun setTarget(address: String?) {
        if (lastDeviceAddress != address) {
            lastDeviceAddress = address
            _sensor.value = SensorState()
        }
    }

    private fun deviceOrError(): BluetoothDeviceOrError {
        val addr = lastDeviceAddress ?: return BluetoothDeviceOrError.Error("Устройство не выбрано")
        val mgr = getApplication<Application>()
            .getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val dev = mgr?.adapter?.getRemoteDevice(addr)
            ?: return BluetoothDeviceOrError.Error("Устройство не найдено")
        return BluetoothDeviceOrError.Ok(dev)
    }

    // ---- CMD_ID_CFS (0x25/0x26) --------------------------------------------

    fun readSensor() {
        val d = deviceOrError(); if (d is BluetoothDeviceOrError.Error) {
            _sensor.value = _sensor.value.copy(error = d.message); return
        }
        _sensor.value = _sensor.value.copy(
            loading = true,
            error = null,
            phase = null,
            timeReadOk = false, timeReadError = null,
            timeSetOk = false, timeSetError = null
        )
        viewModelScope.launch {
            try {
                val reply = client.request(
                    (d as BluetoothDeviceOrError.Ok).device,
                    byteArrayOf(0x25),
                    onPhase = phaseCallback()
                )
                _sensor.value = parseSensor(reply)
            } catch (e: Exception) {
                Log.w(TAG, "readSensor", e)
                _sensor.value = _sensor.value.copy(
                    loading = false, phase = null,
                    error = e.message ?: "Ошибка чтения"
                )
            }
        }
    }

    fun writeSensor() {
        val d = deviceOrError(); if (d is BluetoothDeviceOrError.Error) {
            _sensor.value = _sensor.value.copy(error = d.message); return
        }
        _sensor.value = _sensor.value.copy(
            saving = true,
            error = null,
            phase = null,
            timeReadOk = false, timeReadError = null,
            timeSetOk = false, timeSetError = null
        )
        viewModelScope.launch {
            try {
                val s = _sensor.value
                val cmd = byteArrayOf(
                    0x25,
                    (s.tempK and 0xFF).toByte(),
                    ((s.tempK shr 8) and 0xFF).toByte(),
                    ((s.tempK shr 16) and 0xFF).toByte(),
                    ((s.tempK shr 24) and 0xFF).toByte(),
                    (s.humiK and 0xFF).toByte(),
                    ((s.humiK shr 8) and 0xFF).toByte(),
                    ((s.humiK shr 16) and 0xFF).toByte(),
                    ((s.humiK shr 24) and 0xFF).toByte(),
                    (s.tempZ and 0xFF).toByte(),
                    ((s.tempZ shr 8) and 0xFF).toByte(),
                    (s.humiZ and 0xFF).toByte(),
                    ((s.humiZ shr 8) and 0xFF).toByte()
                )
                val reply = client.request(
                    (d as BluetoothDeviceOrError.Ok).device, cmd,
                    onPhase = phaseCallback()
                )
                _sensor.value = parseSensor(reply)
            } catch (e: Exception) {
                Log.w(TAG, "writeSensor", e)
                _sensor.value = _sensor.value.copy(
                    saving = false, phase = null,
                    error = e.message ?: "Ошибка записи"
                )
            }
        }
    }

    fun resetSensor() {
        val d = deviceOrError(); if (d is BluetoothDeviceOrError.Error) return
        _sensor.value = _sensor.value.copy(
            saving = true,
            error = null,
            phase = null,
            timeReadOk = false, timeReadError = null,
            timeSetOk = false, timeSetError = null
        )
        viewModelScope.launch {
            try {
                val reply = client.request(
                    (d as BluetoothDeviceOrError.Ok).device,
                    byteArrayOf(0x26),
                    onPhase = phaseCallback()
                )
                _sensor.value = parseSensor(reply)
            } catch (e: Exception) {
                _sensor.value = _sensor.value.copy(
                    saving = false, phase = null,
                    error = e.message ?: "Ошибка"
                )
            }
        }
    }

    fun updateSensor(
        tempK: Long? = null,
        humiK: Long? = null,
        tempZ: Int? = null,
        humiZ: Int? = null
    ) {
        val s = _sensor.value
        _sensor.value = s.copy(
            tempK = tempK ?: s.tempK,
            humiK = humiK ?: s.humiK,
            tempZ = tempZ ?: s.tempZ,
            humiZ = humiZ ?: s.humiZ
        )
    }

    // ---- CMD_ID_UTC_TIME (0x23) ---------------------------------------------

    fun readDeviceTime() {
        val d = deviceOrError(); if (d is BluetoothDeviceOrError.Error) {
            _sensor.value = _sensor.value.copy(timeReadError = d.message, timeReadOk = false)
            return
        }
        _sensor.value = _sensor.value.copy(
            timeReading = true,
            timeReadOk = false,
            timeReadError = null,
            timeSetOk = false,
            timeSetError = null,
            phase = null
        )
        viewModelScope.launch {
            try {
                val reply = client.request(
                    (d as BluetoothDeviceOrError.Ok).device,
                    byteArrayOf(0x23),
                    onPhase = phaseCallback()
                )
                if (reply.size >= 5 && (reply[0].toInt() and 0xFF) == 0x23) {
                    val cur = readU32(reply, 1)
                    val set = if (reply.size >= 9) readU32(reply, 5) else 0L
                    _sensor.value = _sensor.value.copy(
                        timeReading = false,
                        phase = "Готово",
                        timeReadOk = true,
                        timeReadError = null,
                        timeCur = cur,
                        timeCurSet = set
                    )
                    Log.d(TAG, "readDeviceTime ok: cur=$cur, set=$set")
                } else {
                    _sensor.value = _sensor.value.copy(
                        timeReading = false,
                        phase = null,
                        timeReadOk = false,
                        timeReadError = "Неверный ответ устройства"
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "readDeviceTime", e)
                _sensor.value = _sensor.value.copy(
                    timeReading = false,
                    phase = null,
                    timeReadOk = false,
                    timeReadError = e.message ?: "Ошибка чтения времени"
                )
            }
        }
    }

    fun setDeviceTime() {
        val d = deviceOrError(); if (d is BluetoothDeviceOrError.Error) {
            _sensor.value = _sensor.value.copy(timeSetError = d.message, timeSetOk = false)
            return
        }

        val localSec = (System.currentTimeMillis() / 1000L) -
                (java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000L)

        _sensor.value = _sensor.value.copy(
            timeSetting = true,
            timeSetOk = false,
            timeSetError = null,
            timeReadOk = false,
            timeReadError = null,
            phase = null
        )

        viewModelScope.launch {
            try {
                val cmd = byteArrayOf(
                    0x23,
                    (localSec and 0xFF).toByte(),
                    ((localSec shr 8) and 0xFF).toByte(),
                    ((localSec shr 16) and 0xFF).toByte(),
                    ((localSec shr 24) and 0xFF).toByte()
                )
                val reply = client.request(
                    (d as BluetoothDeviceOrError.Ok).device, cmd,
                    onPhase = phaseCallback()
                )
                if (reply.size >= 5 && (reply[0].toInt() and 0xFF) == 0x23) {
                    val cur = readU32(reply, 1)
                    val set = if (reply.size >= 9) readU32(reply, 5) else 0L
                    _sensor.value = _sensor.value.copy(
                        timeSetting = false,
                        phase = "Готово",
                        timeSetOk = true,
                        timeSetError = null,
                        timeCur = cur,
                        timeCurSet = set,
                        timeReadOk = true,
                        timeReadError = null
                    )
                    Log.d(TAG, "setDeviceTime ok: cur=$cur, set=$set, local=$localSec")
                } else {
                    _sensor.value = _sensor.value.copy(
                        timeSetting = false,
                        phase = null,
                        timeSetOk = false,
                        timeSetError = "Неверный ответ устройства"
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "setDeviceTime", e)
                _sensor.value = _sensor.value.copy(
                    timeSetting = false,
                    phase = null,
                    timeSetOk = false,
                    timeSetError = e.message ?: "Ошибка установки времени"
                )
            }
        }
    }

    // ---- parsing -----------------------------------------------------------

    private fun parseSensor(reply: ByteArray): SensorState {
        if (reply.size < 13) {
            return _sensor.value.copy(loading = false, saving = false, phase = null, error = "Короткий ответ")
        }
        val tempK = readU32(reply, 1)
        val humiK = readU32(reply, 5)
        val tempZ = readS16(reply, 9)
        val humiZ = readS16(reply, 11)
        var mid = 0; var vid = 0; var addr = 0
        if (reply.size >= 18) {
            mid = ((reply[13].toInt() and 0xFF) shl 8) or (reply[14].toInt() and 0xFF)
            vid = ((reply[15].toInt() and 0xFF) shl 8) or (reply[16].toInt() and 0xFF)
            addr = reply[17].toInt() and 0xFF
        }
        return SensorState(
            loaded = true, loading = false, saving = false, phase = "Готово", error = null,
            tempK = tempK, humiK = humiK, tempZ = tempZ, humiZ = humiZ,
            mid = mid, vid = vid, i2cAddr = addr
        )
    }

    private fun readU32(d: ByteArray, o: Int): Long =
        (d[o].toLong() and 0xFF) or
                ((d[o + 1].toLong() and 0xFF) shl 8) or
                ((d[o + 2].toLong() and 0xFF) shl 16) or
                ((d[o + 3].toLong() and 0xFF) shl 24)

    private fun readS16(d: ByteArray, o: Int): Int =
        ((d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)).toShort().toInt()
}

sealed class BluetoothDeviceOrError {
    data class Ok(val device: BluetoothDevice) : BluetoothDeviceOrError()
    data class Error(val message: String) : BluetoothDeviceOrError()
}