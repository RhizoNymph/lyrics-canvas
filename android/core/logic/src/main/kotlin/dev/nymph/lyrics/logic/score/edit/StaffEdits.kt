package dev.nymph.lyrics.logic.score.edit

import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.score.Score
import dev.nymph.lyrics.model.score.ScoreAccent
import dev.nymph.lyrics.model.score.ScoreLine
import dev.nymph.lyrics.model.score.ScoreNote

/** A syllable of the score: grid line [line], syllable ordinal [s] (rest cells not counted). */
data class SyllableRef(val line: Int, val s: Int)

/** Why a staff edit did nothing. */
enum class StaffRefusal {
    /** The line or syllable does not exist in the grid / score. */
    NO_SUCH_SYLLABLE,

    /** The syllable already has a note (place) or has none (move, resize, accent, unplace). */
    WRONG_PLACEMENT,

    /** No free ticks between the neighbouring notes (single voice: notes keep their order). */
    NO_ROOM,

    /** A meter, tempo, bar or pickup value outside format v1. */
    OUT_OF_RANGE,

    /** Bar 0's meter and tempo can be changed but not removed. */
    FIRST_ENTRY,
}

/** The outcome of a staff edit: a new valid score, or why nothing changed. */
sealed interface StaffEdit {
    data class Changed(val score: Score) : StaffEdit

    data class Refused(val reason: StaffRefusal) : StaffEdit
}

/** The free ticks for syllable [ref]: after the previous note in score order, before the next one. */
data class NoteWindow(val start: Int, val end: Int) {
    val size: Int get() = end - start
}

/**
 * Where [ref] may sit without breaking the single voice: notes are ordered by (line, s), so it lies
 * between the end of the last note before it and the start of the first note after it (its own
 * note excluded); MAX_TICKS bounds the end.
 */
fun noteWindow(score: Score, ref: SyllableRef): NoteWindow {
    var start = 0
    var end = Score.MAX_TICKS
    score.lines.forEachIndexed { index, line ->
        for (note in line.notes) {
            val before = index < ref.line || (index == ref.line && note.s < ref.s)
            val after = index > ref.line || (index == ref.line && note.s > ref.s)
            if (before) start = maxOf(start, note.end)
            if (after && note.t < end) end = note.t
        }
    }
    return NoteWindow(start, end.coerceAtLeast(start))
}

fun Score.noteOf(ref: SyllableRef): ScoreNote? = lines.getOrNull(ref.line)?.notes?.firstOrNull { it.s == ref.s }

private fun Score.withNote(ref: SyllableRef, note: ScoreNote?): Score {
    val line = lines[ref.line]
    val others = line.notes.filter { it.s != ref.s }
    val notes = if (note == null) others else (others + note).sortedBy { it.s }
    return copy(lines = lines.toMutableList().also { it[ref.line] = ScoreLine(notes) })
}

private fun syllableCount(grid: GridData?, line: Int): Int? = grid?.lines?.getOrNull(line)?.cells?.count { !it.isRest }

/**
 * Place the unplaced syllable [ref] near [tick] (snapped to [snap] ticks), [snap] long or as long as
 * fits before the next note. The accent comes from the grid stress (1 -> accent), as in a draft.
 */
fun placeSyllable(score: Score, grid: GridData?, ref: SyllableRef, tick: Int, snap: Int): StaffEdit {
    val count = syllableCount(grid, ref.line) ?: return StaffEdit.Refused(StaffRefusal.NO_SUCH_SYLLABLE)
    if (ref.line !in score.lines.indices || ref.s !in 0 until count) return StaffEdit.Refused(StaffRefusal.NO_SUCH_SYLLABLE)
    if (score.noteOf(ref) != null) return StaffEdit.Refused(StaffRefusal.WRONG_PLACEMENT)
    val window = noteWindow(score, ref)
    if (window.size < 1) return StaffEdit.Refused(StaffRefusal.NO_ROOM)
    val d = minOf(snap, window.size)
    val t = snapTick(score, tick, snap).coerceIn(window.start, window.end - d)
    val cell = grid!!.lines[ref.line].cells.filter { !it.isRest }[ref.s]
    val accent = if (cell.stress == 1) ScoreAccent.ACCENT else ScoreAccent.NONE
    return StaffEdit.Changed(score.withNote(ref, ScoreNote(ref.s, t, d, accent)))
}

