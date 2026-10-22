package com.example.bthviewing

data class BthomeData(
    val deviceAddress: String,
    val deviceName: String?,
    val temperature: Float? = null,
    val humidity: Float? = null,
    val battery: Int? = null,
    val voltage: Float? = null,
    val rssi: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)

data class ScannedDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val hasBthomeData: Boolean,
    val bthomeData: BthomeData? = null
)