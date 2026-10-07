package com.strata.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Just enough Markdown for assistant replies: paragraphs, bullet and numbered lists, bold.
 * Each paragraph and list item is one text, with the list marker drawn inline, so text copied
 * from a selection keeps its markers and line breaks.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge) {
    val blocks = text.trim().split(Regex("\n\\s*\n"))
    val markerColor = MaterialTheme.colorScheme.primary
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (block in blocks) {
            val lines = block.lines().filter { it.isNotBlank() }
            val isList = lines.isNotEmpty() && lines.all { listMarker(it) != null }
            if (isList) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    lines.forEach { line ->
                        val (marker, body) = listMarker(line)!!
                        val markerContent = mapOf(
                            // A box the height of the font, centred on the line, with the marker centred in it.
                            MARKER to InlineTextContent(Placeholder(MarkerWidth, style.fontSize, PlaceholderVerticalAlign.Center)) {
                                // The item's own text already holds the marker for copying; this one is only drawn.
                                DisableSelection {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                                        Text(
                                            marker, Modifier.wrapContentHeight(unbounded = true),
                                            style = style.copy(lineHeight = TextUnit.Unspecified), color = markerColor, maxLines = 1,
                                        )
                                    }
                                }
                            }
                        )
                        Text(
                            listItem(marker, body),
                            style = style.copy(textIndent = TextIndent(restLine = MarkerWidth)),
                            color = LocalContentColor.current,
                            inlineContent = markerContent,
                        )
                    }
                }
            } else {
                val joined = lines.joinToString("\n") { it.trimStart('#', ' ').let { l -> if (it.startsWith("#")) "**$l**" else it } }
                Text(inline(joined), style = style, color = LocalContentColor.current)
            }
        }
    }
}

private const val MARKER = "marker"

/** Room for "•" or "12." before a list item; wrapped lines indent by the same amount. */
private val MarkerWidth = 22.sp

/** A list item: the marker is a placeholder drawn in the margin, and copies as "• " or "1. ". */
private fun listItem(marker: String, body: String): AnnotatedString = buildAnnotatedString {
    appendInlineContent(MARKER, "$marker ")
    append(inline(body))
}

private fun listMarker(line: String): Pair<String, String>? {
    val t = line.trimStart()
    if (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• ")) return "•" to t.substring(2)
    val m = Regex("^(\\d+)[.)]\\s+(.*)").find(t) ?: return null
    return "${m.groupValues[1]}." to m.groupValues[2]
}

private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    var rest = text
    while (true) {
        val start = rest.indexOf("**")
        val end = if (start >= 0) rest.indexOf("**", start + 2) else -1
        if (start < 0 || end < 0) { append(rest.replace("`", "")); break }
        append(rest.substring(0, start).replace("`", ""))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(rest.substring(start + 2, end)) }
        rest = rest.substring(end + 2)
    }
}

