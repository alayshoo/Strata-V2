package com.strata.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.strata.app.domain.LinePoint
import com.strata.app.domain.MoneyFormat
import com.strata.app.domain.PairedBucket
import com.strata.app.ui.theme.Figures
import com.strata.app.ui.theme.StrataTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

data class BarSegment(val key: String, val label: String, val value: Double, val color: Color)
data class StackBucket(val date: LocalDate, val segments: List<BarSegment>)

private val monthFormat = DateTimeFormatter.ofPattern("MMM")
private val dayMonthFormat = DateTimeFormatter.ofPattern("d MMM")

private val yearFormat = DateTimeFormatter.ofPattern("yyyy")

/** Months read as months; January carries the year so the axis never shows ambiguous "Oct 25". */
private fun axisDate(date: LocalDate, spanDays: Long): String = when {
    spanDays > 100 -> if (date.monthValue == 1) date.format(yearFormat) else date.format(monthFormat)
    else -> date.format(dayMonthFormat)
}

/** Round tick values covering [lo, hi]. Always includes zero when the data crosses or touches it. */
internal fun niceTicks(lo: Double, hi: Double, count: Int = 4): List<Double> {
    if (hi <= lo) return listOf(lo)
    val raw = (hi - lo) / count
    val magnitude = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * magnitude }.first { it >= raw }
    val start = floor(lo / step) * step
    val end = ceil(hi / step) * step
    return generateSequence(start) { it + step }.takeWhile { it <= end + step / 2 }.toList()
}

private class ChartInk(
    val grid: Color,
    val axisText: TextStyle,
    val surface: Color,
    val tooltipBg: Color,
    val tooltipText: TextStyle,
    val tooltipMuted: TextStyle,
)

@Composable
private fun rememberInk(): ChartInk {
    val scheme = MaterialTheme.colorScheme
    val extras = StrataTheme.colors
    return ChartInk(
        grid = extras.gridLine,
        axisText = Figures.axis.copy(color = scheme.onSurfaceVariant),
        surface = scheme.surfaceContainer,
        tooltipBg = scheme.inverseSurface,
        tooltipText = Figures.small.copy(color = scheme.inverseOnSurface),
        tooltipMuted = Figures.axis.copy(color = scheme.inverseOnSurface.copy(alpha = 0.72f)),
    )
}

private const val AXIS_WIDTH_DP = 44
private const val X_AXIS_HEIGHT_DP = 22

/** A value over time, net worth by default: 2dp line over a 10% wash, drag to scrub. */
@Composable
fun LineChart(
    points: List<LinePoint>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    height: Dp = 200.dp,
    initialSelection: Int? = null,
    valueFormat: (Double) -> String = { MoneyFormat.whole(it.toBigDecimal()) },
    axisFormat: (Double) -> String = MoneyFormat::compact,
    dateFormat: (LocalDate) -> String = MoneyFormat::date,
) {
    val ink = rememberInk()
    val measurer = rememberTextMeasurer()
    var selected by remember(points) { mutableStateOf(initialSelection) }

    // X follows calendar time, so irregular snapshot dates are spaced honestly.
    val first = points.firstOrNull()?.date?.toEpochDay() ?: 0L
    val span = max(1L, (points.lastOrNull()?.date?.toEpochDay() ?: 0L) - first)
    fun nearest(fraction: Float): Int {
        val day = first + fraction * span
        return points.indices.minByOrNull { abs(points[it].date.toEpochDay() - day) } ?: 0
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(points) {
                fun index(x: Float): Int = nearest(x / (size.width - AXIS_WIDTH_DP.dp.toPx()))
                detectHorizontalDragGestures(
                    onDragStart = { selected = index(it.x) },
                    onDragEnd = { selected = null },
                    onDragCancel = { selected = null },
                ) { change, _ -> selected = index(change.position.x) }
            }
            .pointerInput(points) {
                detectTapGestures { offset ->
                    val i = nearest(offset.x / (size.width - AXIS_WIDTH_DP.dp.toPx()))
                    selected = if (selected == i) null else i
                }
            }
    ) {
        if (points.size < 2) return@Canvas
        val axisW = AXIS_WIDTH_DP.dp.toPx()
        val xAxisH = X_AXIS_HEIGHT_DP.dp.toPx()
        val plotW = size.width - axisW
        val plotH = size.height - xAxisH
        val values = points.map { it.value }
        val lo = min(values.min(), if (values.min() >= 0 && values.min() < values.max() * 0.15) 0.0 else values.min())
        val ticks = niceTicks(lo, values.max())
        val yMin = ticks.first()
        val yMax = ticks.last().takeIf { it > yMin } ?: (yMin + 1)
        fun y(v: Double) = (plotH - ((v - yMin) / (yMax - yMin)) * plotH).toFloat()
        fun x(i: Int) = (points[i].date.toEpochDay() - first).toFloat() / span * plotW

        drawGrid(ticks, ::y, plotW, axisW, ink, measurer, axisFormat)

        val line = Path()
        points.forEachIndexed { i, p -> if (i == 0) line.moveTo(x(i), y(p.value)) else line.lineTo(x(i), y(p.value)) }
        val area = Path().apply {
            addPath(line)
            lineTo(x(points.lastIndex), plotH)
            lineTo(0f, plotH)
            close()
        }
        drawPath(area, color.copy(alpha = 0.10f))
        drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        drawDateAxis(points.first().date, points.last().date, plotW, plotH, ink, measurer)

        val focus = (selected ?: points.lastIndex).coerceIn(0, points.lastIndex)
        val fx = x(focus)
        val fy = y(points[focus].value)
        if (selected != null) {
            drawLine(ink.grid.copy(alpha = 1f), Offset(fx, 0f), Offset(fx, plotH), strokeWidth = 1.dp.toPx())
        }
        drawCircle(ink.surface, radius = 6.dp.toPx(), center = Offset(fx, fy))
        drawCircle(color, radius = 4.dp.toPx(), center = Offset(fx, fy))
        if (selected != null) {
            drawTooltip(
                anchor = Offset(fx, fy),
                title = dateFormat(points[focus].date),
                lines = listOf(null to valueFormat(points[focus].value)),
                ink = ink,
                measurer = measurer,
                bounds = Size(plotW, plotH),
            )
        }
    }
}

