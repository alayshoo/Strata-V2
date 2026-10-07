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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.strata.app.domain.DashboardState
import com.strata.app.domain.LastMonthExpenses
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
data class ChartPreviewSelection(
    val line: Int? = null,
    val bars: Int? = null,
    val flows: Int? = null,
    val categories: Int? = null,
    val growth: Int? = null,
    val holdings: Int? = null,
    val rate: Int? = null,
)

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
        item { SavingsReturnsPanel(state, previewSelection.growth) }
        item { AssetClassPanel(state, previewSelection.bars) }
        if (state.institutions.isNotEmpty()) item { InstitutionPanel(state) }
        item { HoldingsIncomePanel(state, previewSelection.holdings) }
        item { CashFlowPanel(state, previewSelection.flows) }
        if (state.hasFlows) item { SavingsRatePanel(state, previewSelection.rate) }
        item { SpendingCategoryPanel(state, previewSelection.categories) }
        if (state.hasFlows) state.lastMonth?.let { item { LargestExpensesPanel(it) } }
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

internal fun rangePhrase(range: TimeRange) = when (range) {
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
            FlowTotal("Income", money(state.income), colors.income, Modifier.weight(1f))
            FlowTotal("Spending", money(state.spending), colors.spending, Modifier.weight(1f))
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
    }
}

