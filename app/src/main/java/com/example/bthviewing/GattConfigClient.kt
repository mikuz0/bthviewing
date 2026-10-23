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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

class GattConfigClient(private val context: Context) {

    companion object {
        private const val TAG = "GATT_CFG"

        val SERVICE_FCD2: UUID = UUID.fromString("0000fcd2-0000-1000-8000-00805f9b34fb")
        val CHAR_CMD: UUID = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val PRE_CONNECT_DELAY_MS = 500L
        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val PROBE_TIMEOUT_MS = 5_000L
    }

    /** Колбэк для уведомления UI о текущей фазе. */
    fun interface PhaseCallback {
        fun onPhase(phase: String)
    }

    /**
     * Отправить команду и вернуть ответ (весь пакет, начиная с blkId).
     * Одна попытка, таймаут [DEFAULT_TIMEOUT_MS]. Перед командой — probe 0x00 (DEVID)
     * с таймаутом 5 с, как в GattMemoClient.
     * Бросает [IllegalStateException] при таймауте/ошибке GATT.
     *
     * @param onPhase вызывается для каждой фазы: «Подключение…», «Проверка отклика…»,
     *                «Чтение…»/«Запись…»/«Сброс…», «Готово».
     */
    @SuppressLint("MissingPermission")
    suspend fun request(
        device: BluetoothDevice,
        cmd: ByteArray,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        onPhase: PhaseCallback? = null
    ): ByteArray {
        if (cmd.isEmpty()) throw IllegalArgumentException("Пустая команда")
        val blkId = cmd[0].toInt() and 0xFF
        Log.d(TAG, "request blkId=0x${"%02X".format(blkId)} cmd=${cmd.toHex()}")

        val commandPhase = when (blkId) {
            0x23 -> "Чтение…"
            0x25, 0x26 -> "Запись…"
            0x55, 0x56 -> "Запись…"
            else -> "Команда…"
        }

        return singleAttempt(device, cmd, blkId, timeoutMs, commandPhase, onPhase)
    }

