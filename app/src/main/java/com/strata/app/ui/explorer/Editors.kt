package com.strata.app.ui.explorer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.strata.app.data.db.AssetClassEntity
import com.strata.app.data.db.FlowKind
import com.strata.app.data.db.ProductEntity
import com.strata.app.data.db.SnapshotEntity
import com.strata.app.data.db.SourceEntity
import com.strata.app.data.db.SpendingCategoryEntity
import com.strata.app.data.db.TransactionEntity
import com.strata.app.data.db.TxKind
import com.strata.app.ui.components.DateField
import com.strata.app.ui.components.Dropdown
import com.strata.app.ui.components.EditorSheet
import com.strata.app.ui.components.FormField
import com.strata.app.ui.components.Swatch
import com.strata.app.ui.components.parseAmount
import com.strata.app.ui.theme.seriesColor

@Composable
fun ProductEditor(
    initial: ProductEntity,
    sources: List<SourceEntity>,
    classes: List<AssetClassEntity>,
    onDismiss: () -> Unit,
    onSave: (ProductEntity) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(initial.name) }
    var source by remember { mutableStateOf(sources.firstOrNull { it.id == initial.sourceId }) }
    var assetClass by remember { mutableStateOf(classes.firstOrNull { it.id == initial.assetClassId }) }
    var currency by remember { mutableStateOf(initial.currency) }
    var identifier by remember { mutableStateOf(initial.identifier) }
    var archived by remember { mutableStateOf(initial.archived) }
    var confirmDelete by remember { mutableStateOf(false) }
    val valid = name.isNotBlank() && source != null && assetClass != null && Regex("[A-Za-z]{3,5}").matches(currency.trim())

    EditorSheet(
        title = if (initial.id == 0L) "New product" else "Edit product",
        onDismiss = onDismiss,
        actions = {
            if (onDelete != null) {
                TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) {
                    Text(if (confirmDelete) "Tap again to delete everything" else "Delete", color = MaterialTheme.colorScheme.error)
                }
            }
            Button(enabled = valid, onClick = {
                onSave(initial.copy(name = name.trim(), sourceId = source!!.id, assetClassId = assetClass!!.id, currency = currency.trim().uppercase(), identifier = identifier.trim(), archived = archived))
            }) { Text("Save") }
        },
    ) {
        FormField(name, { name = it }, "Name", supporting = "For example Current account, VWCE or Visa card")
        Dropdown("Institution", sources, source, { it.name }, { source = it })
        Dropdown("Asset class", classes, assetClass, { it.name }, { assetClass = it }, leading = { Swatch(seriesColor(it.colorKey)) })
        FormField(currency, { currency = it.take(5) }, "Currency", supporting = "ISO code such as EUR or USD")
        FormField(identifier, { identifier = it }, "Identifier", supporting = "IBAN ending, ISIN or ticker, helps match statements")
        if (initial.id != 0L) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Archived", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(archived, { archived = it })
            }
        }
    }
}

@Composable
fun SnapshotEditor(
    initial: SnapshotEntity,
    currency: String,
    onDismiss: () -> Unit,
    onSave: (SnapshotEntity) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var date by remember { mutableStateOf(initial.date) }
    var value by remember { mutableStateOf(if (initial.id == 0L) "" else initial.value.toPlainString()) }
    var quantity by remember { mutableStateOf(initial.quantity?.toPlainString().orEmpty()) }
    var price by remember { mutableStateOf(initial.unitPrice?.toPlainString().orEmpty()) }
    var note by remember { mutableStateOf(initial.note) }
    val parsed = parseAmount(value)

    EditorSheet(
        title = if (initial.id == 0L) "Record a balance" else "Edit balance",
        onDismiss = onDismiss,
        actions = {
            if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            Button(enabled = parsed != null, onClick = {
                onSave(initial.copy(date = date, value = parsed!!, quantity = parseAmount(quantity), unitPrice = parseAmount(price), note = note.trim()))
            }) { Text("Save") }
        },
    ) {
        DateField("Date", date, { date = it })
        FormField(value, { value = it }, "Value in $currency", numeric = true, isError = value.isNotBlank() && parsed == null)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FormField(quantity, { quantity = it }, "Units", numeric = true, modifier = Modifier.weight(1f))
            FormField(price, { price = it }, "Unit price", numeric = true, modifier = Modifier.weight(1f))
        }
        FormField(note, { note = it }, "Note")
    }
}

@Composable
fun TransactionEditor(
    initial: TransactionEntity,
    products: List<ProductEntity>,
    categories: List<SpendingCategoryEntity>,
    onDismiss: () -> Unit,
    onSave: (TransactionEntity) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var product by remember { mutableStateOf(products.firstOrNull { it.id == initial.productId }) }
    var date by remember { mutableStateOf(initial.date) }
    var amount by remember { mutableStateOf(if (initial.id == 0L) "" else initial.amount.toPlainString()) }
    var description by remember { mutableStateOf(initial.description) }
    var counterparty by remember { mutableStateOf(initial.counterparty) }
    var kind by remember { mutableStateOf(initial.kind) }
    var category by remember { mutableStateOf(categories.firstOrNull { it.id == initial.spendingCategoryId }) }
    val parsed = parseAmount(amount)
    val takesCategory = kind == TxKind.EXPENSE || kind == TxKind.INCOME
    val wanted = if (kind == TxKind.INCOME) FlowKind.INCOME else FlowKind.EXPENSE
    val categoryOptions = categories.filter { it.kind == wanted }

    EditorSheet(
        title = if (initial.id == 0L) "New transaction" else "Edit transaction",
        onDismiss = onDismiss,
        actions = {
            if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            Button(enabled = parsed != null && product != null && description.isNotBlank(), onClick = {
                onSave(
                    initial.copy(
                        productId = product!!.id, date = date, amount = parsed!!, description = description.trim(),
                        counterparty = counterparty.trim(), kind = kind,
                        spendingCategoryId = category?.id?.takeIf { takesCategory && category?.kind == wanted },
                    )
                )
            }) { Text("Save") }
        },
    ) {
        Dropdown("Product", products, product, { it.name + "  ·  " + it.currency }, { product = it })
        Dropdown("Kind", TxKind.entries, kind, { it.label }, { kind = it })
        DateField("Date", date, { date = it })
        FormField(amount, { amount = it }, "Amount", supporting = "Negative when money leaves this product", numeric = true, isError = amount.isNotBlank() && parsed == null)
        FormField(description, { description = it }, "Description")
        FormField(counterparty, { counterparty = it }, "Payee or payer")
        if (takesCategory) {
            Dropdown("Category", categoryOptions, category?.takeIf { it.kind == wanted }, { it.name }, { category = it }, leading = { Swatch(seriesColor(it.colorKey)) })
        }
    }
}
