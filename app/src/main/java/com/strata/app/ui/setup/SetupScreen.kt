package com.strata.app.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.strata.app.ai.ModelInfo
import com.strata.app.data.db.AssetClassEntity
import com.strata.app.data.db.FlowKind
import com.strata.app.data.db.SourceEntity
import com.strata.app.data.db.SourceType
import com.strata.app.data.db.SpendingCategoryEntity
import com.strata.app.ui.components.Dropdown
import com.strata.app.ui.components.EditorSheet
import com.strata.app.ui.components.FormField
import com.strata.app.ui.components.ListRow
import com.strata.app.ui.components.Monogram
import com.strata.app.ui.components.Panel
import com.strata.app.ui.components.ScreenTitle
import com.strata.app.ui.components.Swatch
import com.strata.app.ui.theme.SeriesPalette
import com.strata.app.ui.theme.StrataTheme
import com.strata.app.ui.theme.seriesColor

@Composable
fun SetupScreen(
    lists: SetupLists,
    ai: AiSettingsUi,
    onSaveSource: (SourceEntity) -> Unit,
    onDeleteSource: (SourceEntity) -> Unit,
    onSaveAssetClass: (AssetClassEntity) -> Unit,
    onDeleteAssetClass: (AssetClassEntity) -> Unit,
    onSaveCategory: (SpendingCategoryEntity) -> Unit,
    onDeleteCategory: (SpendingCategoryEntity) -> Unit,
    onAddSuggestedClasses: () -> Unit,
    onAddSuggestedCategories: () -> Unit,
    onSaveKey: (String) -> Unit,
    onPickModel: (String) -> Unit,
    onLoadModels: () -> Unit,
    onPrivateOnly: (Boolean) -> Unit,
    onTest: () -> Unit,
    onExport: (CharArray) -> Unit,
    onRestore: (CharArray) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    var editSource by remember { mutableStateOf<SourceEntity?>(null) }
    var editClass by remember { mutableStateOf<AssetClassEntity?>(null) }
    var editCategory by remember { mutableStateOf<SpendingCategoryEntity?>(null) }
    var keyDialog by remember { mutableStateOf(false) }
    var modelSheet by remember { mutableStateOf(false) }
    var passphraseFor by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ScreenTitle("Setup", subtitle = "The structure your data lives in. Only you change it.", startPadding = 4.dp) }

        item {
            Panel(title = "Institutions", trailing = { AddButton("Add institution") { editSource = SourceEntity(name = "", type = SourceType.BANK) } }) {
                if (lists.sources.isEmpty()) Hint("Banks, brokers, exchanges, wallets and employers. Every product belongs to one.")
                lists.sources.forEach { s ->
                    ListRow(
                        s.name, supporting = listOfNotNull(s.type.label, s.notes.ifBlank { null }).joinToString("  ·  "),
                        leading = { Monogram(s.name, MaterialTheme.colorScheme.primary) },
                        onClick = { editSource = s },
                    )
                }
            }
        }

        item {
            Panel(title = "Asset classes", trailing = { AddButton("Add asset class") { editClass = AssetClassEntity(name = "", colorKey = nextColor(lists.assetClasses.map { it.colorKey }), sortOrder = lists.assetClasses.size) } }) {
                if (lists.assetClasses.isEmpty()) {
                    Hint("How your holdings are grouped on the dashboard. Liability classes, like credit, count against net worth.")
                    Spacer(Modifier.height(10.dp))
                    FilledTonalButton(onClick = onAddSuggestedClasses) { Text("Add a starter set") }
                }
                lists.assetClasses.forEach { c ->
                    ListRow(
                        c.name, supporting = if (c.isLiability) "Liability" else null,
                        leading = { ColorBadge(c.colorKey) },
                        onClick = { editClass = c },
                    )
                }
            }
        }

        item {
            Panel(title = "Spending categories", trailing = { AddButton("Add category") { editCategory = SpendingCategoryEntity(name = "", kind = FlowKind.EXPENSE, colorKey = nextColor(lists.categories.map { it.colorKey })) } }) {
                if (lists.categories.isEmpty()) {
                    Hint("Where income comes from and where spending goes. The assistant can only use categories from this list.")
                    Spacer(Modifier.height(10.dp))
                    FilledTonalButton(onClick = onAddSuggestedCategories) { Text("Add a starter set") }
                }
                val (expenses, income) = lists.categories.partition { it.kind == FlowKind.EXPENSE }
                if (expenses.isNotEmpty()) CategoryChips("Spending", expenses) { editCategory = it }
                if (income.isNotEmpty()) CategoryChips("Income", income) { editCategory = it }
            }
        }

        item { AiPanel(ai, onEditKey = { keyDialog = true }, onPickModel = { onLoadModels(); modelSheet = true }, onPrivateOnly = onPrivateOnly, onTest = onTest) }

        item {
            Panel(title = "Privacy and backup") {
                InfoLine(Icons.Rounded.Lock, "Encrypted database", "Everything, including your API key, is stored with SQLCipher (AES-256).")
                InfoLine(Icons.Rounded.Fingerprint, "Biometric key", "The database key sits in the phone's security chip and only unlocks after your fingerprint, face or PIN.")
                InfoLine(Icons.Rounded.Shield, "Stays on the phone", "No cloud backup, screenshots are blocked. Only document text you send in Chat leaves the device.")
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { passphraseFor = "export" }) {
                        Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Export backup")
                    }
                    OutlinedButton(onClick = { passphraseFor = "restore" }) {
                        Icon(Icons.Rounded.Upload, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Restore")
                    }
                }
            }
        }
    }

    editSource?.let { s ->
        SourceEditor(s, onDismiss = { editSource = null }, onSave = { onSaveSource(it); editSource = null }, onDelete = if (s.id != 0L) ({ onDeleteSource(s); editSource = null }) else null)
    }
    editClass?.let { c ->
        AssetClassEditor(c, onDismiss = { editClass = null }, onSave = { onSaveAssetClass(it); editClass = null }, onDelete = if (c.id != 0L) ({ onDeleteAssetClass(c); editClass = null }) else null)
    }
    editCategory?.let { c ->
        CategoryEditor(c, onDismiss = { editCategory = null }, onSave = { onSaveCategory(it); editCategory = null }, onDelete = if (c.id != 0L) ({ onDeleteCategory(c); editCategory = null }) else null)
    }
    if (keyDialog) KeyDialog(onDismiss = { keyDialog = false }, onSave = { onSaveKey(it); keyDialog = false })
    if (modelSheet) ModelSheet(ai, onDismiss = { modelSheet = false }, onPick = { onPickModel(it); modelSheet = false })
    passphraseFor?.let { mode ->
        PassphraseDialog(
            restoring = mode == "restore",
            onDismiss = { passphraseFor = null },
            onConfirm = { pass -> passphraseFor = null; if (mode == "export") onExport(pass) else onRestore(pass) },
        )
    }
}

