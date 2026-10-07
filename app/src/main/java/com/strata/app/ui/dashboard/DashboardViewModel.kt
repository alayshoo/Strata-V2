package com.strata.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strata.app.Session
import com.strata.app.domain.DashboardInput
import com.strata.app.domain.DashboardState
import com.strata.app.domain.TimeRange
import com.strata.app.domain.buildDashboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class DashboardViewModel(private val session: Session) : ViewModel() {
    private val range = MutableStateFlow(TimeRange.Y1)

    private val input = combine(
        session.setup.assetClasses,
        session.ledger.products,
        session.ledger.snapshots,
        session.ledger.transactions,
        session.setup.spendingCategories,
        session.fx.table,
        session.setup.sources,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        DashboardInput(
            assetClasses = values[0] as List<com.strata.app.data.db.AssetClassEntity>,
            products = values[1] as List<com.strata.app.data.db.ProductEntity>,
            snapshots = values[2] as List<com.strata.app.data.db.SnapshotEntity>,
            transactions = values[3] as List<com.strata.app.data.db.TransactionEntity>,
            categories = values[4] as List<com.strata.app.data.db.SpendingCategoryEntity>,
            fx = values[5] as com.strata.app.domain.FxTable,
            sources = values[6] as List<com.strata.app.data.db.SourceEntity>,
        )
    }

    val state = combine(input, range) { i, r -> buildDashboard(i, r) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())

    init {
        // Keep ECB rates covering every foreign currency from the first recorded date.
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            combine(session.ledger.products, session.ledger.snapshots, session.ledger.transactions) { p, s, t ->
                val earliest = listOfNotNull(s.minOfOrNull { it.date }, t.minOfOrNull { it.date }).minOrNull()
                p.map { it.currency }.toSet() to earliest
            }.distinctUntilChanged().debounce(500).collect { (currencies, earliest) ->
                if (earliest != null) runCatching { session.fx.ensure(currencies, earliest, LocalDate.now()) }
            }
        }
    }

    fun setRange(value: TimeRange) { range.value = value }
}
