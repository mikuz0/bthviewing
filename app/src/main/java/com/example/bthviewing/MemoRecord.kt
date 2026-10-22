package com.example.bthviewing


data class MemoRecord(
    val index: Int,
    val timeSec: Long,
    val temperature: Float,
    val humidity: Float,
    val batteryMv: Int
)