package com.strata.app.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.strata.app.ai.RunState
import com.strata.app.data.db.ChatEntity
import com.strata.app.data.db.ProposalStatus
import com.strata.app.domain.MoneyFormat
import com.strata.app.ui.components.EmptyState
import com.strata.app.ui.components.MarkdownText
import com.strata.app.ui.components.ScreenTitle
import com.strata.app.ui.theme.Figures
import com.strata.app.ui.theme.StrataTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// ---------- Chat list ----------

@Composable
fun ChatListScreen(
    chats: List<ChatEntity>,
    runs: Map<Long, RunState>,
    onOpen: (Long) -> Unit,
    onNew: () -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    Box(modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(
                start = 12.dp, end = 12.dp,
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 96.dp,
            ),
        ) {
            item {
                ScreenTitle("Chat", subtitle = "Share statements, ask about your money", startPadding = 8.dp)
            }
            if (chats.isEmpty()) {
                item {
                    EmptyState(
                        title = "Start with a statement",
                        body = "Attach a PDF or CSV from any bank or broker. The assistant reads it on your phone, drafts the entries, and you review them before anything is saved.",
                        icon = Icons.Rounded.Forum,
                    )
                }
            }
            items(chats, key = { it.id }) { chat ->
                ChatRow(chat, runs[chat.id], onOpen = { onOpen(chat.id) }, onDelete = { onDelete(chat.id) })
            }
        }
        ExtendedFloatingActionButton(
            onClick = onNew,
            icon = { Icon(Icons.Rounded.Edit, null) },
            text = { Text("New chat") },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = contentPadding.calculateBottomPadding() + 20.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChatRow(chat: ChatEntity, run: RunState?, onOpen: () -> Unit, onDelete: () -> Unit) {
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(chat.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (run?.busy == true) run.progress.orEmpty()
                    else listOfNotNull(relativeTime(chat.updatedAt), chat.costUsd.takeIf { it > 0 }?.let(MoneyFormat::usage)).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (run?.busy == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (run?.busy == true) LoadingIndicator(Modifier.size(32.dp))
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.DeleteOutline, "Delete chat", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private fun relativeTime(millis: Long): String {
    val then = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    val now = java.time.ZonedDateTime.now()
    val days = ChronoUnit.DAYS.between(then.toLocalDate(), now.toLocalDate())
    return when {
        days == 0L -> "Today, " + then.format(DateTimeFormatter.ofPattern("HH:mm"))
        days == 1L -> "Yesterday"
        days < 7 -> then.format(DateTimeFormatter.ofPattern("EEEE"))
        else -> then.format(DateTimeFormatter.ofPattern("d MMM yyyy"))
    }
}

// ---------- Conversation ----------

data class ConversationUi(
    val title: String,
    val items: List<ChatItem>,
    val run: RunState = RunState(),
    val hasApiKey: Boolean = true,
    val model: String = "",
    /** What this chat's model requests have cost so far, in US dollars. */
    val costUsd: Double = 0.0,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    ui: ConversationUi,
    pendingFiles: List<String>,
    onSend: (String) -> Unit,
    onAttach: () -> Unit,
    onRemoveFile: (Int) -> Unit,
    onApply: (ProposalUi) -> Unit,
    onDiscard: (ProposalUi) -> Unit,
    onUndo: (ProposalUi) -> Unit,
    onDismissError: () -> Unit,
    onOpenSetup: () -> Unit,
    onBack: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    initialDraft: String = "",
    expandTraces: Boolean = false,
) {
    val listState = rememberLazyListState()
    val count = ui.items.size + (if (ui.run.busy) 1 else 0)
    LaunchedEffect(count) { if (count > 0) listState.animateScrollToItem(count - 1) }
    // A turn can take minutes; keep the screen awake while it runs so the phone doesn't sleep mid-turn.
    val view = LocalView.current
    DisposableEffect(view, ui.run.busy) {
        view.keepScreenOn = ui.run.busy
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(ui.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val subtitle = listOfNotNull(ui.model.ifEmpty { null }, ui.costUsd.takeIf { it > 0 }?.let(MoneyFormat::usage)).joinToString("  ·  ")
                        if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            Composer(
                pendingFiles = pendingFiles,
                busy = ui.run.busy,
                enabled = ui.hasApiKey,
                onSend = onSend,
                onStop = onStop,
                onAttach = onAttach,
                onRemoveFile = onRemoveFile,
                initialDraft = initialDraft,
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!ui.hasApiKey) item { KeyNotice(onOpenSetup) }
            if (ui.items.isEmpty() && ui.hasApiKey) item { ConversationHint() }
            items(ui.items, key = { it.key }) { item ->
                when (item) {
                    is ChatItem.User -> UserBubble(item)
                    is ChatItem.Activity -> ActivityCard(item, initiallyExpanded = expandTraces)
                    is ChatItem.Assistant -> AssistantBlock(item, ui.run.busy, onApply, onDiscard, onUndo)
                }
            }
            if (ui.run.busy) item(key = "running") { RunningRow(ui.run) }
            ui.run.error?.let { error -> item(key = "error") { ErrorCard(error, onDismissError) } }
        }
    }
}

@Composable
private fun ConversationHint() {
    Column(Modifier.padding(top = 24.dp, bottom = 8.dp)) {
        Text("What should we look at?", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Attach a statement and say what it is, or ask something like “how much did I spend on dining since June?”",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UserBubble(item: ChatItem.User) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        item.attachments.forEach { a ->
            FileChip(a.name, if (a.pages > 0) "${a.pages} page${if (a.pages == 1) "" else "s"}" else null)
            Spacer(Modifier.height(6.dp))
        }
        if (item.text.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 8.dp),
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Text(item.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp))
            }
        }
    }
}

@Composable
private fun FileChip(name: String, detail: String?, onRemove: (() -> Unit)? = null) {
    Row(
        Modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 12.dp, end = if (onRemove != null) 4.dp else 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Description, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.widthIn(max = 220.dp)) {
            Text(name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f))
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.Close, "Remove $name", tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun AssistantBlock(item: ChatItem.Assistant, busy: Boolean, onApply: (ProposalUi) -> Unit, onDiscard: (ProposalUi) -> Unit, onUndo: (ProposalUi) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (item.text.isNotBlank()) MarkdownText(item.text, Modifier.padding(end = 12.dp))
        item.proposal?.let {
            Spacer(Modifier.height(12.dp))
            ProposalCard(it, onApply, onDiscard, onUndo, locked = busy)
        }
    }
}

/** [locked] while a turn runs: a pending card carries into it and may still change, so it can't be applied yet. */
@Composable
fun ProposalCard(p: ProposalUi, onApply: (ProposalUi) -> Unit, onDiscard: (ProposalUi) -> Unit, onUndo: (ProposalUi) -> Unit, locked: Boolean = false) {
    var expanded by rememberSaveable(p.messageId) { mutableStateOf(false) }
    val pending = p.status == ProposalStatus.PENDING
    val accent = when (p.status) {
        ProposalStatus.PENDING -> MaterialTheme.colorScheme.primary
        ProposalStatus.APPLIED -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .border(if (pending) 2.dp else 1.dp, if (pending) accent else MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
            .animateContentSize(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when (p.status) {
                            ProposalStatus.PENDING -> "Ready to review"
                            ProposalStatus.APPLIED -> "Applied"
                            ProposalStatus.DISCARDED -> "Discarded"
                            ProposalStatus.UNDONE -> "Undone"
                            ProposalStatus.SUPERSEDED -> "Replaced by a later card"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = accent,
                    )
                    Text(p.headline, style = MaterialTheme.typography.titleLarge)
                }
            }
            // The later card holds the same rows, corrected; repeating them here would only confuse.
            if (p.status == ProposalStatus.SUPERSEDED) return@Column
            Spacer(Modifier.height(10.dp))
            if (pending && p.rewrites != null) RewritesBanner(p.rewrites)
            if (p.warnings.isNotEmpty()) CheckWarnings(p.warnings)
            p.groups.forEach { group ->
                if (group.kind != GroupKind.ADD) {
                    RecordedChangesGroup(group)
                    return@forEach
                }
                Text(group.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                val shown = if (expanded) group.lines else group.lines.take(3)
                shown.forEach { line -> ProposalRow(line) }
                if (!expanded && group.lines.size > 3) {
                    Text(
                        "and ${group.lines.size - 3} more",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (p.groups.any { it.kind == GroupKind.ADD && it.lines.size > 3 }) {
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Show all") }
                }
                Spacer(Modifier.weight(1f))
                when (p.status) {
                    ProposalStatus.PENDING -> {
                        TextButton(onClick = { onDiscard(p) }, enabled = !locked) { Text("Discard") }
                        Button(onClick = { onApply(p) }, enabled = !locked) { Text(if (p.rewrites != null) "Apply corrections" else "Apply") }
                    }
                    ProposalStatus.APPLIED -> if (p.importId != null) {
                        FilledTonalButton(onClick = { onUndo(p) }) {
                            Icon(Icons.AutoMirrored.Rounded.Undo, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Undo import")
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}

/** Says up front that this card changes data already recorded, not only adds to it. */
@Composable
private fun RewritesBanner(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Edit, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Changes your recorded data", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

/** Corrections and removals of recorded rows: framed in the caution colour, and never folded behind "Show all". */
@Composable
private fun RecordedChangesGroup(group: ProposalGroup) {
    val accent = MaterialTheme.colorScheme.secondary
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(MaterialTheme.shapes.medium)
            .border(1.5.dp, accent, MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (group.kind == GroupKind.REMOVE) Icons.Rounded.DeleteOutline else Icons.Rounded.Edit, null, tint = accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(group.title, style = MaterialTheme.typography.titleSmall, color = accent)
        }
        group.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)) }
        group.lines.forEach { line -> ProposalRow(line, removal = group.kind == GroupKind.REMOVE) }
    }
}

@Composable
private fun CheckWarnings(warnings: List<String>) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Check before applying", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            warnings.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer) }
        }
    }
}

@Composable
private fun ProposalRow(line: ProposalLine, removal: Boolean = false) {
    val colors = StrataTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(line.primary, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line.secondary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (line.previous != null || line.amount != null) Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            // The recorded value, struck through above what replaces it (or above "Removed").
            line.previous?.let {
                Text(
                    it,
                    style = (if (removal) Figures.medium else Figures.small).copy(textDecoration = TextDecoration.LineThrough),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (removal) {
                Text("Removed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
            }
            line.amount?.let {
                Text(
                    it,
                    style = if (line.previous != null) Figures.medium.copy(fontWeight = FontWeight.SemiBold) else Figures.medium,
                    color = when {
                        line.previous != null -> MaterialTheme.colorScheme.secondary
                        line.tone == Tone.IN -> colors.gain
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

@Composable
private fun KeyNotice(onOpenSetup: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Key, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(10.dp))
                Text("Connect OpenRouter", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Add your API key and pick a model in Setup. The key is stored inside the encrypted database.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onOpenSetup) { Text("Open Setup") }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(10.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Dismiss", tint = MaterialTheme.colorScheme.onErrorContainer) }
        }
    }
}

@Composable
private fun Composer(
    pendingFiles: List<String>,
    busy: Boolean,
    enabled: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onRemoveFile: (Int) -> Unit,
    initialDraft: String,
) {
    var text by rememberSaveable { mutableStateOf(initialDraft) }
    val canSend = enabled && !busy && (text.isNotBlank() || pendingFiles.isNotEmpty())
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (pendingFiles.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    pendingFiles.forEachIndexed { i, name -> FileChip(name, null, onRemove = { onRemoveFile(i) }) }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                FilledIconButton(
                    onClick = onAttach,
                    enabled = enabled && !busy,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    modifier = Modifier.size(52.dp),
                ) { Icon(Icons.Rounded.AttachFile, "Attach a file") }
                Spacer(Modifier.width(8.dp))
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Message") },
                    enabled = enabled,
                    maxLines = 6,
                    shape = RoundedCornerShape(26.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                if (busy) {
                    // While a turn runs the send button becomes Stop, which cancels the request mid-flight.
                    FilledIconButton(
                        onClick = onStop,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        modifier = Modifier.size(52.dp),
                    ) { Icon(Icons.Rounded.Stop, "Stop") }
                } else {
                    FilledIconButton(
                        onClick = { onSend(text.trim()); text = "" },
                        enabled = canSend,
                        modifier = Modifier.size(52.dp),
                    ) { Icon(Icons.AutoMirrored.Rounded.Send, "Send") }
                }
            }
        }
    }
}
