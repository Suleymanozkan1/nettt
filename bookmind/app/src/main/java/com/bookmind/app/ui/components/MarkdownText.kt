package com.bookmind.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** The Markdown subset the summaries use: headings, paragraphs, bullet and numbered lists, **bold** and *italic*. */
internal sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullet(val marker: String?, val text: String) : MdBlock
}

internal fun parseMarkdown(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()
    fun flush() { if (paragraph.isNotBlank()) blocks += MdBlock.Paragraph(paragraph.toString().trim()); paragraph.clear() }
    for (raw in text.lines()) {
        val line = raw.trim()
        val heading = Regex("^(#{1,6})\\s+(.*)$").find(line)
        val bullet = Regex("^[-*•]\\s+(.*)$").find(line)
        val numbered = Regex("^(\\d{1,3})[.)]\\s+(.*)$").find(line)
        when {
            line.isEmpty() -> flush()
            heading != null -> { flush(); blocks += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim().trim('*')) }
            bullet != null -> { flush(); blocks += MdBlock.Bullet(null, bullet.groupValues[1]) }
            numbered != null -> { flush(); blocks += MdBlock.Bullet(numbered.groupValues[1] + ".", numbered.groupValues[2]) }
            else -> paragraph.append(if (paragraph.isEmpty()) "" else " ").append(line)
        }
    }
    flush()
    return blocks
}

/** **bold** and *italic* / _italic_ inside a line. */
internal fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    val pattern = Regex("\\*\\*(.+?)\\*\\*|\\*(.+?)\\*|_(.+?)_")
    var last = 0
    for (m in pattern.findAll(text)) {
        append(text.substring(last, m.range.first))
        when {
            m.groupValues[1].isNotEmpty() -> { pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold)); append(m.groupValues[1]); pop() }
            else -> { pushStyle(SpanStyle(fontStyle = FontStyle.Italic)); append(m.groupValues[2].ifEmpty { m.groupValues[3] }); pop() }
        }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseMarkdown(text) }
    val cs = MaterialTheme.colorScheme
    Column(modifier) {
        blocks.forEachIndexed { i, b ->
            when (b) {
                is MdBlock.Heading -> Text(
                    inlineMarkdown(b.text),
                    style = if (b.level <= 2) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                    color = if (b.level <= 2) cs.primary else cs.onSurface,
                    modifier = Modifier.padding(top = if (i == 0) 0.dp else 18.dp, bottom = 6.dp),
                )
                is MdBlock.Paragraph -> Text(inlineMarkdown(b.text), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 10.dp))
                is MdBlock.Bullet -> Row(Modifier.padding(start = 4.dp, bottom = 6.dp)) {
                    if (b.marker == null) Box(Modifier.padding(top = 9.dp).size(6.dp).clip(CircleShape).background(cs.primary))
                    else Text(b.marker, style = MaterialTheme.typography.bodyLarge, color = cs.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(inlineMarkdown(b.text), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
