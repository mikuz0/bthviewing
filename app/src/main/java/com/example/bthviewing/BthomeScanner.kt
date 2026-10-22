package com.example.bthviewing

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class BthomeScanner(private val context: Context) {

    companion object {
        val BTHOME_SERVICE_UUID: ParcelUuid =
            ParcelUuid.fromString("0000fcd2-0000-1000-8000-00805f9b34fb")
        private const val TAG = "BTH_SCAN"
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    fun isBluetoothReady(): Boolean = bluetoothAdapter?.isEnabled == true

    fun hasPermissions(): Boolean {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return required.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Скан без фильтра — «Обновить список устройств».
     * Все видимые BLE-пакеты доходят до onScanResult.
     *
     * @param scanMode режим сканирования (по умолчанию LOW_LATENCY — быстро собрать список)
     */
    @SuppressLint("MissingPermission")
    fun scanAll(
        scanMode: Int = ScanSettings.SCAN_MODE_LOW_LATENCY
    ): Flow<ScannedDevice> = scanInternal(address = null, scanMode = scanMode)

    /**
     * Скан с фильтром по MAC — «Прослушивание выбранного устройства».
     * Android фильтрует пакеты на уровне контроллера, до приложения доходят
     * только пакеты от указанного MAC. Без таймаута — работает, пока не отменят.
     *
     * @param address MAC-адрес выбранного датчика
     * @param scanMode по умолчанию LOW_POWER — лучше выживает в фоне
     */
    @SuppressLint("MissingPermission")
    fun scanFor(
        address: String,
        scanMode: Int = ScanSettings.SCAN_MODE_LOW_POWER
    ): Flow<ScannedDevice> = scanInternal(address = address, scanMode = scanMode)

    /**
     * Общая реализация: если address != null — фильтр по MAC, иначе без фильтра.
     */
    @SuppressLint("MissingPermission")
    private fun scanInternal(
        address: String?,
        scanMode: Int
    ): Flow<ScannedDevice> = callbackFlow {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("BLE scanner недоступен"))
            return@callbackFlow
        }

        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .build()

        val filters: List<ScanFilter>? = address?.let {
            listOf(ScanFilter.Builder().setDeviceAddress(it).build())
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                // Двойная проверка на случай, если OEM-стек проигнорировал ScanFilter
                if (address != null && result.device.address != address) return

                val record = result.scanRecord
                val serviceData = record?.getServiceData(BTHOME_SERVICE_UUID)
                val decoded = serviceData?.let {
                    BthomeDecoder.decode(
                        serviceData = it,
                        deviceAddress = result.device.address,
                        deviceName = result.device.name ?: record.deviceName,
                        rssi = result.rssi
                    )
                }

                Log.d(
                    TAG,
                    "dev=${result.device.address} name=${record?.deviceName ?: result.device.name} " +
                            "rssi=${result.rssi} serviceData=${serviceData?.joinToString(" ") { "%02X".format(it) }}"
                )

                trySend(
                    ScannedDevice(
                        address = result.device.address,
                        name = result.device.name ?: record?.deviceName,
                        rssi = result.rssi,
                        hasBthomeData = serviceData != null,
                        bthomeData = decoded
                    )
                )
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "onScanFailed: $errorCode")
                close(IllegalStateException("Scan failed: $errorCode"))
            }
        }

        Log.d(TAG, "startScan addr=$address mode=$scanMode")
        scanner.startScan(filters, settings, callback)

        awaitClose {
            runCatching { scanner.stopScan(callback) }
            Log.d(TAG, "stopScan addr=$address")
        }
    }
}