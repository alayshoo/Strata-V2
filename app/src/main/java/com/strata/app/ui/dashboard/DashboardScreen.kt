package com.strata.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.strata.app.domain.DashboardState
import com.strata.app.domain.MoneyFormat
import com.strata.app.domain.TimeRange
import com.strata.app.ui.components.BarSegment
import com.strata.app.ui.components.EmptyState
import com.strata.app.ui.components.LegendRow
import com.strata.app.ui.components.LineChart
import com.strata.app.ui.components.PairedBarChart
import com.strata.app.ui.components.Panel
import com.strata.app.ui.components.RangeSelector
import com.strata.app.ui.components.StackBucket
import com.strata.app.ui.components.StackedBarChart
import com.strata.app.ui.components.Swatch
import com.strata.app.ui.theme.Figures
import com.strata.app.ui.theme.StrataTheme
import com.strata.app.ui.theme.seriesColor
import java.math.BigDecimal

/** Chart selections are only used to pose screenshots. */
data class ChartPreviewSelection(val line: Int? = null, val bars: Int? = null, val flows: Int? = null)

@Composable
fun DashboardScreen(
    state: DashboardState,
    onRangeChange: (TimeRange) -> Unit,
    onOpenChat: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    previewSelection: ChartPreviewSelection = ChartPreviewSelection(),
) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { HeroCard(state) }
        if (!state.hasData && !state.loading) {
            item {
                Panel {
                    EmptyState(
                        title = "Nothing recorded yet",
                        body = "Set up your institutions and asset classes in Setup, then share a statement in Chat. Your dashboard fills in from there.",
                        icon = Icons.Rounded.AutoAwesome,
                        action = { Button(onClick = onOpenChat) { Text("Open chat") } },
                        modifier = Modifier.padding(0.dp),
                    )
                }
            }
            return@LazyColumn
        }
        item { RangeSelector(state.range, onRangeChange) }
        if (state.missingFx.isNotEmpty()) item { FxNotice(state.missingFx) }
        item {
            Panel(title = "Net worth") {
                LineChart(state.line, initialSelection = previewSelection.line)
            }
        }
        item { AssetClassPanel(state, previewSelection.bars) }
        item { CashFlowPanel(state, previewSelection.flows) }
    }
}

@Composable
private fun HeroCard(state: DashboardState) {
    val colors = StrataTheme.colors
    Surface(shape = RoundedCornerShape(32.dp), color = colors.hero, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 18.dp)) {
            Text("Net worth", style = MaterialTheme.typography.titleMedium, color = colors.onHeroMuted)
            Spacer(Modifier.height(2.dp))
            Text(
                MoneyFormat.whole(BigDecimal.valueOf(state.netWorth)),
                style = MaterialTheme.typography.displayMedium,
                color = colors.onHero,
            )
            Spacer(Modifier.height(10.dp))
            if (state.hasData) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val up = state.change >= 0
                    Row(
                        Modifier.clip(CircleShape).background(colors.onHero.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (up) Icons.AutoMirrored.Rounded.TrendingUp else Icons.AutoMirrored.Rounded.TrendingDown,
                            contentDescription = if (up) "Up" else "Down",
                            tint = colors.onHero,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            MoneyFormat.signedWhole(BigDecimal.valueOf(state.change)) +
                                (state.changeRatio?.let { "  ·  " + MoneyFormat.percent(it) } ?: ""),
                            style = Figures.small,
                            color = colors.onHero,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(rangePhrase(state.range), style = MaterialTheme.typography.bodyMedium, color = colors.onHeroMuted)
                }
                Spacer(Modifier.height(18.dp))
                StrataStrip(state)
            } else {
                Text("Your total across every institution, in euros.", style = MaterialTheme.typography.bodyMedium, color = colors.onHeroMuted)
            }
        }
    }
}