/**
 * Values stacked per period, e.g. asset classes or spending categories. Negatives hang below the zero line.
 * Bars cap at 24dp, a 2dp surface gap separates segments, the outer end is rounded.
 * [monthly] buckets are labelled by month rather than by their end date.
 */
@Composable
fun StackedBarChart(
    buckets: List<StackBucket>,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    initialSelection: Int? = null,
    monthly: Boolean = false,
) {
    val ink = rememberInk()
    val measurer = rememberTextMeasurer()
    var selected by remember(buckets) { mutableStateOf(initialSelection) }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(buckets) {
                detectTapGestures { offset ->
                    val plotW = size.width - AXIS_WIDTH_DP.dp.toPx()
                    val slot = plotW / max(1, buckets.size)
                    val i = (offset.x / slot).toInt().coerceIn(0, max(0, buckets.lastIndex))
                    selected = if (selected == i) null else i
                }
            }
    ) {
        if (buckets.isEmpty()) return@Canvas
        val axisW = AXIS_WIDTH_DP.dp.toPx()
        val xAxisH = X_AXIS_HEIGHT_DP.dp.toPx()
        val plotW = size.width - axisW
        val plotH = size.height - xAxisH
        val tops = buckets.map { b -> b.segments.filter { it.value > 0 }.sumOf { it.value } }
        val bottoms = buckets.map { b -> b.segments.filter { it.value < 0 }.sumOf { it.value } }
        // Ticks cover the assets; the scale only reaches as far below zero as liabilities need.
        val ticks = niceTicks(0.0, max(1.0, tops.max()))
        val yMax = ticks.last()
        val yMin = min(0.0, bottoms.min() * 1.15)
        fun y(v: Double) = (plotH - ((v - yMin) / (yMax - yMin)) * plotH).toFloat()

        drawGrid(ticks.filter { it >= yMin }, ::y, plotW, axisW, ink, measurer)

        val slot = plotW / buckets.size
        val barW = min(24.dp.toPx(), slot * 0.62f)
        val gap = 2.dp.toPx()
        val radius = 4.dp.toPx()
        val zero = y(0.0)

        buckets.forEachIndexed { i, bucket ->
            val left = i * slot + (slot - barW) / 2
            val alpha = if (selected == null || selected == i) 1f else 0.35f
            for (positive in listOf(true, false)) {
                val segs = bucket.segments.filter { if (positive) it.value > 0 else it.value < 0 }
                var base = 0.0
                segs.forEachIndexed { s, seg ->
                    val from = y(base)
                    base += seg.value
                    val to = y(base)
                    val top = min(from, to)
                    val bottom = max(from, to)
                    if (bottom - top <= 0.5f) return@forEachIndexed
                    // Every segment but the outermost leaves a surface gap at its far end.
                    val outer = s == segs.lastIndex
                    val rect = if (positive) {
                        val t = top + if (outer) 0f else gap
                        RoundRect(left, t, left + barW, bottom, topLeftCornerRadius = cr(outer, radius), topRightCornerRadius = cr(outer, radius))
                    } else {
                        val b = bottom - if (outer) 0f else gap
                        RoundRect(left, top, left + barW, b, bottomLeftCornerRadius = cr(outer, radius), bottomRightCornerRadius = cr(outer, radius))
                    }
                    drawPath(Path().apply { addRoundRect(rect) }, seg.color.copy(alpha = alpha))
                }
            }
        }
        drawLine(ink.axisText.color.copy(alpha = 0.5f), Offset(0f, zero), Offset(plotW, zero), strokeWidth = 1.dp.toPx())
        drawBucketAxis(buckets.map { it.date }, slot, plotH, ink, measurer, monthsOnly = monthly)

        selected?.takeIf { it in buckets.indices }?.let { i ->
            val bucket = buckets[i]
            val total = bucket.segments.sumOf { it.value }
            val period = if (monthly) MoneyFormat.monthYear(bucket.date) else MoneyFormat.date(bucket.date)
            drawTooltip(
                anchor = Offset(i * slot + slot / 2, y(tops[i])),
                title = period + "  ·  " + MoneyFormat.whole(total.toBigDecimal()),
                lines = bucket.segments.filter { abs(it.value) >= 0.5 }.sortedByDescending { it.value }
                    .map { it.color to "${it.label}  ${MoneyFormat.whole(it.value.toBigDecimal())}" },
                ink = ink,
                measurer = measurer,
                bounds = Size(plotW, plotH),
            )
        }
    }
}

