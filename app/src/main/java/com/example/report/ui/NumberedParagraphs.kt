package com.example.report.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.em

/**
 * Number prefixes ("1. ", "2. ", …) for every non-blank line of [text]; blank lines get "".
 * With [pad] the numbers are left-padded with spaces to the width of the largest one.
 */
fun prefixes(text: String, pad: Boolean): List<String> {
    val width = if (pad) text.split('\n').count { it.isNotBlank() }.toString().length else 0
    var n = 0
    return text.split('\n').map { line ->
        if (line.isBlank()) "" else (++n).toString().padStart(width, ' ') + ". "
    }
}

/**
 * Shows a number in front of each non-blank paragraph (in [numColor]) without changing the
 * stored text; wrapped lines are indented to align under the text.
 */
data class NumberedParagraphs(val numColor: Color) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val src = text.text
        val lines = src.split('\n')
        val pre = prefixes(src, true)
        val sb = StringBuilder(src.length + lines.size * 4)
        val origToTrans = IntArray(src.length + 1)
        val transToOrig = ArrayList<Int>(src.length + lines.size * 4 + 1)
        val lineStarts = IntArray(lines.size)
        val prefixLens = IntArray(lines.size)

        var o = 0
        lines.forEachIndexed { i, line ->
            lineStarts[i] = sb.length
            prefixLens[i] = pre[i].length
            for (c in pre[i]) {
                transToOrig.add(o)
                sb.append(c)
            }
            for (c in line) {
                origToTrans[o] = sb.length
                transToOrig.add(o)
                sb.append(c)
                o++
            }
            origToTrans[o] = sb.length
            if (i < lines.size - 1) {
                transToOrig.add(o)
                sb.append('\n')
                o++
            }
        }
        transToOrig.add(o)

        val out = sb.toString()
        val layout = Layout(out, lineStarts, prefixLens, origToTrans, transToOrig.toIntArray())

        val annotated = buildAnnotatedString {
            append(out)
            for (i in lines.indices) {
                val len = layout.prefixLens[i]
                if (len > 0) {
                    val start = layout.lineStarts[i]
                    addStyle(SpanStyle(color = numColor), start, start + len)
                }
            }
            if (out.isNotEmpty()) {
                val count = src.split('\n').count { it.isNotBlank() }
                addStyle(
                    ParagraphStyle(textIndent = TextIndent(restLine = ((count.toString().length + 2) * 0.6).em)),
                    0,
                    out.length,
                )
            }
        }
        return TransformedText(annotated, Mapping(layout))
    }

    private class Layout(
        val text: String,
        val lineStarts: IntArray,
        val prefixLens: IntArray,
        val origToTrans: IntArray,
        val transToOrig: IntArray,
    )

    private class Mapping(private val layout: Layout) : OffsetMapping {
        override fun originalToTransformed(offset: Int): Int =
            layout.origToTrans[offset.coerceIn(0, layout.origToTrans.size - 1)]

        override fun transformedToOriginal(offset: Int): Int =
            layout.transToOrig[offset.coerceIn(0, layout.transToOrig.size - 1)]
    }
}