@Composable
private fun SpendingCategoryPanel(state: DashboardState, selection: Int?) {
    Panel(title = "Spending by category") {
        if (state.categories.isEmpty()) {
            Text(
                "Once spending is recorded, each month's total shows here split by category.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Panel
        }
        val byId = state.categories.associateBy { it.id }
        val outline = MaterialTheme.colorScheme.outline
        val buckets = state.categoryBars.map { b ->
            StackBucket(b.date, b.values.map { (id, v) ->
                val c = byId[id]
                BarSegment(id.toString(), c?.name ?: "Uncategorised", v, c?.let { seriesColor(it.colorKey) } ?: outline)
            })
        }
        StackedBarChart(buckets, initialSelection = selection, monthly = true)
        Spacer(Modifier.height(12.dp))
        state.categories.forEach { c ->
            LegendRow(
                color = seriesColor(c.colorKey),
                label = c.name,
                value = MoneyFormat.whole(BigDecimal.valueOf(c.value)),
                detail = MoneyFormat.percent(c.share),
            )
        }
    }
}

@Composable
private fun FlowTotal(label: String, value: String, color: Color?, modifier: Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (color != null) {
                Swatch(color, 8.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value, style = Figures.large)
    }
}

private fun money(value: Double) = MoneyFormat.whole(BigDecimal.valueOf(value))
private fun signedMoney(value: Double) = MoneyFormat.signedWhole(BigDecimal.valueOf(value))

@Composable
private fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
private fun SavingsReturnsPanel(state: DashboardState, selection: Int?) {
    val savedColor = StrataTheme.colors.income
    val returnsColor = seriesColor("violet")
    val newColor = seriesColor("graphite")
    val saved = state.growth.sumOf { it.saved }
    val returns = state.growth.sumOf { it.returns }
    val newAccounts = state.growth.sumOf { it.newAccounts }
    val showNew = state.growth.any { kotlin.math.abs(it.newAccounts) >= 0.5 }
    val buckets = state.growth.map { g ->
        StackBucket(g.date, listOfNotNull(
            BarSegment("saved", "Saved", g.saved, savedColor),
            BarSegment("returns", "Returns", g.returns, returnsColor),
            if (showNew) BarSegment("new", "New accounts", g.newAccounts, newColor) else null,
        ))
    }
    Panel(title = "Savings and returns") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowTotal("Saved", signedMoney(saved), savedColor, Modifier.weight(1f))
            FlowTotal("Returns", signedMoney(returns), returnsColor, Modifier.weight(1f))
            if (showNew) FlowTotal("New accounts", signedMoney(newAccounts), newColor, Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        StackedBarChart(buckets, height = 200.dp, initialSelection = selection, monthly = true)
        Spacer(Modifier.height(10.dp))
        Caption(
            buildString {
                append("How your net worth changed ${rangePhrase(state.range)}. Saved is income minus spending; ")
                append("returns is the rest: price moves, dividends and interest, less fees.")
                if (showNew) append(" New accounts is the opening balance of accounts first recorded in this period.")
                if (!state.hasFlows) append(" Share a bank statement in Chat to separate what you saved from what your holdings earned.")
            }
        )
    }
}

@Composable
private fun InstitutionPanel(state: DashboardState) {
    Panel(title = "Institutions") {
        ShareStrip(state.institutions.map { it.share to seriesColor(it.colorKey) })
        Spacer(Modifier.height(12.dp))
        state.institutions.forEach { inst ->
            LegendRow(
                color = seriesColor(inst.colorKey),
                label = inst.name,
                value = money(inst.value),
                detail = MoneyFormat.percent(inst.share),
            )
        }
        val flagged = state.institutions.filter { it.aboveGuarantee }
        if (flagged.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Notice(
                "Cash at ${flagged.joinToString { it.name }} is above the €100,000 the EU deposit guarantee covers per bank.",
            )
        }
    }
}

/** Today's shares as one rounded bar of segments. */
@Composable
private fun ShareStrip(parts: List<Pair<Double, Color>>) {
    val visible = parts.filter { it.first > 0 }
    if (visible.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        visible.forEach { (share, color) ->
            Box(Modifier.weight(share.toFloat().coerceAtLeast(0.012f)).fillMaxHeight().background(color))
        }
    }
}

@Composable
private fun HoldingsIncomePanel(state: DashboardState, selection: Int?) {
    val dividendColor = seriesColor("saffron")
    val interestColor = seriesColor("sky")
    val feeColor = StrataTheme.colors.spending
    Panel(title = "Dividends and interest") {
        if (!state.hasHoldingsIncome) {
            Caption("Share broker and bank statements in Chat and the dividends, interest and fees they list show up here.")
            return@Panel
        }
        val months = state.holdingsIncome
        val dividends = months.sumOf { it.dividends }
        val interest = months.sumOf { it.interest }
        val fees = months.sumOf { it.fees }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowTotal("Dividends", money(dividends), dividendColor, Modifier.weight(1f))
            FlowTotal("Interest", money(interest), interestColor, Modifier.weight(1f))
            FlowTotal("Fees", signedMoney(fees), feeColor, Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        StackedBarChart(
            months.map { m ->
                StackBucket(m.date, listOf(
                    BarSegment("dividends", "Dividends", m.dividends, dividendColor),
                    BarSegment("interest", "Interest", m.interest, interestColor),
                    BarSegment("fees", "Fees", m.fees, feeColor),
                ))
            },
            height = 180.dp,
            initialSelection = selection,
            monthly = true,
        )
        Spacer(Modifier.height(10.dp))
        val net = dividends + interest + fees
        Caption("${signedMoney(net)} after fees ${rangePhrase(state.range)}, about ${money(net / months.size.coerceAtLeast(1))} a month.")
    }
}

@Composable
private fun SavingsRatePanel(state: DashboardState, selection: Int?) {
    Panel(title = "Savings rate") {
        val points = state.savingsRate
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowTotal("Last three months", points.lastOrNull()?.let { MoneyFormat.percent(it.value) } ?: "—", null, Modifier.weight(1f))
            val overall = if (state.income > 0) MoneyFormat.percent((state.income - state.spending) / state.income) else "—"
            FlowTotal("Whole period", overall, null, Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        if (points.size < 2) {
            Caption("The trend appears once there are two complete months of income and spending.")
            return@Panel
        }
        LineChart(
            points,
            color = StrataTheme.colors.income,
            height = 170.dp,
            initialSelection = selection,
            valueFormat = MoneyFormat::percent,
            axisFormat = MoneyFormat::axisPercent,
            dateFormat = MoneyFormat::monthYear,
        )
        Spacer(Modifier.height(10.dp))
        Caption("Share of income you kept, averaged over the three months up to each complete month.")
    }
}

@Composable
private fun LargestExpensesPanel(last: LastMonthExpenses) {
    Panel(title = "Largest expenses in ${MoneyFormat.month(last.month)}") {
        if (last.largest.isEmpty()) {
            Caption("No expenses recorded for ${MoneyFormat.monthYear(last.month)}.")
            return@Panel
        }
        Caption("${money(last.total)} spent across ${last.count} ${if (last.count == 1) "expense" else "expenses"}.")
        Spacer(Modifier.height(6.dp))
        last.largest.forEach { e ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Swatch(seriesColor(e.colorKey ?: "graphite"))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(MoneyFormat.dayMonth(e.date), e.category ?: "Uncategorised", e.description)
                            .filter { it.isNotBlank() }.distinctBy { it.lowercase() }.joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(money(e.amount), style = Figures.medium)
            }
        }
    }
}

@Composable
private fun Notice(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun FxNotice(currencies: Set<String>) {
    Notice("No exchange rate for ${currencies.joinToString()} yet, so those products are left out of the totals.")
}
