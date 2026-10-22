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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

class GattMemoClient(private val context: Context) {

    companion object {
        private const val TAG = "GATT_MEMO"

        val SERVICE_FCD2: UUID = UUID.fromString("0000fcd2-0000-1000-8000-00805f9b34fb")
        val CHAR_CMD: UUID = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val ALL_RECORDS = 0xFFFF

        /** Пауза перед connectGatt. */
        private const val PRE_CONNECT_DELAY_MS = 500L

        /** Таймаут проверки отклика (0x00 DEVID). */
        private const val PROBE_TIMEOUT_MS = 5_000L

        /** Таймаут чтения истории. 10 минут. */
        private const val READ_TIMEOUT_MS = 600_000L
    }

    @Volatile
    private var activeCancel: (() -> Unit)? = null

    @Volatile
    private var probeDeferred: CompletableDeferred<Unit>? = null

    sealed class Event {
        data object Probe : Event()
        data class Progress(val recordsRead: Int) : Event()
        data class Record(val record: MemoRecord) : Event()
        data object Done : Event()
        data object Cancelled : Event()
        data class Error(val message: String) : Event()
    }

    fun cancel() {
        try {
            activeCancel?.invoke()
        } catch (e: Exception) {
            Log.w(TAG, "cancel()", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun readHistory(
        device: BluetoothDevice,
        maxRecords: Int = ALL_RECORDS
    ): Flow<Event> = callbackFlow {
        BleMutex.mutex.lock()
        Log.d(TAG, "start readHistory ${device.address}, cnt=$maxRecords")

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

        fun releaseMutex() {
            try {
                BleMutex.mutex.unlock()
            } catch (_: Exception) {
                // уже разблокирован
            }
        }

        fun finish(error: String? = null, cancelled: Boolean = false) {
            if (finished) return
            finished = true
            activeCancel = null
            val probe = probeDeferred
            probeDeferred = null
            closeGatt()
            releaseMutex()
            when {
                error != null -> trySend(Event.Error(error))
                cancelled -> trySend(Event.Cancelled)
                else -> trySend(Event.Done)
            }
            if (probe != null && !probe.isCompleted) {
                probe.completeExceptionally(CancellationException("closed"))
            }
            close()
        }

        fun fail(message: String) {
            Log.w(TAG, "fail: $message")
            finish(error = message)
        }

        // Регистрируем cancel() с самого начала — до connectGatt,
        // чтобы пользователь мог отменить на фазе «Подключение…»
        activeCancel = { finish(cancelled = true) }

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
                        try {
                            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        } catch (e: Exception) {
                            Log.w(TAG, "requestConnectionPriority", e)
                        }
                        if (!g.discoverServices()) {
                            fail("discoverServices() не запустился")
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (!finished) {
                            fail(
                                if (connected) "Устройство отключилось во время чтения"
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
                    fail("Сервис 0xFCD2 не найден"); return
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
                    // без CCCD — пробуем сразу probe
                    doProbeAndRead(g, ch, maxRecords)
                    return
                }
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
                doProbeAndRead(g, ch, maxRecords)
            }

            /**
             * Проверка отклика: шлём 0x00 (DEVID), ждём notify [0x00, ...] с таймаутом 5 с.
             * Если ответ пришёл — шлём 0x35 (чтение истории). Если нет — fail.
             */
            private fun doProbeAndRead(
                g: BluetoothGatt,
                ch: BluetoothGattCharacteristic,
                maxRecords: Int
            ) {
                val probe = CompletableDeferred<Unit>()
                probeDeferred = probe
                trySend(Event.Probe)

                val sent = writeBytes(g, ch, byteArrayOf(0x00))
                if (!sent) {
                    probeDeferred = null
                    fail("Не удалось отправить 0x00 (DEVID)")
                    return
                }

                // Ждём probe.await() в отдельной корутине (callbackFlow scope)
                launch {
                    val ok = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                        try {
                            probe.await()
                            true
                        } catch (_: Exception) {
                            false
                        }
                    } ?: false
                    probeDeferred = null
                    if (finished) return@launch
                    if (!ok) {
                        fail("Датчик не отвечает")
                        return@launch
                    }
                    // Ответ пришёл — читаем историю
                    Log.d(TAG, "probe ok, write 0x35")
                    if (!writeBytes(g, ch, readCommandBytes(maxRecords))) {
                        fail("Не удалось отправить 0x35 (LOGGER)")
                    }
                }
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                ch: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                if (ch.uuid != CHAR_CMD) return
                handle(value)
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                ch: BluetoothGattCharacteristic
            ) {
                if (ch.uuid != CHAR_CMD) return
                @Suppress("DEPRECATION")
                val value = ch.value ?: return
                handle(value)
            }

            private fun handle(value: ByteArray) {
                if (value.isEmpty()) return
                val id = value[0].toInt() and 0xFF
                Log.d(TAG, "notify blkId=0x${"%02X".format(id)}: ${value.toHex()}")

                // Ответ на probe (DEVID, 0x00)
                if (id == 0x00) {
                    val probe = probeDeferred
                    if (probe != null && !probe.isCompleted) {
                        probe.complete(Unit)
                    }
                    return
                }

                // История (0x35)
                if (id != 0x35) return
                recordsRead = handleHistory(value, recordsRead)
            }

            private fun handleHistory(value: ByteArray, prev: Int): Int {
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
                        finish()      // устройство закончило историю
                        prev
                    }
                    else -> {
                        Log.d(TAG, "skip 0x35 len=${value.size}: ${value.toHex()}")
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

        if (finished) return@callbackFlow

        Log.d(TAG, "connectGatt ${device.address} (autoConnect=false)")
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
            releaseMutex()
            if (!finished) {
                finished = true
                activeCancel = null
                probeDeferred = null
            }
            Log.d(TAG, "readHistory closed")
        }
    }
        .buffer(Channel.UNLIMITED)

    @SuppressLint("MissingPermission")
    private fun writeBytes(
        g: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        bytes: ByteArray
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            ch.value = bytes
            @Suppress("DEPRECATION")
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }
    }

    private fun readCommandBytes(maxRecords: Int): ByteArray {
        val cnt = maxRecords.coerceIn(0, 0xFFFF)
        return byteArrayOf(
            0x35,
            (cnt and 0xFF).toByte(),
            ((cnt shr 8) and 0xFF).toByte(),
            0x00,
            0x00
        )
    }

    private fun readInt16LE(d: ByteArray, o: Int): Int =
        (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)

    private fun readUInt16LE(d: ByteArray, o: Int): Int = readInt16LE(d, o)

    private fun readUInt32LE(d: ByteArray, o: Int): Long =
        (d[o].toLong() and 0xFF) or
                ((d[o + 1].toLong() and 0xFF) shl 8) or
                ((d[o + 2].toLong() and 0xFF) shl 16) or
                ((d[o + 3].toLong() and 0xFF) shl 24)

    private fun ByteArray.toHex(): String =
        joinToString(" ") { "%02X".format(it) }
}