/**
 * Move [ref]'s note so it starts near [tick] (snapped), clamped between its neighbours; a note
 * longer than its window is shortened to fit. Accent, length and extra keys are kept.
 */
fun moveNote(score: Score, ref: SyllableRef, tick: Int, snap: Int): StaffEdit {
    val note = score.noteOf(ref) ?: return StaffEdit.Refused(StaffRefusal.WRONG_PLACEMENT)
    val window = noteWindow(score, ref)
    if (window.size < 1) return StaffEdit.Refused(StaffRefusal.NO_ROOM)
    val d = minOf(note.d, window.size)
    val t = snapTick(score, tick, snap).coerceIn(window.start, window.end - d)
    if (t == note.t && d == note.d) return StaffEdit.Changed(score)
    return StaffEdit.Changed(score.withNote(ref, note.copy(t = t, d = d)))
}

/** Set [ref]'s note to end near [endTick] (snapped), at least one tick long and before the next note. */
fun resizeNote(score: Score, ref: SyllableRef, endTick: Int, snap: Int): StaffEdit {
    val note = score.noteOf(ref) ?: return StaffEdit.Refused(StaffRefusal.WRONG_PLACEMENT)
    val window = noteWindow(score, ref)
    val snapped = snapTick(score, endTick.coerceAtLeast(note.t + 1), snap)
    val end = (if (snapped <= note.t) note.t + snap else snapped).coerceIn(note.t + 1, window.end)
    return StaffEdit.Changed(score.withNote(ref, note.copy(d = end - note.t)))
}

/** Lengthen (+1) or shorten (-1) [ref]'s note by one [snap] unit, within its window. */
fun stepLength(score: Score, ref: SyllableRef, direction: Int, snap: Int): StaffEdit {
    val note = score.noteOf(ref) ?: return StaffEdit.Refused(StaffRefusal.WRONG_PLACEMENT)
    val window = noteWindow(score, ref)
    val d = (note.d + direction * snap).coerceIn(1, window.end - note.t)
    return StaffEdit.Changed(score.withNote(ref, note.copy(d = d)))
}

/** Move [ref]'s note by one [snap] unit left (-1) or right (+1), within its window. */
fun nudgeNote(score: Score, ref: SyllableRef, direction: Int, snap: Int): StaffEdit {
    val note = score.noteOf(ref) ?: return StaffEdit.Refused(StaffRefusal.WRONG_PLACEMENT)
    val window = noteWindow(score, ref)
    val t = (note.t + direction * snap).coerceIn(window.start, (window.end - note.d).coerceAtLeast(window.start))
    if (t + note.d > window.end) return StaffEdit.Refused(StaffRefusal.NO_ROOM)
    return StaffEdit.Changed(score.withNote(ref, note.copy(t = t)))
}

/** Accent cycle: none -> accent -> strong -> none. */
fun cycleAccent(score: Score, ref: SyllableRef): StaffEdit {
    val note = score.noteOf(ref) ?: return StaffEdit.Refused(StaffRefusal.WRONG_PLACEMENT)
    val next = when (note.a) {
        ScoreAccent.NONE -> ScoreAccent.ACCENT
        ScoreAccent.ACCENT -> ScoreAccent.STRONG
        else -> ScoreAccent.NONE
    }
    return StaffEdit.Changed(score.withNote(ref, note.copy(a = next)))
}

/** Take [ref]'s note off the staff: the syllable goes back to the tray. */
fun unplace(score: Score, ref: SyllableRef): StaffEdit {
    score.noteOf(ref) ?: return StaffEdit.Refused(StaffRefusal.WRONG_PLACEMENT)
    return StaffEdit.Changed(score.withNote(ref, null))
}

/** The syllables of each grid line with no note, in order (the tray). */
fun unplacedSyllables(score: Score, grid: GridData?): List<List<Int>> =
    grid?.lines.orEmpty().mapIndexed { index, line ->
        val placed = score.lines.getOrNull(index)?.notes?.mapTo(HashSet()) { it.s }.orEmpty()
        (0 until line.cells.count { !it.isRest }).filter { it !in placed }
    }
