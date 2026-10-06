package com.strata.app.ui.explorer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.strata.app.data.db.ImportEntity
import com.strata.app.data.db.ProductEntity
import com.strata.app.data.db.SnapshotEntity
import com.strata.app.data.db.TransactionEntity
import com.strata.app.data.db.TxKind
import com.strata.app.domain.LinePoint
import com.strata.app.domain.MoneyFormat
import com.strata.app.ui.components.EmptyState
import com.strata.app.ui.components.LineChart
import com.strata.app.ui.components.Monogram
import com.strata.app.ui.components.ScreenTitle
import com.strata.app.ui.components.Swatch
import com.strata.app.ui.theme.Figures
import com.strata.app.ui.theme.StrataTheme
import com.strata.app.ui.theme.seriesColor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class ExplorerTab(val label: String) { HOLDINGS("Holdings"), TRANSACTIONS("Transactions"), IMPORTS("Imports") }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExplorerScreen(
    data: ExplorerData,
    onOpenProduct: (Long) -> Unit,
    onUndoImport: (ImportEntity) -> Unit,
    onSaveProduct: (ProductEntity) -> Unit,
    onSaveTransaction: (TransactionEntity) -> Unit,
    onDeleteTransaction: (TransactionEntity) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    initialTab: ExplorerTab = ExplorerTab.HOLDINGS,
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var filter by rememberSaveable { mutableStateOf(TxFilter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var editingProduct by remember { mutableStateOf<ProductEntity?>(null) }
    var editingTransaction by remember { mutableStateOf<TransactionEntity?>(null) }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { ScreenTitle("Data", subtitle = "Everything recorded, row by row", startPadding = 4.dp) }
            item {
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                    ExplorerTab.entries.forEachIndexed { i, t ->
                        ToggleButton(
                            checked = tab == t,
                            onCheckedChange = { tab = t },
                            modifier = Modifier.weight(1f),
                            shapes = when (i) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                ExplorerTab.entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                            colors = ToggleButtonDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                checkedContainerColor = MaterialTheme.colorScheme.primary,
                                checkedContentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) { Text(t.label, maxLines = 1) }
                    }
                }
            }
            when (tab) {
                ExplorerTab.HOLDINGS -> holdingsItems(data, onOpenProduct)
                ExplorerTab.TRANSACTIONS -> {
                    item {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Search descriptions and payees") },
                            leadingIcon = { Icon(Icons.Rounded.Search, null) },
                            singleLine = true,
                            shape = RoundedCornerShape(28.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(TxFilter.entries) { f ->
                                FilterChip(
                                    selected = filter == f,
                                    onClick = { filter = f },
                                    label = { Text(f.label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    ),
                                )
                            }
                        }
                    }
                    transactionItems(transactionRows(data, filter, query)) { editingTransaction = it }
                }
                ExplorerTab.IMPORTS -> importItems(data.imports, onUndoImport)
            }
        }
        if (tab != ExplorerTab.IMPORTS && data.sources.isNotEmpty() && data.assetClasses.isNotEmpty()) {
            ExtendedFloatingActionButton(
                onClick = {
                    if (tab == ExplorerTab.HOLDINGS) {
                        editingProduct = ProductEntity(sourceId = data.sources.first().id, assetClassId = data.assetClasses.first().id, name = "", currency = "EUR")
                    } else if (data.products.isNotEmpty()) {
                        editingTransaction = TransactionEntity(
                            productId = data.products.first().id, date = java.time.LocalDate.now(),
                            amount = java.math.BigDecimal.ZERO, description = "", kind = TxKind.EXPENSE,
                        )
                    }
                },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text(if (tab == ExplorerTab.HOLDINGS) "Product" else "Transaction") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = contentPadding.calculateBottomPadding() + 20.dp),
            )
        }
    }

    editingProduct?.let { p ->
        ProductEditor(p, data.sources, data.assetClasses, onDismiss = { editingProduct = null }, onSave = { onSaveProduct(it); editingProduct = null }, onDelete = null)
    }
    editingTransaction?.let { t ->
        TransactionEditor(
            t, data.products, data.categories,
            onDismiss = { editingTransaction = null },
            onSave = { onSaveTransaction(it); editingTransaction = null },
            onDelete = if (t.id != 0L) ({ onDeleteTransaction(t); editingTransaction = null }) else null,
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.holdingsItems(data: ExplorerData, onOpenProduct: (Long) -> Unit) {
    val groups = holdings(data)
    if (groups.isEmpty()) {
        item {
            EmptyState(
                "No institutions yet",
                "Add your banks, brokers and wallets in Setup. Products appear under them as you record statements.",
                icon = Icons.Rounded.Inventory2,
            )
        }
        return
    }
    groups.forEach { group ->
        item(key = "s${group.source.id}") {
            Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(group.source.name, style = MaterialTheme.typography.titleLarge)
                    Text(group.source.type.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(MoneyFormat.whole(group.totalEur), style = Figures.medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (group.products.isEmpty()) {
            item(key = "e${group.source.id}") {
                Text("No products yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
            }
        }
        items(group.products, key = { "p${it.product.id}" }) { row -> ProductCard(row) { onOpenProduct(row.product.id) } }
    }
}

@Composable
private fun ProductCard(row: ProductRowUi, onClick: () -> Unit) {
    val color = row.assetClass?.let { seriesColor(it.colorKey) } ?: MaterialTheme.colorScheme.outline
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Monogram(row.product.name, color)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(row.product.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Swatch(color, 8.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        listOfNotNull(row.assetClass?.name, row.product.identifier.ifBlank { null }, if (row.product.archived) "Archived" else null).joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(row.latest?.let { MoneyFormat.full(it.value, row.product.currency) } ?: "No balance", style = Figures.medium)
                Text(row.latest?.let { "as of " + MoneyFormat.date(it.date) } ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val dayHeader = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy")

private fun androidx.compose.foundation.lazy.LazyListScope.transactionItems(rows: List<TransactionRowUi>, onEdit: (TransactionEntity) -> Unit) {
    if (rows.isEmpty()) {
        item { EmptyState("No transactions", "Nothing matches. Statements you import through Chat land here.", icon = Icons.Rounded.Receipt) }
        return
    }
    rows.groupBy { it.transaction.date }.forEach { (date, dayRows) ->
        item(key = "d$date") {
            Text(date.format(dayHeader), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 12.dp))
        }
        items(dayRows, key = { "t${it.transaction.id}" }) { row -> TransactionRow(row) { onEdit(row.transaction) } }
    }
}

@Composable
fun TransactionRow(row: TransactionRowUi, onClick: () -> Unit) {
    val t = row.transaction
    val color = row.category?.let { seriesColor(it.colorKey) } ?: MaterialTheme.colorScheme.outline
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Monogram(t.counterparty.ifBlank { t.description }, color, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.counterparty.ifBlank { t.description }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(row.product?.name, row.category?.name ?: t.kind.label, if (t.transferGroup != null) "linked" else null).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                MoneyFormat.signed(t.amount, row.product?.currency ?: "EUR"),
                style = Figures.medium,
                color = if (t.amount.signum() > 0) StrataTheme.colors.gain else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.importItems(imports: List<ImportEntity>, onUndo: (ImportEntity) -> Unit) {
    if (imports.isEmpty()) {
        item { EmptyState("No imports yet", "Each time you apply a review card in Chat it is logged here, so you can undo it later.", icon = Icons.AutoMirrored.Rounded.Undo) }
        return
    }
    items(imports, key = { "i${it.id}" }) { entry ->
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.summary.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall)
                    Text(
                        listOfNotNull(
                            entry.fileNames.ifBlank { null },
                            Instant.ofEpochMilli(entry.createdAt).atZone(ZoneId.systemDefault()).toLocalDate().let(MoneyFormat::date),
                        ).joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (entry.undoneAt == null) TextButton(onClick = { onUndo(entry) }) { Text("Undo") }
                else Text("Undone", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(end = 8.dp))
            }
        }
    }
}

// ---------- Product detail ----------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProductDetailScreen(
    productId: Long,
    data: ExplorerData,
    onBack: () -> Unit,
    onSaveProduct: (ProductEntity) -> Unit,
    onDeleteProduct: (ProductEntity) -> Unit,
    onSaveSnapshot: (SnapshotEntity) -> Unit,
    onDeleteSnapshot: (SnapshotEntity) -> Unit,
    onSaveTransaction: (TransactionEntity) -> Unit,
    onDeleteTransaction: (TransactionEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val product = data.products.firstOrNull { it.id == productId }
    var showTransactions by rememberSaveable { mutableStateOf(false) }
    var editProduct by remember { mutableStateOf(false) }
    var editingSnapshot by remember { mutableStateOf<SnapshotEntity?>(null) }
    var editingTransaction by remember { mutableStateOf<TransactionEntity?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = { if (product != null) IconButton(onClick = { editProduct = true }) { Icon(Icons.Rounded.Edit, "Edit product") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        floatingActionButton = {
            if (product != null) ExtendedFloatingActionButton(
                onClick = {
                    if (showTransactions) editingTransaction = TransactionEntity(productId = productId, date = java.time.LocalDate.now(), amount = java.math.BigDecimal.ZERO, description = "", kind = TxKind.EXPENSE)
                    else editingSnapshot = SnapshotEntity(productId = productId, date = java.time.LocalDate.now(), value = java.math.BigDecimal.ZERO)
                },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text(if (showTransactions) "Transaction" else "Balance") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        if (product == null) {
            Box(Modifier.padding(padding)) { EmptyState("Product not found", "It may have been deleted.") }
            return@Scaffold
        }
        val assetClass = data.assetClasses.firstOrNull { it.id == product.assetClassId }
        val source = data.sources.firstOrNull { it.id == product.sourceId }
        val snapshots = data.snapshots.filter { it.productId == productId }.sortedByDescending { it.date }
        val transactions = data.transactions.filter { it.productId == productId }
        val categories = data.categories.associateBy { it.id }
        val color = assetClass?.let { seriesColor(it.colorKey) } ?: MaterialTheme.colorScheme.primary

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 4.dp)) {
                    Text(product.name, style = MaterialTheme.typography.headlineLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Swatch(color, 8.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            listOfNotNull(source?.name, assetClass?.name, product.currency, product.identifier.ifBlank { null }).joinToString("  ·  "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(snapshots.firstOrNull()?.let { MoneyFormat.full(it.value, product.currency) } ?: "No balance yet", style = MaterialTheme.typography.displaySmall)
                    snapshots.firstOrNull()?.let {
                        Text("as of ${MoneyFormat.date(it.date)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (snapshots.size >= 2) {
                item {
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.padding(top = 8.dp)) {
                        LineChart(
                            snapshots.reversed().map { LinePoint(it.date, it.value.toDouble()) },
                            color = color, height = 160.dp, modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                    listOf(false to "Balances  ${snapshots.size}", true to "Transactions  ${transactions.size}").forEachIndexed { i, (value, label) ->
                        ToggleButton(
                            checked = showTransactions == value,
                            onCheckedChange = { showTransactions = value },
                            modifier = Modifier.weight(1f),
                            shapes = if (i == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                            colors = ToggleButtonDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                checkedContainerColor = MaterialTheme.colorScheme.primary,
                                checkedContentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) { Text(label) }
                    }
                }
            }
            if (!showTransactions) {
                items(snapshots, key = { "s${it.id}" }) { s ->
                    Surface(onClick = { editingSnapshot = s }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(MoneyFormat.date(s.date), style = MaterialTheme.typography.titleSmall)
                                val detail = listOfNotNull(
                                    s.quantity?.let { q -> "${MoneyFormat.quantity(q)} units" + (s.unitPrice?.let { " at " + MoneyFormat.full(it, product.currency) } ?: "") },
                                    s.note.ifBlank { null },
                                    if (s.importId != null) "imported" else "manual",
                                ).joinToString("  ·  ")
                                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(MoneyFormat.full(s.value, product.currency), style = Figures.medium)
                        }
                    }
                }
            } else {
                items(transactions, key = { "t${it.id}" }) { t ->
                    TransactionRow(TransactionRowUi(t, product, t.spendingCategoryId?.let(categories::get))) { editingTransaction = t }
                }
            }
        }

        if (editProduct) {
            ProductEditor(
                product, data.sources, data.assetClasses,
                onDismiss = { editProduct = false },
                onSave = { onSaveProduct(it); editProduct = false },
                onDelete = { onDeleteProduct(product); editProduct = false; onBack() },
            )
        }
        editingSnapshot?.let { s ->
            SnapshotEditor(
                s, product.currency,
                onDismiss = { editingSnapshot = null },
                onSave = { onSaveSnapshot(it); editingSnapshot = null },
                onDelete = if (s.id != 0L) ({ onDeleteSnapshot(s); editingSnapshot = null }) else null,
            )
        }
        editingTransaction?.let { t ->
            TransactionEditor(
                t, data.products, data.categories,
                onDismiss = { editingTransaction = null },
                onSave = { onSaveTransaction(it); editingTransaction = null },
                onDelete = if (t.id != 0L) ({ onDeleteTransaction(t); editingTransaction = null }) else null,
            )
        }
    }
}