private fun nextColor(used: List<String>): String =
    SeriesPalette.swatches.firstOrNull { it.key !in used }?.key ?: SeriesPalette.swatches.first().key

@Composable
private fun AddButton(description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(Icons.Rounded.Add, description, tint = MaterialTheme.colorScheme.primary) }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ColorBadge(key: String) {
    Box(Modifier.size(40.dp).clip(MaterialTheme.shapes.small).background(seriesColor(key)))
}

@Composable
private fun CategoryChips(title: String, categories: List<SpendingCategoryEntity>, onClick: (SpendingCategoryEntity) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
        categories.forEach { c ->
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onClick(c) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Swatch(seriesColor(c.colorKey))
                Spacer(Modifier.width(8.dp))
                Text(c.name, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun InfoLine(icon: ImageVector, title: String, body: String) {
    Row(Modifier.padding(vertical = 8.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp).size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AiPanel(ai: AiSettingsUi, onEditKey: () -> Unit, onPickModel: () -> Unit, onPrivateOnly: (Boolean) -> Unit, onTest: () -> Unit) {
    Panel(title = "Assistant") {
        SettingRow("OpenRouter key", ai.keyTail?.let { "Saved, ending in $it" } ?: "Not set", if (ai.keyTail == null) "Add" else "Replace", onEditKey)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 4.dp))
        SettingRow("Model", ai.model, "Change", onPickModel)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 4.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Private providers only", style = MaterialTheme.typography.titleSmall)
                Text("Route only to providers that don't store or train on your prompts.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Switch(ai.privateOnly, onPrivateOnly)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = onTest, enabled = !ai.testing) { Text("Test connection") }
            Spacer(Modifier.width(12.dp))
            if (ai.testing) LoadingIndicator(Modifier.size(36.dp))
        }
        ai.testResult?.let {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Swatch(if (ai.testOk) StrataTheme.colors.gain else MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(10.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, value: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onClick) { Text(action) }
    }
}

// ---------- Editors and dialogs ----------

@Composable
internal fun ColorPicker(selected: String, onSelect: (String) -> Unit) {
    Text("Colour", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SeriesPalette.swatches.forEach { swatch ->
            val isSelected = swatch.key == selected
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .then(if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                    .padding(if (isSelected) 5.dp else 0.dp)
                    .clip(CircleShape)
                    .background(seriesColor(swatch.key))
                    .clickable { onSelect(swatch.key) },
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) Icon(Icons.Rounded.Check, swatch.label, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun DeleteButton(onDelete: (() -> Unit)?) {
    if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
}

@Composable
fun SourceEditor(initial: SourceEntity, onDismiss: () -> Unit, onSave: (SourceEntity) -> Unit, onDelete: (() -> Unit)?) {
    var name by remember { mutableStateOf(initial.name) }
    var type by remember { mutableStateOf(initial.type) }
    var notes by remember { mutableStateOf(initial.notes) }
    EditorSheet(
        if (initial.id == 0L) "New institution" else "Edit institution", onDismiss,
        actions = {
            DeleteButton(onDelete)
            Button(enabled = name.isNotBlank(), onClick = { onSave(initial.copy(name = name, type = type, notes = notes.trim())) }) { Text("Save") }
        },
    ) {
        FormField(name, { name = it }, "Name")
        Dropdown("Type", SourceType.entries, type, { it.label }, { type = it })
        FormField(notes, { notes = it }, "Notes for the assistant", supporting = "Optional, for example which statements it sends", singleLine = false)
    }
}

@Composable
fun AssetClassEditor(initial: AssetClassEntity, onDismiss: () -> Unit, onSave: (AssetClassEntity) -> Unit, onDelete: (() -> Unit)?) {
    var name by remember { mutableStateOf(initial.name) }
    var color by remember { mutableStateOf(initial.colorKey) }
    var liability by remember { mutableStateOf(initial.isLiability) }
    EditorSheet(
        if (initial.id == 0L) "New asset class" else "Edit asset class", onDismiss,
        actions = {
            DeleteButton(onDelete)
            Button(enabled = name.isNotBlank(), onClick = { onSave(initial.copy(name = name, colorKey = color, isLiability = liability)) }) { Text("Save") }
        },
    ) {
        FormField(name, { name = it }, "Name")
        ColorPicker(color) { color = it }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Liability", style = MaterialTheme.typography.titleSmall)
                Text("Balances are amounts owed and reduce net worth.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(liability, { liability = it })
        }
    }
}

@Composable
fun CategoryEditor(initial: SpendingCategoryEntity, onDismiss: () -> Unit, onSave: (SpendingCategoryEntity) -> Unit, onDelete: (() -> Unit)?) {
    var name by remember { mutableStateOf(initial.name) }
    var color by remember { mutableStateOf(initial.colorKey) }
    var kind by remember { mutableStateOf(initial.kind) }
    EditorSheet(
        if (initial.id == 0L) "New category" else "Edit category", onDismiss,
        actions = {
            DeleteButton(onDelete)
            Button(enabled = name.isNotBlank(), onClick = { onSave(initial.copy(name = name, colorKey = color, kind = kind)) }) { Text("Save") }
        },
    ) {
        FormField(name, { name = it }, "Name")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            FlowKind.entries.forEachIndexed { i, k ->
                SegmentedButton(
                    selected = kind == k,
                    onClick = { kind = k },
                    shape = SegmentedButtonDefaults.itemShape(i, FlowKind.entries.size),
                ) { Text(if (k == FlowKind.EXPENSE) "Spending" else "Income") }
            }
        }
        ColorPicker(color) { color = it }
    }
}

@Composable
private fun KeyDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("OpenRouter API key") },
        text = {
            Column {
                Text("Create one at openrouter.ai under Keys. It is stored only in the encrypted database.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = key, onValueChange = { key = it.trim() }, singleLine = true,
                    placeholder = { Text("sk-or-…") },
                    visualTransformation = PasswordVisualTransformation(),
                    shape = MaterialTheme.shapes.small,
                )
            }
        },
        confirmButton = { Button(enabled = key.length > 10, onClick = { onSave(key) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ModelSheet(ai: AiSettingsUi, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var custom by remember { mutableStateOf(ai.model) }
    EditorSheet(
        "Choose a model", onDismiss,
        actions = { Button(enabled = custom.contains('/'), onClick = { onPick(custom) }) { Text("Use this ID") } },
    ) {
        FormField(custom, { custom = it.trim() }, "Model ID", supporting = "Any OpenRouter model that supports tool calls")
        FormField(query, { query = it }, "Search models")
        when {
            ai.modelsLoading -> Text("Loading models…", style = MaterialTheme.typography.bodyMedium)
            ai.modelsError != null -> Text(ai.modelsError, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        val shown = ai.models.filter { query.isBlank() || it.id.contains(query, true) || it.name.contains(query, true) }.take(40)
        Column(Modifier.heightIn(max = 420.dp)) {
            LazyColumn {
                items(shown, key = { it.id }) { m -> ModelRow(m, m.id == ai.model) { onPick(m.id) } }
            }
        }
    }
}

@Composable
private fun ModelRow(m: ModelInfo, selected: Boolean, onClick: () -> Unit) {
    ListRow(
        m.name,
        supporting = listOfNotNull(m.id, m.promptPricePerMillion?.let { "$" + String.format(java.util.Locale.US, "%.2f", it) + " per M input" }).joinToString("  ·  "),
        trailing = if (selected) ({ Icon(Icons.Rounded.Check, "Selected", tint = MaterialTheme.colorScheme.primary) }) else null,
        onClick = onClick,
    )
}

@Composable
private fun PassphraseDialog(restoring: Boolean, onDismiss: () -> Unit, onConfirm: (CharArray) -> Unit) {
    var pass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = pass.length >= 8 && (restoring || pass == confirm)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (restoring) "Restore a backup" else "Protect your backup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (restoring) "Restoring replaces every institution, product and transaction on this phone with the backup's contents."
                    else "Choose a passphrase of at least 8 characters. Without it the file cannot be opened, by anyone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                androidx.compose.material3.OutlinedTextField(pass, { pass = it }, label = { Text("Passphrase") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), shape = MaterialTheme.shapes.small)
                if (!restoring) androidx.compose.material3.OutlinedTextField(confirm, { confirm = it }, label = { Text("Repeat passphrase") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), shape = MaterialTheme.shapes.small)
            }
        },
        confirmButton = { Button(enabled = valid, onClick = { onConfirm(pass.toCharArray()) }) { Text(if (restoring) "Choose file" else "Save file") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

