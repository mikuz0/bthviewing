package com.example.bthviewing.ui.chart

/** Диапазон значений по оси. */
data class ChartRange(val min: Float, val max: Float) {

    val span: Float get() = (max - min).takeIf { it > 0f } ?: 1f

    /** Нормализация: 0..1. */
    fun normalize(v: Float): Float = ((v - min) / span).coerceIn(0f, 1f)

    companion object {
        /** Отступ сверху/снизу 10 %, с защитой от нулевого диапазона. */
        fun of(values: List<Float>, padRatio: Float = 0.1f): ChartRange {
            if (values.isEmpty()) return ChartRange(0f, 1f)
            var lo = values[0]
            var hi = values[0]
            for (v in values) {
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            if (hi == lo) { hi += 1f; lo -= 1f }
            val pad = (hi - lo) * padRatio
            return ChartRange(lo - pad, hi + pad)
        }
    }
}