    @SuppressLint("MissingPermission")
    private suspend fun singleAttempt(
        device: BluetoothDevice,
        cmd: ByteArray,
        blkId: Int,
        timeoutMs: Long,
        commandPhase: String,
        onPhase: PhaseCallback?
    ): ByteArray {
        onPhase?.onPhase("Подключение…")
        delay(PRE_CONNECT_DELAY_MS)

        val replyDeferred = CompletableDeferred<ByteArray>()
        val probeDeferred = CompletableDeferred<Unit>()
        val flow = connectAndSubscribe(device, cmd, blkId, replyDeferred, probeDeferred, commandPhase, onPhase)

        val reply: ByteArray
        try {
            reply = withTimeout(timeoutMs) {
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { flow.collect { /* ignore */ } }
                    catch (_: CancellationException) { /* normal */ }
                    catch (e: Exception) {
                        if (!replyDeferred.isCompleted) replyDeferred.completeExceptionally(e)
                        if (!probeDeferred.isCompleted) probeDeferred.completeExceptionally(e)
                    }
                }
                try {
                    replyDeferred.await()
                } finally {
                    collector.cancel()
                }
            }
        } catch (te: kotlinx.coroutines.TimeoutCancellationException) {
            throw IllegalStateException("Устройство не ответило за $timeoutMs мс", te)
        }
        onPhase?.onPhase("Готово")
        return reply
    }

    @SuppressLint("MissingPermission")
    private fun connectAndSubscribe(
        device: BluetoothDevice,
        cmd: ByteArray,
        expectedBlkId: Int,
        replyDeferred: CompletableDeferred<ByteArray>,
        probeDeferred: CompletableDeferred<Unit>,
        commandPhase: String,
        onPhase: PhaseCallback?
    ): Flow<Unit> = callbackFlow {
        BleMutex.mutex.lock()
        Log.d(TAG, "start request ${device.address}, blkId=0x${"%02X".format(expectedBlkId)}")

        var gatt: BluetoothGatt? = null
        var finished = false
        var cmdSent = false

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

        fun finish(error: Throwable? = null) {
            if (finished) return
            finished = true
            closeGatt()
            releaseMutex()
            if (!replyDeferred.isCompleted) {
                replyDeferred.completeExceptionally(
                    error ?: CancellationException("closed")
                )
            }
            if (!probeDeferred.isCompleted) {
                probeDeferred.completeExceptionally(
                    error ?: CancellationException("closed")
                )
            }
            close()
        }

        fun sendProbe(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            Log.d(TAG, "write probe 0x00 (DEVID)")
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, byteArrayOf(0x00), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                        android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                ch.value = byteArrayOf(0x00)
                @Suppress("DEPRECATION")
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                g.writeCharacteristic(ch)
            }
            if (!ok) {
                finish(IllegalStateException("write probe failed"))
            }
        }

        fun sendCmd(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (cmdSent) return
            cmdSent = true
            Log.d(TAG, "writeCharacteristic len=${cmd.size}: ${cmd.toHex()}")
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, cmd, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                        android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                ch.value = cmd
                @Suppress("DEPRECATION")
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                g.writeCharacteristic(ch)
            }
            if (!ok) {
                Log.w(TAG, "writeCharacteristic вернул ошибку")
                finish(IllegalStateException("writeCharacteristic failed"))
            }
        }

        val callback = object : BluetoothGattCallback() {

            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                Log.d(TAG, "onConnectionStateChange status=$status newState=$newState")
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finish(IllegalStateException(
                        if (status == 133) "BLE 133: датчик занят другим устройством, мешает Wi-Fi (2.4 ГГц) или другие беспроводные интерфейсы. Убедитесь, что выбранный датчик активен, отключите Wi-Fi и повторите."
                        else "GATT status=$status"
                    ))
                    return
                }
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        try {
                            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        } catch (e: Exception) {
                            Log.w(TAG, "requestConnectionPriority", e)
                        }
                        if (!g.discoverServices()) {
                            finish(IllegalStateException("discoverServices() вернул false"))
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> if (!finished)
                        finish(IllegalStateException("Устройство отключилось"))
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finish(IllegalStateException("discoverServices status=$status")); return
                }
                val ch = g.getService(SERVICE_FCD2)?.getCharacteristic(CHAR_CMD)
                if (ch == null) {
                    finish(IllegalStateException("0xFFF4 не найдена")); return
                }
                if (!g.setCharacteristicNotification(ch, true)) {
                    finish(IllegalStateException("setCharacteristicNotification=false")); return
                }
                val desc = ch.getDescriptor(CCCD)
                if (desc == null) {
                    doProbeAndCommand(g, ch)
                    return
                }
                val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                            android.bluetooth.BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(desc)
                }
                if (!ok) Log.w(TAG, "writeDescriptor вернул ошибку, ждём колбэк")
            }

            override fun onDescriptorWrite(
                g: BluetoothGatt,
                d: BluetoothGattDescriptor,
                status: Int
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finish(IllegalStateException("descriptor write status=$status")); return
                }
                val ch = g.getService(SERVICE_FCD2)?.getCharacteristic(CHAR_CMD)
                if (ch == null) { finish(IllegalStateException("0xFFF4 пропала")); return }
                doProbeAndCommand(g, ch)
            }

            /**
             * Шлём probe 0x00 (DEVID), ждём notify [0x00, ...] с таймаутом 5 с.
             * Если ответ пришёл — шлём настоящую команду. Если нет — fail.
             */
            private fun doProbeAndCommand(
                g: BluetoothGatt,
                ch: BluetoothGattCharacteristic
            ) {
                onPhase?.onPhase("Проверка отклика…")
                sendProbe(g, ch)
                launch {
                    val ok = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                        try {
                            probeDeferred.await()
                            true
                        } catch (_: Exception) {
                            false
                        }
                    } ?: false
                    if (finished) return@launch
                    if (!ok) {
                        Log.w(TAG, "probe timeout ($PROBE_TIMEOUT_MS ms)")
                        finish(IllegalStateException("Датчик не отвечает"))
                        return@launch
                    }
                    Log.d(TAG, "probe ok, send command")
                    onPhase?.onPhase(commandPhase)
                    sendCmd(g, ch)
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
                    if (!probeDeferred.isCompleted) probeDeferred.complete(Unit)
                    return
                }

                // Ответ на настоящую команду
                if (id == expectedBlkId) {
                    if (!replyDeferred.isCompleted) {
                        replyDeferred.complete(value)
                        trySend(Unit)
                    }
                    finish()
                } else {
                    Log.d(TAG, "skip blkId=0x${"%02X".format(id)}")
                }
            }
        }

        try { ensureActive() } catch (_: CancellationException) { finish(); return@callbackFlow }

        Log.d(TAG, "connectGatt ${device.address} (autoConnect=false)")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) {
            finish(IllegalStateException("connectGatt=null"))
            return@callbackFlow
        }

        awaitClose {
            closeGatt()
            releaseMutex()
            if (!finished) {
                finished = true
            }
            if (!replyDeferred.isCompleted) {
                replyDeferred.completeExceptionally(CancellationException("closed"))
            }
            if (!probeDeferred.isCompleted) {
                probeDeferred.completeExceptionally(CancellationException("closed"))
            }
            Log.d(TAG, "request closed")
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString(" ") { "%02X".format(it) }
}