package dev.nymph.lyrics.app.editor.score.ui

import dev.nymph.lyrics.logic.score.barCount
import dev.nymph.lyrics.logic.score.barLength
import dev.nymph.lyrics.logic.score.barStartTick
import dev.nymph.lyrics.logic.score.edit.SyllableRef
import dev.nymph.lyrics.logic.score.isCompound
import dev.nymph.lyrics.logic.score.meterAtBar
import dev.nymph.lyrics.logic.score.unit
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.score.Score

/** Sizes of the staff editor, in px (the composable converts from dp). */
data class StaffMetrics(
    val width: Float,
    /** Horizontal px per tick before fitting (a quarter note = 48 ticks). */
    val pxPerTick: Float,
    val rowHeight: Float,
    val leftPad: Float,
    val rightPad: Float,
    /** Bars drawn after the last note, so a note can be dragged past the end. */
    val spareBars: Int = 1,
)

/** A bar line (or the pickup's start) in a row, with the meter / tempo label when one starts here. */
data class BarMark(val bar: Int, val tick: Int, val x: Float, val meter: String?, val tempo: String?)

/** A tick of a beat inside a bar (drawn as a short mark on the staff line). */
data class BeatMark(val x: Float, val strong: Boolean)

/** One row of wrapped bars: ticks `[startTick, endTick)`. */
data class StaffRow(val index: Int, val startTick: Int, val endTick: Int, val top: Float, val bars: List<BarMark>, val beats: List<BeatMark>, val endX: Float)

/** A note's box in one row (a note across a row break has one box per row). */
data class NoteBox(
    val ref: SyllableRef,
    val row: Int,
    val left: Float,
    val right: Float,
    /** This box holds the note's start (its syllable label and accent go here). */
    val head: Boolean,
    /** This box holds the note's end (the length handle goes here). */
    val tail: Boolean,
    val label: String,
    val accent: Int,
)

/** Where a lyric line's first note starts (line boundaries are marked on the staff). */
data class LineMark(val line: Int, val row: Int, val x: Float)

/** What a touch hits on the staff. */
sealed interface StaffHit {
    data class Body(val ref: SyllableRef, val grabTicks: Int) : StaffHit

    data class Edge(val ref: SyllableRef) : StaffHit

    data class Empty(val tick: Int) : StaffHit
}

/**
 * The staff laid out in rows: the pickup, then bars 0.. (the score's bars plus spare ones), packed
 * left to right into rows of [StaffMetrics.width]; px per tick shrinks so the longest bar fits a row.
 */
class StaffGeometry(val score: Score, grid: GridData?, val metrics: StaffMetrics) {
    val pxPerTick: Float
    val rows: List<StaffRow>
    val notes: List<NoteBox>
    val lines: List<LineMark>
    val height: Float

    private class Segment(val bar: Int, val start: Int, val length: Int)