/** The signature: today's allocation as stacked mineral layers along the card's edge. */
@Composable
private fun StrataStrip(state: DashboardState) {
    val assets = state.slices.filter { !it.isLiability && it.value > 0 }
    if (assets.isEmpty()) return
    // A white track keeps every class visible, including those close to the card colour.
    Row(
        Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(9.dp)).background(StrataTheme.colors.onHero).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        assets.forEach { slice ->
            Box(
                Modifier
                    .weight(slice.share.toFloat().coerceAtLeast(0.012f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .background(seriesColor(slice.colorKey))
            )
        }
    }
}

private fun rangePhrase(range: TimeRange) = when (range) {
    TimeRange.ALL -> "since your first record"
    TimeRange.Y1 -> "over the last year"
    TimeRange.YTD -> "this year"
    TimeRange.M6 -> "over six months"
    TimeRange.M3 -> "over three months"
}

@Composable
private fun AssetClassPanel(state: DashboardState, selection: Int?) {
    val names = state.slices.associate { it.id to it }
    val colorByClass = state.slices.associate { it.id to seriesColor(it.colorKey) }
    val buckets = state.bars.map { b ->
        StackBucket(b.date, b.values.map { (id, v) ->
            BarSegment(id.toString(), names[id]?.name ?: "", v, colorByClass[id] ?: MaterialTheme.colorScheme.outline)
        })
    }
    Panel(title = "Asset classes") {
        StackedBarChart(buckets, initialSelection = selection)
        Spacer(Modifier.height(12.dp))
        state.slices.sortedWith(compareBy({ it.isLiability }, { -it.value })).forEach { slice ->
            LegendRow(
                color = seriesColor(slice.colorKey),
                label = slice.name,
                value = MoneyFormat.whole(BigDecimal.valueOf(if (slice.isLiability) -slice.value else slice.value)),
                detail = if (slice.isLiability) "owed" else MoneyFormat.percent(slice.share),
            )
        }
    }
}

@Composable
private fun CashFlowPanel(state: DashboardState, selection: Int?) {
    val colors = StrataTheme.colors
    Panel(title = "Cash flow") {
        if (!state.hasFlows) {
            Text(
                "Share a bank statement in Chat and your income and spending show up here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Panel
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowTotal("Income", state.income, colors.income, Modifier.weight(1f))
            FlowTotal("Spending", state.spending, colors.spending, Modifier.weight(1f))
            val saved = if (state.income > 0) (state.income - state.spending) / state.income else null
            Column(Modifier.weight(1f)) {
                Text("Kept", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(saved?.let { MoneyFormat.percent(it) } ?: "—", style = Figures.large)
            }
        }
        Spacer(Modifier.height(14.dp))
        PairedBarChart(
            state.flows, colors.income, colors.spending, "Income", "Spending",
            initialSelection = selection,
        )
        if (state.categories.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text("Where it went", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            val top = state.categories.first().value
            state.categories.take(6).forEach { c -> CategoryBar(c.name, c.value, c.share, top, seriesColor(c.colorKey)) }
        }
    }
}

@Composable
private fun FlowTotal(label: String, value: Double, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Swatch(color, 8.dp)
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(MoneyFormat.whole(BigDecimal.valueOf(value)), style = Figures.large)
    }
}

@Composable
private fun CategoryBar(name: String, value: Double, share: Double, max: Double, color: androidx.compose.ui.graphics.Color) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(MoneyFormat.percent(share), style = Figures.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text(MoneyFormat.whole(BigDecimal.valueOf(value)), style = Figures.small)
        }
        Spacer(Modifier.height(5.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Box(Modifier.fillMaxWidth((value / max).toFloat().coerceIn(0.02f, 1f)).fillMaxHeight().clip(CircleShape).background(color))
        }
    }
}

@Composable
private fun FxNotice(currencies: Set<String>) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(12.dp))
            Text(
                "No exchange rate for ${currencies.joinToString()} yet, so those products are left out of the totals.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
