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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

class GattConfigClient(private val context: Context) {

    companion object {
        private const val TAG = "GATT_CFG"

        val SERVICE_FCD2: UUID = UUID.fromString("0000fcd2-0000-1000-8000-00805f9b34fb")
        val CHAR_CMD: UUID = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val PRE_CONNECT_DELAY_MS = 1500L
        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 2000L
    }

    private val lock = Mutex()

    @SuppressLint("MissingPermission")
    suspend fun request(
        device: BluetoothDevice,
        cmd: ByteArray,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): ByteArray = lock.withLock {
        if (cmd.isEmpty()) throw IllegalArgumentException("Пустая команда")
        val blkId = cmd[0].toInt() and 0xFF
        Log.d(TAG, "request blkId=0x${"%02X".format(blkId)} cmd=${cmd.toHex()}")

        var lastError: Exception? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                return singleAttempt(device, cmd, blkId, timeoutMs)
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "attempt $attempt/$MAX_ATTEMPTS failed: ${e.message}")
                if (attempt < MAX_ATTEMPTS) delay(RETRY_DELAY_MS)
            }
        }
        throw IllegalStateException(
            lastError?.message ?: "Не удалось получить ответ от устройства",
            lastError
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun singleAttempt(
        device: BluetoothDevice,
        cmd: ByteArray,
        blkId: Int,
        timeoutMs: Long
    ): ByteArray {
        delay(PRE_CONNECT_DELAY_MS)

        val replyDeferred = CompletableDeferred<ByteArray>()
        val flow = connectAndSubscribe(device, cmd, blkId, replyDeferred)

        val reply: ByteArray
        try {
            reply = withTimeout(timeoutMs) {
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { flow.collect { /* ignore */ } }
                    catch (_: CancellationException) { /* normal */ }
                    catch (e: Exception) {
                        if (!replyDeferred.isCompleted) replyDeferred.completeExceptionally(e)
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
        return reply
    }

    @SuppressLint("MissingPermission")
    private fun connectAndSubscribe(
        device: BluetoothDevice,
        cmd: ByteArray,
        expectedBlkId: Int,
        replyDeferred: CompletableDeferred<ByteArray>
    ): Flow<Unit> = callbackFlow {
        var gatt: BluetoothGatt? = null
        var finished = false
        var cmdSent = false

        fun closeGatt() {
            val g = gatt ?: return
            gatt = null
            try { g.disconnect() } catch (_: Exception) {}
            try { g.close() } catch (_: Exception) {}
        }

        fun finish(error: Throwable? = null) {
            if (finished) return
            finished = true
            closeGatt()
            if (error != null && !replyDeferred.isCompleted) {
                replyDeferred.completeExceptionally(error)
            }
            close()
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
                        if (status == 133) "BLE 133: датчик занят другим устройством"
                        else "GATT status=$status"
                    ))
                    return
                }
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
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
                    sendCmd(g, ch)
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
                sendCmd(g, ch)
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

        Log.d(TAG, "connectGatt ${device.address}")
        gatt = device.connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) {
            finish(IllegalStateException("connectGatt=null"))
            return@callbackFlow
        }

        awaitClose {
            closeGatt()
            if (!replyDeferred.isCompleted) {
                replyDeferred.completeExceptionally(CancellationException("closed"))
            }
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString(" ") { "%02X".format(it) }
}