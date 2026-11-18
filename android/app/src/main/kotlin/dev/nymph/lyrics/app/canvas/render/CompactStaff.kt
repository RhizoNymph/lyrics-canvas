package dev.nymph.lyrics.app.canvas.render

import dev.nymph.lyrics.logic.score.barAtTick
import dev.nymph.lyrics.logic.score.barStartTick
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.score.Score

/** One placed syllable on a compact staff row (card units). */
data class CompactNote(val x: Double, val width: Double, val accent: Int, val label: String)

/** One lyric line as a one-line staff: its bars (x of each bar line) and notes; [y] is the staff line. */
data class CompactRow(val y: Double, val left: Double, val right: Double, val barXs: List<Double>, val notes: List<CompactNote>)

/** The compact staff a card shows instead of its grid when the snippet is in the staff view. */
data class CompactStaff(val rows: List<CompactRow>)

/** True when a card (or list row) shows the snippet's staff rather than its grid. */
fun showsStaff(snippet: Snippet): Boolean = snippet.scoreView && snippet.score != null

/**
 * Each grid line as a row [CardMetrics.CELL_HEIGHT] tall, from the bar line before its first note
 * to the bar line after its last; every row on one horizontal scale (the longest span fills
 * [width]), starting at [left]. A line with no placed syllable is an empty one-bar row.
 */
fun compactStaff(score: Score, grid: GridData?, left: Double, top: Double, width: Double): CompactStaff {
    val syllables = grid?.lines.orEmpty().map { line -> line.cells.filter { !it.isRest }.map { it.syllable.orEmpty() } }
    val spans = score.lines.map { line ->
        val first = line.notes.firstOrNull() ?: return@map null
        val end = line.notes.maxOf { it.end }
        val startBar = barAtTick(score, first.t).bar
        val endPos = barAtTick(score, end)
        val endBar = if (endPos.offset == 0) endPos.bar else endPos.bar + 1
        val from = barStartTick(score, startBar).toInt()
        val to = maxOf(barStartTick(score, endBar).toInt(), end)
        Triple(from, to, (startBar + 1..endBar - 1).filter { it >= 0 }.map { barStartTick(score, it).toInt() })
    }
    val longest = spans.filterNotNull().maxOfOrNull { it.second - it.first }?.coerceAtLeast(1) ?: Score.WHOLE
    val scale = width / longest
    val rows = score.lines.mapIndexed { index, line ->
        val y = top + index * CardMetrics.CELL_HEIGHT + CardMetrics.CELL_HEIGHT * 0.45
        val span = spans[index]
        if (span == null) {
            CompactRow(y, left, left + Score.WHOLE * scale, emptyList(), emptyList())
        } else {
            val (from, to, inner) = span
            CompactRow(
                y = y,
                left = left,
                right = left + (to - from) * scale,
                barXs = inner.map { left + (it - from) * scale },
                notes = line.notes.map { n ->
                    CompactNote(left + (n.t - from) * scale, (n.d * scale).coerceAtLeast(1.0), n.a, syllables.getOrNull(index)?.getOrNull(n.s).orEmpty())
                },
            )
        }
    }
    return CompactStaff(rows)
}
