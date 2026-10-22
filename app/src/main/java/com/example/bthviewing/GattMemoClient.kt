package com.example.bthviewing

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class GattMemoClient(private val context: Context) {

    companion object {
        private const val TAG = "GATT_MEMO"

        val SERVICE_FCD2: UUID = UUID.fromString("0000fcd2-0000-1000-8000-00805f9b34fb")
        val CHAR_CMD: UUID = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val ALL_RECORDS = 0xFFFF

        private const val PRE_CONNECT_DELAY_MS = 2000L
        private const val READ_TIMEOUT_MS = 90_000L
    }

    private val connectionLock = Mutex()

    sealed class Event {
        data class Progress(val recordsRead: Int) : Event()
        data class Record(val record: MemoRecord) : Event()
        data object Done : Event()
        data class Error(val message: String) : Event()
    }

    @SuppressLint("MissingPermission")
    fun readHistory(
        device: BluetoothDevice,
        maxRecords: Int = ALL_RECORDS
    ): Flow<Event> = callbackFlow {
        connectionLock.withLock {
            Log.d(TAG, "start readHistory ${device.address}, cnt=$maxRecords")
        }

        var gatt: BluetoothGatt? = null
        var recordsRead = 0
        var connected = false
        var finished = false

        fun closeGatt() {
            val g = gatt ?: return
            gatt = null
            try { g.disconnect() } catch (_: Exception) {}
            try { g.close() } catch (_: Exception) {}
        }

        fun finish(error: String? = null) {
            if (finished) return
            finished = true
            closeGatt()
            if (error != null) trySend(Event.Error(error)) else trySend(Event.Done)
            close()
        }

        fun fail(message: String) {
            Log.w(TAG, "fail: $message")
            finish(error = message)
        }

        val callback = object : BluetoothGattCallback() {

            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                Log.d(TAG, "onConnectionStateChange status=$status newState=$newState")

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    if (status == 133) {
                        fail("Ошибка BLE 133. Убедитесь, что датчик не подключён к другому телефону/ПК, и повторите через 3-5 секунд.")
                    } else {
                        fail("GATT status=$status")
                    }
                    return
                }

                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        connected = true
                        if (!g.discoverServices()) {
                            fail("discoverServices() не запустился")
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (!finished) {
                            fail(
                                if (connected) "Устройство отключилось во время чтения (возможно, ушло в сон или другой централ подключился)"
                                else "Не удалось подключиться"
                            )
                        }
                    }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("discoverServices status=$status"); return
                }
                val service = g.getService(SERVICE_FCD2)
                if (service == null) {
                    fail("Сервис 0xFCD2 не найден. Это не BTHome-устройство?"); return
                }
                val ch = service.getCharacteristic(CHAR_CMD)
                if (ch == null) {
                    fail("Характеристика 0xFFF4 не найдена"); return
                }

                if (!g.setCharacteristicNotification(ch, true)) {
                    fail("setCharacteristicNotification() вернул false"); return
                }
                val desc = ch.getDescriptor(CCCD)
                if (desc == null) {
                    sendReadCommand(g, ch, maxRecords)
                    return
                }

                // Результат writeDescriptor в разных SDK — Boolean или Int.
                // Не сравниваем с GATT_SUCCESS; статус придёт в onDescriptorWrite().
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(desc)
                }
            }

            override fun onDescriptorWrite(
                g: BluetoothGatt,
                d: BluetoothGattDescriptor,
                status: Int
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("descriptor write status=$status"); return
                }
                val ch = g.getService(SERVICE_FCD2)?.getCharacteristic(CHAR_CMD)
                if (ch == null) {
                    fail("Характеристика 0xFFF4 исчезла"); return
                }
                sendReadCommand(g, ch, maxRecords)
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                ch: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                if (ch.uuid != CHAR_CMD) return
                recordsRead = handlePacket(value, recordsRead)
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                ch: BluetoothGattCharacteristic
            ) {
                if (ch.uuid != CHAR_CMD) return
                @Suppress("DEPRECATION")
                val value = ch.value ?: return
                recordsRead = handlePacket(value, recordsRead)
            }

            private fun handlePacket(value: ByteArray, prev: Int): Int {
                if (value.isEmpty()) return prev
                if ((value[0].toInt() and 0xFF) != 0x35) return prev

                return when {
                    value.size >= 13 -> {
                        val cnt = readUInt16LE(value, 1)
                        val timeSec = readUInt32LE(value, 3)
                        val temp = readInt16LE(value, 7) / 100.0f
                        val humi = readUInt16LE(value, 9) / 100.0f
                        val vbat = readUInt16LE(value, 11)

                        val next = prev + 1
                        trySend(Event.Record(MemoRecord(cnt, timeSec, temp, humi, vbat)))
                        trySend(Event.Progress(next))
                        next
                    }
                    value.size == 3 -> {
                        finish()
                        prev
                    }
                    else -> {
                        Log.d(TAG, "skip 0x35 len=${value.size}: " +
                                value.joinToString(" ") { "%02X".format(it) })
                        prev
                    }
                }
            }
        }

        delay(PRE_CONNECT_DELAY_MS)

        try {
            ensureActive()
        } catch (_: CancellationException) {
            finish()
            return@callbackFlow
        }

        Log.d(TAG, "connectGatt ${device.address}")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) {
            fail("connectGatt вернул null")
            return@callbackFlow
        }

        val watchdog = launch(start = CoroutineStart.UNDISPATCHED) {
            delay(READ_TIMEOUT_MS)
            if (!finished) fail("Таймаут чтения истории")
        }

        awaitClose {
            watchdog.cancel()
            closeGatt()
        }
    }
        .buffer(Channel.UNLIMITED)

    @SuppressLint("MissingPermission")
    private fun sendReadCommand(
        g: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        maxRecords: Int
    ) {
        val cnt = maxRecords.coerceIn(0, 0xFFFF)
        val cmd = byteArrayOf(
            0x35,
            (cnt and 0xFF).toByte(),
            ((cnt shr 8) and 0xFF).toByte(),
            0x00,
            0x00
        )
        Log.d(TAG, "write 0x35 cnt=$cnt")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, cmd, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            ch.value = cmd
            @Suppress("DEPRECATION")
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }
    }

    private fun readInt16LE(d: ByteArray, o: Int): Int =
        (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)

    private fun readUInt16LE(d: ByteArray, o: Int): Int = readInt16LE(d, o)

    private fun readUInt32LE(d: ByteArray, o: Int): Long =
        (d[o].toLong() and 0xFF) or
                ((d[o + 1].toLong() and 0xFF) shl 8) or
                ((d[o + 2].toLong() and 0xFF) shl 16) or
                ((d[o + 3].toLong() and 0xFF) shl 24)
}