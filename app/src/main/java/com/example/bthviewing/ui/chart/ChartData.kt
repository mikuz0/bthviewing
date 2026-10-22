package com.example.bthviewing.ui.chart

import com.example.bthviewing.MemoRecord

/**
 * Готовит данные для графика:
 *  - точки отсортированы по времени;
 *  - отдельные диапазоны для T и H (шкалы независимы);
 *  - диапазон по X (время).
 */
data class ChartData(
    val points: List<ChartPoint>,
    val tempRange: ChartRange,
    val humiRange: ChartRange,
    val timeMin: Long,
    val timeMax: Long
) {
    val timeSpan: Long get() = (timeMax - timeMin).takeIf { it > 0 } ?: 1L

    fun xFraction(t: Long): Float =
        ((t - timeMin).toFloat() / timeSpan.toFloat()).coerceIn(0f, 1f)

    companion object {
        fun from(records: List<MemoRecord>): ChartData {
            val pts = records
                .asSequence()
                .filter { it.timeSec > 0 }
                .sortedBy { it.timeSec }
                .map { ChartPoint(it.timeSec, it.temperature, it.humidity) }
                .toList()

            val tempRange = ChartRange.of(pts.map { it.temp })
            val humiRange = ChartRange.of(pts.map { it.humi })
            val tMin = pts.firstOrNull()?.x ?: 0L
            val tMax = pts.lastOrNull()?.x ?: 1L
            return ChartData(
                points = pts,
                tempRange = tempRange,
                humiRange = humiRange,
                timeMin = tMin,
                timeMax = if (tMax > tMin) tMax else tMin + 1
            )
        }
    }
}