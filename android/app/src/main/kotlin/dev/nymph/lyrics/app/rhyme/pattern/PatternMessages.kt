package dev.nymph.lyrics.app.rhyme.pattern

import dev.nymph.lyrics.logic.rhyme.pattern.PatternError
import dev.nymph.lyrics.logic.rhyme.pattern.PatternFailure
import dev.nymph.lyrics.logic.rhyme.pattern.PatternOperation
import dev.nymph.lyrics.logic.rhyme.pattern.PatternSkip
import dev.nymph.lyrics.logic.rhyme.pattern.RangeProblem
import dev.nymph.lyrics.logic.rhyme.pattern.SlotBoard
import dev.nymph.lyrics.logic.rhyme.pattern.SlotEditRefusal
import dev.nymph.lyrics.logic.rhyme.pattern.SlotId
import dev.nymph.lyrics.logic.rhyme.pattern.slotLetter
import dev.nymph.lyrics.model.PatternErrorCode
import dev.nymph.lyrics.model.PatternLimits

private fun SlotBoard.letterOf(id: SlotId): String = indexOf(id).takeIf { it >= 0 }?.let { slotLetter(it) } ?: id.value

/** Why a slot edit was refused, for the notice line. Lines are numbered from 1 within the snippet. */
fun refusalText(refusal: SlotEditRefusal, board: SlotBoard): String = when (refusal) {
    is SlotEditRefusal.CrossesOrder ->
        "That would put ${board.letterOf(refusal.moved)} out of order with ${board.letterOf(refusal.other)} on line ${refusal.lineIndex + 1}."
    is SlotEditRefusal.NoSuchSlot -> "That slot is gone."
    is SlotEditRefusal.NoSuchWord -> "That word is not in the pattern."
    SlotEditRefusal.NotInSlot -> "That word is not in a slot."
    SlotEditRefusal.TooManySlots -> "A pattern has at most ${PatternLimits.MAX_SLOTS} slots."
}

fun rangeProblemText(problem: RangeProblem): String = when (problem) {
    RangeProblem.OUTSIDE_SNIPPET -> "Those lines are not in the snippet."
    RangeProblem.TOO_FEW_LINES -> "Pick at least 2 lines with words."
    RangeProblem.TOO_MANY_LINES -> "Pick at most ${PatternLimits.MAX_LINES} lines."
}

fun skipText(skip: PatternSkip): String = when (skip) {
    PatternSkip.NO_SLOTS_ON -> "Turn a slot on (or tap words to make one) to see suggestions."
    PatternSkip.NO_SOURCES -> "Turn on notes or dictionary to see suggestions."
}

fun failureText(failure: PatternFailure): String {
    val what = when (failure.operation) {
        PatternOperation.DETECT -> "Finding the pattern failed"
        PatternOperation.SUGGEST -> "Suggestions failed"
        PatternOperation.MAKE_SNIPPET -> "Make snippet failed"
        PatternOperation.MATERIALIZE -> "Materialize failed"
    }
    return when (val e = failure.error) {
        PatternError.NeedsConnection -> "$what: needs connection."
        is PatternError.Rejected -> when (e.code) {
            PatternErrorCode.RANGE_INVALID -> "$what: these lines can't be used (${e.message})."
            PatternErrorCode.SPAN_INVALID, PatternErrorCode.SLOT_ORDER -> "$what: the slots don't fit the lines any more; Reset to detect again (${e.message})."
            PatternErrorCode.INVALID_TAG_EXPRESSION -> "$what: fix the tag filter (${e.message})."
        }
        else -> "$what: ${e.message}"
    }
}
