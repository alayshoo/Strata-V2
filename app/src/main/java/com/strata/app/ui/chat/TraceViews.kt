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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.strata.app.ai.RunState
import com.strata.app.domain.MoneyFormat
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
    r.cost?.let(MoneyFormat::usage),
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

/**
 * One line per turn. It opens into a card per round; a round opens into what the model wrote, with
 * reasoning and tool calls behind their own toggles, and each tool call opens into its arguments and result.
 * [initiallyExpanded] opens every level, for previews.
 */
@Composable
fun ActivityCard(item: ChatItem.Activity, initiallyExpanded: Boolean = false) {
    var expanded by rememberSaveable(item.key) { mutableStateOf(initiallyExpanded) }
    val hasErrors = item.errorCount > 0
    val summary = buildList {
        if (item.rounds.isNotEmpty()) add("${item.rounds.size} round${if (item.rounds.size == 1) "" else "s"}")
        if (item.callCount > 0) add("${item.callCount} tool call${if (item.callCount == 1) "" else "s"}")
        if (item.totalMs > 0) add(formatDuration(item.totalMs))
        item.totalCost?.let { add(MoneyFormat.usage(it)) }
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
                Chevron(expanded, "details")
            }
            if (expanded) {
                Column(Modifier.padding(start = 8.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item.rounds.forEachIndexed { i, r -> RoundCard(r, "${item.key}-r$i", initiallyExpanded) }
                }
                CopyTraceButton(item)
            }
        }
    }
}

@Composable
private fun RoundCard(r: TraceRound, key: String, expandAll: Boolean) {
    var open by rememberSaveable(key) { mutableStateOf(expandAll) }
    val errors = r.calls.count { it.isError }
    val metrics = listOfNotNull(
        r.durationMs?.let(::formatDuration),
        r.promptTokens?.let { "${tokens(it)} in" },
        r.completionTokens?.let { "${tokens(it)} out" },
        r.cost?.let(MoneyFormat::usage),
    ).joinToString("  ·  ")
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.round?.let { "Round $it" } ?: "Round", style = MaterialTheme.typography.titleSmall)
                        if (metrics.isNotEmpty()) {
                            Spacer(Modifier.width(10.dp))
                            Text(metrics, style = Figures.small, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (!open) {
                        val steps = r.calls.map { it.label }.distinct().joinToString("  ·  ").ifEmpty { "Answered" }
                        Text(steps, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (errors > 0) {
                    Text(
                        "$errors error${if (errors == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
                Chevron(open, "round")
            }
            if (open) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val output = r.note ?: if (r.calls.isEmpty()) "Wrote the reply below." else null
                    if (output != null) SelectionContainer {
                        Text(output, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    } else {
                        Text("No text this round, only tool calls.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    r.reasoning?.let { reasoning ->
                        Section("Reasoning", "${reasoning.trim().split(Regex("\\s+")).size} words", "$key-reasoning", expandAll) {
                            Payload(null, reasoning, error = false, italic = true)
                        }
                    }
                    if (r.calls.isNotEmpty()) {
                        val detail = "${r.calls.size}" + if (errors > 0) "  ·  $errors failed" else ""
                        Section("Tool calls", detail, "$key-calls", expandAll || errors > 0) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                r.calls.forEachIndexed { i, c -> CallRow(c, "$key-c$i", expandAll && c.isError) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A labelled toggle inside a round; its content shows only while open. */
@Composable
private fun Section(label: String, detail: String, key: String, initiallyOpen: Boolean, content: @Composable () -> Unit) {
    var open by rememberSaveable(key) { mutableStateOf(initiallyOpen) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { open = !open }.padding(vertical = 6.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (open) Icons.Rounded.ExpandMore else Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(8.dp))
            Text(detail, style = Figures.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) Box(Modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp)) { content() }
    }
}

/** One line per tool call; it opens into the arguments and the result. */
@Composable
private fun CallRow(c: TraceCall, key: String, initiallyOpen: Boolean) {
    var open by rememberSaveable(key) { mutableStateOf(initiallyOpen) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { open = !open }.padding(vertical = 6.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(c.isError, size = 18.dp)
            Spacer(Modifier.width(10.dp))
            Text(c.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            Text(c.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.weight(1f))
            Chevron(open, c.label, size = 20.dp)
        }
        if (open) {
            Column(Modifier.padding(start = 32.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Payload("Arguments", c.arguments, error = false)
                Payload("Result", c.result ?: "No result recorded", error = c.isError)
            }
        }
    }
}

@Composable
private fun Chevron(open: Boolean, what: String, size: androidx.compose.ui.unit.Dp = 24.dp) {
    Icon(
        if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
        contentDescription = if (open) "Collapse $what" else "Expand $what",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(size),
    )
}

@Composable
private fun StatusDot(error: Boolean, size: androidx.compose.ui.unit.Dp = 22.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (error) Icons.Rounded.PriorityHigh else Icons.Rounded.Check, null,
            tint = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.size(size * 0.64f),
        )
    }
}

@Composable
private fun CopyTraceButton(item: ChatItem.Activity) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
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
private fun Payload(label: String?, text: String, error: Boolean, italic: Boolean = false) {
    var full by remember { mutableStateOf(false) }
    val shown = if (full || text.length <= MAX_SHOWN_CHARS) text else text.take(MAX_SHOWN_CHARS)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        if (label != null) {
            Text(
                label, style = MaterialTheme.typography.labelSmall,
                color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
        }
        SelectionContainer {
            Text(
                shown,
                style = MaterialTheme.typography.bodySmall.let { if (italic) it.copy(fontStyle = FontStyle.Italic) else it },
                color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
                maxLines = if (full) Int.MAX_VALUE else 14,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
