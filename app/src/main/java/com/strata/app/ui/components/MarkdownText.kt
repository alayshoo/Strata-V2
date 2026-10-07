package com.strata.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** Just enough Markdown for assistant replies: paragraphs, bullet and numbered lists, bold. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge) {
    val blocks = text.trim().split(Regex("\n\\s*\n"))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (block in blocks) {
            val lines = block.lines().filter { it.isNotBlank() }
            val isList = lines.isNotEmpty() && lines.all { listMarker(it) != null }
            if (isList) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    lines.forEach { line ->
                        val (marker, body) = listMarker(line)!!
                        Row {
                            Text(marker, style = style, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(22.dp))
                            Text(inline(body), style = style, color = LocalContentColor.current)
                        }
                    }
                }
            } else {
                val joined = lines.joinToString("\n") { it.trimStart('#', ' ').let { l -> if (it.startsWith("#")) "**$l**" else it } }
                Text(inline(joined), style = style, color = LocalContentColor.current)
            }
        }
    }
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

