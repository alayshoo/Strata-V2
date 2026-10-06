package com.strata.app.ui.explorer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strata.app.Session
import com.strata.app.data.db.ImportEntity
import com.strata.app.data.db.ProductEntity
import com.strata.app.data.db.SnapshotEntity
import com.strata.app.data.db.TransactionEntity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ExplorerViewModel(private val session: Session) : ViewModel() {
    private val setup = combine(session.setup.sources, session.setup.assetClasses, session.setup.spendingCategories) { s, c, k -> Triple(s, c, k) }
    private val ledger = combine(session.ledger.products, session.ledger.snapshots, session.ledger.transactions, session.ledger.imports) { p, s, t, i ->
        listOf(p, s, t, i)
    }

    @Suppress("UNCHECKED_CAST")
    val data = combine(setup, ledger, session.fx.table) { (s, c, k), l, fx ->
        ExplorerData(
            sources = s, assetClasses = c, categories = k,
            products = l[0] as List<ProductEntity>,
            snapshots = l[1] as List<SnapshotEntity>,
            transactions = l[2] as List<TransactionEntity>,
            imports = l[3] as List<ImportEntity>,
            fx = fx,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExplorerData())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    fun saveProduct(p: ProductEntity) = viewModelScope.launch { session.ledger.saveProduct(p) }
    fun deleteProduct(p: ProductEntity) = viewModelScope.launch { session.ledger.deleteProduct(p) }
    fun saveSnapshot(s: SnapshotEntity) = viewModelScope.launch { session.ledger.saveSnapshot(s) }
    fun deleteSnapshot(s: SnapshotEntity) = viewModelScope.launch { session.ledger.deleteSnapshot(s) }
    fun saveTransaction(t: TransactionEntity) = viewModelScope.launch { session.ledger.saveTransaction(t) }
    fun deleteTransaction(t: TransactionEntity) = viewModelScope.launch { session.ledger.deleteTransaction(t) }

    fun undoImport(entry: ImportEntity) = viewModelScope.launch {
        session.ledger.undoImport(entry.id)
        _messages.emit("Import removed")
    }
}