private fun cr(rounded: Boolean, r: Float) = if (rounded) CornerRadius(r, r) else CornerRadius.Zero

/** Two bars per month from a shared baseline, e.g. income and spending. */
@Composable
fun PairedBarChart(
    buckets: List<PairedBucket>,
    firstColor: Color,
    secondColor: Color,
    firstLabel: String,
    secondLabel: String,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    initialSelection: Int? = null,
) {
    val ink = rememberInk()
    val measurer = rememberTextMeasurer()
    var selected by remember(buckets) { mutableStateOf(initialSelection) }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(buckets) {
                detectTapGestures { offset ->
                    val plotW = size.width - AXIS_WIDTH_DP.dp.toPx()
                    val slot = plotW / max(1, buckets.size)
                    val i = (offset.x / slot).toInt().coerceIn(0, max(0, buckets.lastIndex))
                    selected = if (selected == i) null else i
                }
            }
    ) {
        if (buckets.isEmpty()) return@Canvas
        val axisW = AXIS_WIDTH_DP.dp.toPx()
        val plotW = size.width - axisW
        val plotH = size.height - X_AXIS_HEIGHT_DP.dp.toPx()
        val ticks = niceTicks(0.0, max(1.0, buckets.maxOf { max(it.first, it.second) }))
        val yMax = ticks.last()
        fun y(v: Double) = (plotH - (v / yMax) * plotH).toFloat()

        drawGrid(ticks, ::y, plotW, axisW, ink, measurer)
        val slot = plotW / buckets.size
        val gap = 2.dp.toPx()
        val barW = min(16.dp.toPx(), (slot * 0.7f - gap) / 2)
        val r = 4.dp.toPx()
        buckets.forEachIndexed { i, b ->
            val alpha = if (selected == null || selected == i) 1f else 0.35f
            val left = i * slot + (slot - (barW * 2 + gap)) / 2
            listOf(b.first to firstColor, b.second to secondColor).forEachIndexed { k, (v, c) ->
                if (v <= 0) return@forEachIndexed
                val l = left + k * (barW + gap)
                drawPath(
                    Path().apply { addRoundRect(RoundRect(l, y(v), l + barW, plotH, topLeftCornerRadius = CornerRadius(r, r), topRightCornerRadius = CornerRadius(r, r))) },
                    c.copy(alpha = alpha),
                )
            }
        }
        drawLine(ink.axisText.color.copy(alpha = 0.5f), Offset(0f, plotH), Offset(plotW, plotH), strokeWidth = 1.dp.toPx())
        drawBucketAxis(buckets.map { it.date }, slot, plotH, ink, measurer, monthsOnly = true)

        selected?.takeIf { it in buckets.indices }?.let { i ->
            val b = buckets[i]
            drawTooltip(
                anchor = Offset(i * slot + slot / 2, y(max(b.first, b.second))),
                title = MoneyFormat.monthYear(b.date),
                lines = listOf(
                    firstColor to "$firstLabel  ${MoneyFormat.whole(b.first.toBigDecimal())}",
                    secondColor to "$secondLabel  ${MoneyFormat.whole(b.second.toBigDecimal())}",
                    null to "Net  ${MoneyFormat.whole((b.first - b.second).toBigDecimal())}",
                ),
                ink = ink,
                measurer = measurer,
                bounds = Size(plotW, plotH),
            )
        }
    }
}

