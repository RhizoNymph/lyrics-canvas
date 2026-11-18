package dev.nymph.lyrics.app.editor.score

import dev.nymph.lyrics.logic.score.edit.SyllableRef
import dev.nymph.lyrics.model.score.Score
import dev.nymph.lyrics.model.score.ScoreSnap

/** The score layer's actions (musical mode); implemented by the grid editor's ViewModel. */
interface ScoreActions {
    /** Show the staff: the rows take the score's rest-linked grid first. */
    fun showStaff()

    fun showGrid()

    /** "Make score" from the grid, locally (offline): every cell in turn, rests one beat unit. */
    fun draftFromGrid(num: Int, den: Int, bpm: Double, note: ScoreSnap)

    /** A score made elsewhere (the draft sheet's `onDraft`); ignored when it does not fit the rows' grid. */
    fun useDraft(score: Score)

    /** "Remove score" (after the screen's confirmation): back to the grid only. */
    fun removeScore()

    /** Select a note or tray syllable (null clears). */
    fun selectSyllable(ref: SyllableRef?)

    fun placeSyllable(ref: SyllableRef, tick: Int)
    fun moveNote(ref: SyllableRef, tick: Int)
    fun resizeNote(ref: SyllableRef, endTick: Int)

    /** The selected note one snap left (-1) or right (+1). */
    fun nudgeNote(direction: Int)

    /** The selected note one snap shorter (-1) or longer (+1). */
    fun stepLength(direction: Int)

    fun cycleAccent()

    /** The selected note back to the tray. */
    fun unplaceSelected()

    fun setMeter(bar: Int, num: Int, den: Int)
    fun removeMeter(bar: Int)
    fun setTempo(bar: Int, bpm: Double)
    fun removeTempo(bar: Int)
    fun setPickup(ticks: Int)
    fun setSnap(snap: ScoreSnap)
}
