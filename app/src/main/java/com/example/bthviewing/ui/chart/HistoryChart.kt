package com.example.bthviewing.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val TempColor = Color(0xFF2E7D32)   // green 800
private val HumiColor = Color(0xFF1565C0)   // blue 800
private val GridColor = Color(0xFF9E9E9E).copy(alpha = 0.5f)
private val AxisColor = Color(0xFF616161)
private val CrosshairColor = Color(0xFF000000).copy(alpha = 0.6f)

@Composable
fun HistoryChart(
    records: List<com.example.bthviewing.MemoRecord>,
    periodLabel: String = "day",      // "6h" | "day" | "week" | "month" | "year"
    modifier: Modifier = Modifier
) {
    val data = remember(records) { ChartData.from(records) }
    if (data.points.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Нет данных для графика", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    var selectedIdx by remember(data) { mutableStateOf(-1) }

    val textMeasurer = rememberTextMeasurer()

    // Форматы подписей
    val hmFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dmFmt = remember { SimpleDateFormat("dd.MM", Locale.getDefault()) }
    val myFmt = remember { SimpleDateFormat("MMM yyyy", Locale.getDefault()) }
    val mFmt = remember { SimpleDateFormat("MMM", Locale.getDefault()) }

    // Формат для плашки — зависит от периода
    val pointFmt = remember(periodLabel) {
        when (periodLabel) {
            "6h", "day" -> SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            "week", "month" -> SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
            "year" -> SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
            else -> SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        }
    }

    val labelStyle = TextStyle(
        color = AxisColor,
        fontSize = 10.sp,
        fontWeight = FontWeight.Normal
    )
    val valueStyle = TextStyle(
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 12.sp
    )

    Column(modifier.fillMaxWidth()) {

        // --- Плашка с выбранной точкой ---
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            val idx = selectedIdx
            if (idx in data.points.indices) {
                val p = data.points[idx]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("T ", color = TempColor, fontWeight = FontWeight.Bold, style = valueStyle)
                    Text("%.2f °C".format(p.temp), style = valueStyle)
                    Spacer(Modifier.width(16.dp))
                    Text("H ", color = HumiColor, fontWeight = FontWeight.Bold, style = valueStyle)
                    Text("%.1f %%".format(p.humi), style = valueStyle)
                    Spacer(Modifier.width(16.dp))
                    Text(
                        pointFmt.format(Date(p.x * 1000L)),
                        style = valueStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }
            } else {
                Text(
                    "Коснитесь графика, чтобы увидеть значения",
                    style = valueStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                )
            }
        }

        // --- Сам график ---
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .padding(start = 56.dp, end = 60.dp, top = 8.dp, bottom = 32.dp)
                .pointerInput(data) {
                    detectTapGestures { pos ->
                        selectedIdx = nearestIndex(data, pos.x, size.width.toFloat())
                    }
                }
                .pointerInput(data) {
                    detectHorizontalDragGestures(
                        onDragStart = { pos ->
                            selectedIdx = nearestIndex(data, pos.x, size.width.toFloat())
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            selectedIdx = nearestIndex(data, change.position.x, size.width.toFloat())
                        }
                    )
                }
        ) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas

            // --- Сетка и подписи оси Y (T) ---
            val yTicks = 5
            for (i in 0..yTicks) {
                val f = i / yTicks.toFloat()
                val y = h - f * h
                drawLine(
                    color = GridColor,
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1f
                )
                // Подпись слева — T
                val tVal = data.tempRange.min + f * data.tempRange.span
                val tLayout = textMeasurer.measure(
                    text = "%.1f".format(tVal),
                    style = labelStyle,
                    constraints = Constraints()
                )
                drawText(
                    textLayoutResult = tLayout,
                    topLeft = Offset(-44f, y - tLayout.size.height / 2f)
                )
                // Подпись справа — H
                val hVal = data.humiRange.min + f * data.humiRange.span
                val hLayout = textMeasurer.measure(
                    text = "%.0f".format(hVal),
                    style = labelStyle,
                    constraints = Constraints()
                )
                drawText(
                    textLayoutResult = hLayout,
                    topLeft = Offset(w + 6f, y - hLayout.size.height / 2f)
                )
            }

            // --- Вертикальная сетка и умные подписи оси X ---
            val xTicks = 4
            var prevDay: Int? = null
            var prevMonth: Int? = null
            for (i in 0..xTicks) {
                val f = i / xTicks.toFloat()
                val x = f * w
                drawLine(
                    color = GridColor,
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = 1f
                )
                val tSec = data.timeMin + (data.timeSpan * f).toLong()
                val date = Date(tSec * 1000L)
                val cal = Calendar.getInstance().apply { timeInMillis = tSec * 1000L }
                val day = cal.get(Calendar.DAY_OF_YEAR)
                val month = cal.get(Calendar.MONTH)

                val label = when (periodLabel) {
                    "6h", "day" -> hmFmt.format(date)
                    "week", "month" ->
                        if (i == 0 || prevDay == null || prevDay != day) dmFmt.format(date)
                        else hmFmt.format(date)
                    "year" ->
                        if (i == 0) myFmt.format(date)
                        else if (prevMonth == null || prevMonth != month) mFmt.format(date)
                        else dmFmt.format(date)
                    else -> hmFmt.format(date)
                }
                prevDay = day
                prevMonth = month

                val layout = textMeasurer.measure(
                    text = label,
                    style = labelStyle,
                    constraints = Constraints()
                )
                val lx = (x - layout.size.width / 2f)
                    .coerceIn(-8f, w - layout.size.width + 8f)
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(lx, h + 4f)
                )
            }

            // --- Линии ---
            drawSeries(
                points = data.points,
                data = data,
                color = TempColor,
                yRange = data.tempRange,
                yOf = { it.temp },
                w = w, h = h
            )
            drawSeries(
                points = data.points,
                data = data,
                color = HumiColor,
                yRange = data.humiRange,
                yOf = { it.humi },
                w = w, h = h
            )

            // --- Оси ---
            drawLine(AxisColor, Offset(0f, h), Offset(w, h), strokeWidth = 1.5f)
            drawLine(AxisColor, Offset(0f, 0f), Offset(0f, h), strokeWidth = 1.5f)
            drawLine(AxisColor, Offset(w, 0f), Offset(w, h), strokeWidth = 1.5f)

            // --- Crosshair на выделенной точке ---
            if (selectedIdx in data.points.indices) {
                val p = data.points[selectedIdx]
                val x = data.xFraction(p.x) * w
                drawLine(
                    color = CrosshairColor,
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = 1f
                )
                val yT = h - data.tempRange.normalize(p.temp) * h
                val yH = h - data.humiRange.normalize(p.humi) * h
                drawCircle(TempColor, radius = 5f, center = Offset(x, yT))
                drawCircle(Color.White, radius = 2f, center = Offset(x, yT))
                drawCircle(HumiColor, radius = 5f, center = Offset(x, yH))
                drawCircle(Color.White, radius = 2f, center = Offset(x, yH))
            }
        }
    }
}

private fun nearestIndex(data: ChartData, tapX: Float, width: Float): Int {
    if (data.points.isEmpty() || width <= 0f) return -1
    val frac = (tapX / width).coerceIn(0f, 1f)
    val targetT = data.timeMin + (data.timeSpan * frac).toLong()
    var lo = 0
    var hi = data.points.size - 1
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (data.points[mid].x < targetT) lo = mid + 1 else hi = mid
    }
    val cand1 = lo
    val cand2 = (lo - 1).coerceAtLeast(0)
    val d1 = kotlin.math.abs(data.points[cand1].x - targetT)
    val d2 = kotlin.math.abs(data.points[cand2].x - targetT)
    return if (d1 <= d2) cand1 else cand2
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSeries(
    points: List<ChartPoint>,
    data: ChartData,
    color: Color,
    yRange: ChartRange,
    yOf: (ChartPoint) -> Float,
    w: Float,
    h: Float
) {
    if (points.isEmpty()) return
    val path = Path()
    points.forEachIndexed { i, p ->
        val x = data.xFraction(p.x) * w
        val y = h - yRange.normalize(yOf(p)) * h
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}