private fun DrawScope.drawGrid(
    ticks: List<Double>,
    y: (Double) -> Float,
    plotW: Float,
    axisW: Float,
    ink: ChartInk,
    measurer: TextMeasurer,
    label: (Double) -> String = MoneyFormat::compact,
) {
    for (t in ticks) {
        val ty = y(t)
        drawLine(ink.grid, Offset(0f, ty), Offset(plotW, ty), strokeWidth = 1.dp.toPx())
        val layout = measurer.measure(label(t), ink.axisText)
        drawText(layout, topLeft = Offset(plotW + axisW - layout.size.width, ty - layout.size.height / 2f))
    }
}

private fun DrawScope.drawDateAxis(start: LocalDate, end: LocalDate, plotW: Float, plotH: Float, ink: ChartInk, measurer: TextMeasurer) {
    val span = java.time.temporal.ChronoUnit.DAYS.between(start, end)
    val labels = listOf(start, start.plusDays(span / 2), end)
    labels.forEachIndexed { i, d ->
        val text = if (span > 330) d.format(DateTimeFormatter.ofPattern("MMM yyyy")) else axisDate(d, span)
        val layout = measurer.measure(text, ink.axisText)
        val x = when (i) {
            0 -> 0f
            1 -> plotW / 2 - layout.size.width / 2
            else -> plotW - layout.size.width
        }
        drawText(layout, topLeft = Offset(x, plotH + 6.dp.toPx()))
    }
}

private fun DrawScope.drawBucketAxis(
    dates: List<LocalDate>,
    slot: Float,
    plotH: Float,
    ink: ChartInk,
    measurer: TextMeasurer,
    monthsOnly: Boolean = false,
) {
    val span = java.time.temporal.ChronoUnit.DAYS.between(dates.first(), dates.last())
    val labelW = measurer.measure("0000", ink.axisText).size.width + 10.dp.toPx()
    val every = max(1, ceil(labelW / slot).toInt())
    dates.forEachIndexed { i, d ->
        if ((dates.lastIndex - i) % every != 0) return@forEachIndexed
        val text = if (monthsOnly) axisDate(d, 365) else axisDate(d, span)
        val layout = measurer.measure(text, ink.axisText)
        val x = (i * slot + slot / 2 - layout.size.width / 2).coerceAtLeast(0f)
        drawText(layout, topLeft = Offset(x, plotH + 6.dp.toPx()))
    }
}

private fun DrawScope.drawTooltip(
    anchor: Offset,
    title: String,
    lines: List<Pair<Color?, String>>,
    ink: ChartInk,
    measurer: TextMeasurer,
    bounds: Size,
) {
    val pad = 10.dp.toPx()
    val dot = 8.dp.toPx()
    val titleLayout = measurer.measure(title, ink.tooltipMuted)
    val lineLayouts = lines.map { (c, text) -> c to measurer.measure(text, ink.tooltipText) }
    val lineH = lineLayouts.maxOfOrNull { it.second.size.height } ?: 0
    val contentW = max(
        titleLayout.size.width.toFloat(),
        lineLayouts.maxOfOrNull { (c, l) -> l.size.width + if (c != null) dot + 6.dp.toPx() else 0f } ?: 0f,
    )
    val w = contentW + pad * 2
    val h = pad * 2 + titleLayout.size.height + lineLayouts.size * (lineH + 2.dp.toPx())
    var left = anchor.x - w / 2
    left = left.coerceIn(0f, max(0f, bounds.width - w))
    var top = anchor.y - h - 12.dp.toPx()
    if (top < 0) top = min(anchor.y + 12.dp.toPx(), bounds.height - h).coerceAtLeast(0f)
    drawRoundRect(ink.tooltipBg, Offset(left, top), Size(w, h), CornerRadius(12.dp.toPx()))
    drawText(titleLayout, topLeft = Offset(left + pad, top + pad))
    var yy = top + pad + titleLayout.size.height + 2.dp.toPx()
    for ((c, layout) in lineLayouts) {
        var xx = left + pad
        if (c != null) {
            drawCircle(c, radius = dot / 2, center = Offset(xx + dot / 2, yy + layout.size.height / 2f))
            xx += dot + 6.dp.toPx()
        }
        drawText(layout, topLeft = Offset(xx, yy))
        yy += lineH + 2.dp.toPx()
    }
}
