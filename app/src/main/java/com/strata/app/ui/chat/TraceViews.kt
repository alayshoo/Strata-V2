package com.strata.app.ui.chat

import android.content.ClipData
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.strata.app.ai.RunState
import com.strata.app.ui.theme.Figures
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** Long payloads are cut in the view; Copy trace always copies everything. */
private const val MAX_SHOWN_CHARS = 3_000

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s < 60) String.format(Locale.US, "%.1f s", ms / 1000.0) else "${s / 60}:${String.format(Locale.US, "%02d", s % 60)}"
}

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "${s / 60}:${String.format(Locale.US, "%02d", s % 60)}"
}

private fun tokens(n: Int) = if (n >= 1000) String.format(Locale.US, "%.1fk", n / 1000.0) else n.toString()

fun roundSummary(r: TraceRound): String = listOfNotNull(
    r.round?.let { "Round $it" },
    r.durationMs?.let(::formatDuration),
    r.promptTokens?.let { "${tokens(it)} in" },
    r.completionTokens?.let { "${tokens(it)} out" },
).joinToString("  ·  ").ifEmpty { "Round" }

/** Plain-text dump of a turn, for pasting into a bug report or another chat. */
fun traceText(item: ChatItem.Activity): String = buildString {
    item.rounds.forEach { r ->
        appendLine("== ${roundSummary(r)}")
        r.reasoning?.let { appendLine("-- reasoning\n$it") }
        r.note?.let { appendLine("-- text\n$it") }
        r.calls.forEach { c ->
            appendLine("-- ${c.name}${if (c.isError) "  [error]" else ""}")
            appendLine("arguments: ${c.arguments}")
            appendLine("result: ${c.result ?: "(none)"}")
        }
        appendLine()
    }
}

/** One line per turn that opens into a round-by-round trace of what the model did. */
@Composable
fun ActivityCard(item: ChatItem.Activity, initiallyExpanded: Boolean = false) {
    var expanded by rememberSaveable(item.key) { mutableStateOf(initiallyExpanded) }
    val hasErrors = item.errorCount > 0
    val summary = buildList {
        if (item.callCount > 0) add("${item.callCount} tool call${if (item.callCount == 1) "" else "s"}")
        if (item.rounds.isNotEmpty()) add("${item.rounds.size} round${if (item.rounds.size == 1) "" else "s"}")
        if (item.totalMs > 0) add(formatDuration(item.totalMs))
        if (hasErrors) add("${item.errorCount} error${if (item.errorCount == 1) "" else "s"}")
    }.joinToString("  ·  ")

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (expanded) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent,
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { expanded = !expanded }.padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(hasErrors)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    if (summary.isNotEmpty()) Text(summary, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                    if (item.steps.isNotEmpty() && !expanded) {
                        Text(
                            item.steps.joinToString("  ·  "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Hide details" else "Show details",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) TraceBody(item)
        }
    }
}

@Composable
private fun StatusDot(error: Boolean) {
    Box(
        Modifier.size(22.dp).clip(CircleShape).background(if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (error) Icons.Rounded.PriorityHigh else Icons.Rounded.Check, null,
            tint = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun TraceBody(item: ChatItem.Activity) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    SelectionContainer {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item.rounds.forEachIndexed { i, r ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(roundSummary(r), style = Figures.small, color = MaterialTheme.colorScheme.primary)
                r.reasoning?.let { Expandable("Reasoning", it, italic = true) }
                r.note?.let { Expandable("Said", it) }
                r.calls.forEach { c -> CallBlock(c) }
                if (r.calls.isEmpty() && r.reasoning == null) {
                    Text("Answered without tools.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    TextButton(
        onClick = {
            scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Strata trace", traceText(item))))
                copied = true
            }
        },
        modifier = Modifier.padding(start = 2.dp),
    ) {
        Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (copied) "Copied" else "Copy trace")
    }
}

@Composable
private fun CallBlock(c: TraceCall) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            Text(c.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Payload("Arguments", c.arguments, error = false)
        Payload("Result", c.result ?: "No result recorded", error = c.isError)
    }
}

@Composable
private fun Payload(label: String, text: String, error: Boolean) {
    var full by remember { mutableStateOf(false) }
    val shown = if (full || text.length <= MAX_SHOWN_CHARS) text else text.take(MAX_SHOWN_CHARS)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            label, style = MaterialTheme.typography.labelSmall,
            color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            shown,
            style = MaterialTheme.typography.bodySmall,
            color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
            maxLines = if (full) Int.MAX_VALUE else 14,
            overflow = TextOverflow.Ellipsis,
        )
        if (!full && (text.length > MAX_SHOWN_CHARS || text.lines().size > 14)) {
            Text(
                "Show all ${text.length} characters",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp).clickable { full = true },
            )
        }
    }
}

@Composable
private fun Expandable(label: String, text: String, italic: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.let { if (italic) it.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic) else it },
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (open) Int.MAX_VALUE else 6,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable { open = !open },
        )
    }
}

/** Live status of a running turn with timers, so a slow model is visible rather than a silent spinner. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RunningRow(run: RunState) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(run.since) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val onStep = now - run.since
    Row(verticalAlignment = Alignment.CenterVertically) {
        LoadingIndicator(Modifier.size(40.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(run.progress.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                listOfNotNull(
                    run.round.takeIf { it > 0 }?.let { "Round $it" },
                    "${clock(onStep)} on this step",
                    run.startedAt.takeIf { it > 0 && it != run.since }?.let { "${clock(now - it)} total" },
                ).joinToString("  ·  "),
                style = Figures.small,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onStep > SLOW_AFTER_MS) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "This is taking a while. Large files make every round slower; you can stop and try a faster model.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val SLOW_AFTER_MS = 90_000L
