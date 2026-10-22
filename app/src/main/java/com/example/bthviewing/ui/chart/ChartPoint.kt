package com.example.bthviewing.ui.chart

/** Одна точка на графике. */
data class ChartPoint(
    val x: Long,          // время в секундах (Unix)
    val temp: Float,      // °C
    val humi: Float       // %
)