    init {
        val bars = barCount(score) + metrics.spareBars
        val segments = buildList {
            if (score.pickup > 0) add(Segment(-1, 0, score.pickup))
            for (bar in 0 until bars) add(Segment(bar, barStartTick(score, bar).toInt(), barLength(score, bar)))
        }
        val usable = (metrics.width - metrics.leftPad - metrics.rightPad).coerceAtLeast(1f)
        val longest = segments.maxOf { it.length }
        pxPerTick = minOf(metrics.pxPerTick, usable / longest)
        val packed = mutableListOf<MutableList<Segment>>()
        var used = Float.MAX_VALUE
        for (seg in segments) {
            val w = seg.length * pxPerTick
            if (used + w > usable + 0.5f) {
                packed += mutableListOf<Segment>()
                used = 0f
            }
            packed.last() += seg
            used += w
        }
        rows = packed.mapIndexed { index, segs ->
            val start = segs.first().start
            fun x(tick: Int) = metrics.leftPad + (tick - start) * pxPerTick
            val marks = segs.map { seg ->
                val meter = score.meters.firstOrNull { it.bar == seg.bar }?.let { "${it.num}/${it.den}" }
                val tempo = score.tempos.firstOrNull { it.bar == seg.bar }?.let { "♩=${bpmLabel(it.bpm)}" }
                BarMark(seg.bar, seg.start, x(seg.start), meter, tempo)
            }
            val beats = segs.filter { it.bar >= 0 }.flatMap { seg ->
                val m = meterAtBar(score, seg.bar)
                val beat = if (m.isCompound) 3 * m.unit else m.unit
                (beat until seg.length step beat).map { BeatMark(x(seg.start + it), strong = false) }
            }
            val end = segs.last().let { it.start + it.length }
            StaffRow(index, start, end, index * metrics.rowHeight, marks, beats, x(end))
        }
        height = rows.size * metrics.rowHeight
        val syllables = grid?.lines.orEmpty().map { line -> line.cells.filter { !it.isRest }.map { it.syllable.orEmpty() } }
        notes = score.lines.flatMapIndexed { line, l ->
            l.notes.flatMap { n ->
                val label = syllables.getOrNull(line)?.getOrNull(n.s).orEmpty()
                rows.filter { it.startTick < n.end && n.t < it.endTick }.map { row ->
                    NoteBox(
                        ref = SyllableRef(line, n.s),
                        row = row.index,
                        left = metrics.leftPad + (maxOf(n.t, row.startTick) - row.startTick) * pxPerTick,
                        right = metrics.leftPad + (minOf(n.end, row.endTick) - row.startTick) * pxPerTick,
                        head = n.t >= row.startTick,
                        tail = n.end <= row.endTick,
                        label = label,
                        accent = n.a,
                    )
                }
            }
        }
        lines = score.lines.mapIndexedNotNull { line, l ->
            val first = l.notes.firstOrNull() ?: return@mapIndexedNotNull null
            val row = rowOf(first.t) ?: return@mapIndexedNotNull null
            LineMark(line, row.index, xOf(row, first.t))
        }
    }

    private fun rowOf(tick: Int): StaffRow? = rows.firstOrNull { tick < it.endTick } ?: rows.lastOrNull()

    private fun xOf(row: StaffRow, tick: Int) = metrics.leftPad + (tick - row.startTick) * pxPerTick

    /** The row and x of [tick] (the playhead); past the end it stays at the end of the last row. */
    fun positionOf(tick: Int): Pair<StaffRow, Float>? {
        val row = rowOf(tick) ?: return null
        return row to xOf(row, tick.coerceAtMost(row.endTick))
    }

    /** The tick under ([x], [y]), clamped into the row there (and to the staff's rows). */
    fun tickAt(x: Float, y: Float): Int {
        val row = rows.getOrNull((y / metrics.rowHeight).toInt().coerceIn(0, rows.size - 1)) ?: return 0
        val tick = row.startTick + ((x - metrics.leftPad) / pxPerTick).toInt()
        return tick.coerceIn(row.startTick, row.endTick)
    }

    /**
     * What a touch at ([x], [y]) hits: a note's length handle (the last [edgePx] of its tail box),
     * its body (with the ticks from the note's start to the touch), or empty staff.
     */
    fun hit(x: Float, y: Float, edgePx: Float): StaffHit {
        val rowIndex = (y / metrics.rowHeight).toInt()
        val inRow = notes.filter { it.row == rowIndex }
        val tick = tickAt(x, y)
        inRow.firstOrNull { it.tail && x in (it.right - edgePx)..(it.right + edgePx / 2) }?.let { return StaffHit.Edge(it.ref) }
        inRow.firstOrNull { x in it.left..it.right }?.let { box ->
            val start = score.lines[box.ref.line].notes.first { it.s == box.ref.s }.t
            return StaffHit.Body(box.ref, (tick - start).coerceAtLeast(0))
        }
        return StaffHit.Empty(tick)
    }
}

/** A bpm as shown: no decimals when whole. */
fun bpmLabel(bpm: Double): String = if (bpm % 1.0 == 0.0) bpm.toLong().toString() else bpm.toString()
