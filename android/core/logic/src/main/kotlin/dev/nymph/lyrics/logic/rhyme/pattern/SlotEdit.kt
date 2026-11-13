package dev.nymph.lyrics.logic.rhyme.pattern

import dev.nymph.lyrics.model.PatternLimits

/** One edit of the pattern's slots (the word picker, the legend chips). */
sealed interface SlotEdit {
    /**
     * Make word [word] of [lineIndex] the slot's member on that line (replacing its member there).
     * A word already in another slot moves (that slot goes when it has no member left).
     */
    data class AddWord(val slot: SlotId, val lineIndex: Int, val word: Int) : SlotEdit

    /** Start a slot holding only this word, placed in order; the word leaves any slot it was in. */
    data class NewSlot(val lineIndex: Int, val word: Int) : SlotEdit

    /** Take the member containing this word out of its slot (the slot goes when it was the last member). */
    data class RemoveWord(val lineIndex: Int, val word: Int) : SlotEdit

    /** The legend chip's ×. */
    data class Drop(val slot: SlotId) : SlotEdit

    /** The legend chip: on / off. */
    data class Toggle(val slot: SlotId) : SlotEdit
}

/** Why an edit was refused; the board is unchanged. */
sealed interface SlotEditRefusal {
    /** The edit would put [moved] out of order with [other] on line [lineIndex]. */
    data class CrossesOrder(val lineIndex: Int, val moved: SlotId, val other: SlotId) : SlotEditRefusal

    data class NoSuchSlot(val slot: SlotId) : SlotEditRefusal

    /** The line is not a pattern line or has no such word. */
    data class NoSuchWord(val lineIndex: Int, val word: Int) : SlotEditRefusal

    /** Remove: the word is in no slot. */
    data object NotInSlot : SlotEditRefusal

    /** New slot: the pattern already has [PatternLimits.MAX_SLOTS]. */
    data object TooManySlots : SlotEditRefusal
}

sealed interface SlotEditResult {
    data class Applied(val board: SlotBoard) : SlotEditResult

    /** The edit changes nothing (the word is already that slot's member). */
    data object Unchanged : SlotEditResult

    data class Refused(val reason: SlotEditRefusal) : SlotEditResult
}

fun applySlotEdit(board: SlotBoard, edit: SlotEdit): SlotEditResult = when (edit) {
    is SlotEdit.Toggle -> if (board.slot(edit.slot) == null) {
        SlotEditResult.Refused(SlotEditRefusal.NoSuchSlot(edit.slot))
    } else {
        SlotEditResult.Applied(board.copy(slots = board.slots.map { if (it.id == edit.slot) it.copy(on = !it.on) else it }))
    }
    is SlotEdit.Drop -> if (board.slot(edit.slot) == null) {
        SlotEditResult.Refused(SlotEditRefusal.NoSuchSlot(edit.slot))
    } else {
        SlotEditResult.Applied(board.copy(slots = board.slots.filter { it.id != edit.slot }))
    }
    is SlotEdit.RemoveWord -> removeWord(board, edit)
    is SlotEdit.AddWord -> addWord(board, edit)
    is SlotEdit.NewSlot -> newSlot(board, edit)
}

private fun wordExists(board: SlotBoard, lineIndex: Int, word: Int): Boolean {
    val line = board.line(lineIndex) ?: return false
    return word in 0 until line.wordCount
}

/** [slots] with the member containing the word removed; slots left without members go. */
private fun withoutWord(slots: List<BoardSlot>, lineIndex: Int, word: Int): List<BoardSlot> = slots.mapNotNull { slot ->
    if (!slot.holds(lineIndex, word)) {
        slot
    } else {
        val rest = slot.members - lineIndex
        if (rest.isEmpty()) null else slot.copy(members = rest)
    }
}

private fun removeWord(board: SlotBoard, edit: SlotEdit.RemoveWord): SlotEditResult {
    if (!wordExists(board, edit.lineIndex, edit.word)) return SlotEditResult.Refused(SlotEditRefusal.NoSuchWord(edit.lineIndex, edit.word))
    if (board.slotAt(edit.lineIndex, edit.word) == null) return SlotEditResult.Refused(SlotEditRefusal.NotInSlot)
    return SlotEditResult.Applied(board.copy(slots = withoutWord(board.slots, edit.lineIndex, edit.word)))
}

private fun addWord(board: SlotBoard, edit: SlotEdit.AddWord): SlotEditResult {
    val target = board.slot(edit.slot) ?: return SlotEditResult.Refused(SlotEditRefusal.NoSuchSlot(edit.slot))
    if (!wordExists(board, edit.lineIndex, edit.word)) return SlotEditResult.Refused(SlotEditRefusal.NoSuchWord(edit.lineIndex, edit.word))
    val span = WordSpan.word(edit.word)
    if (target.members[edit.lineIndex] == span) return SlotEditResult.Unchanged
    val slots = board.slots.mapNotNull { slot ->
        when {
            // Replaces the target's member on this line, if it had one.
            slot.id == target.id -> slot.copy(members = slot.members + (edit.lineIndex to span))
            // The word moves out of the slot it was in.
            slot.holds(edit.lineIndex, edit.word) -> (slot.members - edit.lineIndex).takeIf { it.isNotEmpty() }?.let { slot.copy(members = it) }
            else -> slot
        }
    }
    return checked(board, slots, target.id)
}

private fun newSlot(board: SlotBoard, edit: SlotEdit.NewSlot): SlotEditResult {
    if (!wordExists(board, edit.lineIndex, edit.word)) return SlotEditResult.Refused(SlotEditRefusal.NoSuchWord(edit.lineIndex, edit.word))
    val remaining = withoutWord(board.slots, edit.lineIndex, edit.word)
    if (remaining.size >= PatternLimits.MAX_SLOTS) return SlotEditResult.Refused(SlotEditRefusal.TooManySlots)
    val (id, next) = freshUserId(board)
    val slot = BoardSlot(id, mapOf(edit.lineIndex to WordSpan.word(edit.word)), on = true)
    val index = insertionIndex(board, remaining, edit.lineIndex, edit.word)
    val slots = remaining.take(index) + slot + remaining.drop(index)
    return when (val result = checked(board, slots, id)) {
        is SlotEditResult.Applied -> SlotEditResult.Applied(result.board.copy(nextUser = next))
        else -> result
    }
}

/** The first unused `u<n>` id at or after the board's counter, and the counter after it. */
private fun freshUserId(board: SlotBoard): Pair<SlotId, Int> {
    var n = board.nextUser
    val taken = board.slots.mapTo(HashSet()) { it.id }
    while (SlotId("u$n") in taken) n++
    return SlotId("u$n") to n + 1
}

/**
 * Where a new single-word slot on [lineIndex] goes: after every slot whose member on that line
 * comes before the word, before every slot whose member comes after it, and among the slots with no
 * member on that line by mean relative position (member start / line word count), as the server orders.
 */
private fun insertionIndex(board: SlotBoard, slots: List<BoardSlot>, lineIndex: Int, word: Int): Int {
    val lo = slots.indexOfLast { s -> s.members[lineIndex]?.let { it.end <= word } == true } + 1
    val hi = slots.indexOfFirst { s -> s.members[lineIndex]?.let { it.start > word } == true }.let { if (it < 0) slots.size else it }
    val position = relativePosition(board, lineIndex, word)
    var index = lo
    while (index < hi && meanPosition(board, slots[index]) <= position) index++
    return index
}

private fun relativePosition(board: SlotBoard, lineIndex: Int, word: Int): Double {
    val count = board.line(lineIndex)?.wordCount ?: 1
    return word.toDouble() / maxOf(1, count)
}

private fun meanPosition(board: SlotBoard, slot: BoardSlot): Double =
    slot.members.entries.map { (line, span) -> relativePosition(board, line, span.start) }.average()

private fun checked(board: SlotBoard, slots: List<BoardSlot>, moved: SlotId): SlotEditResult = when (val v = orderViolation(board.lines, slots)) {
    null -> SlotEditResult.Applied(board.copy(slots = slots))
    is Violation.Order -> SlotEditResult.Refused(SlotEditRefusal.CrossesOrder(v.lineIndex, moved, if (v.earlier == moved) v.later else v.earlier))
    is Violation.OutsideLine -> SlotEditResult.Refused(SlotEditRefusal.NoSuchWord(v.lineIndex, -1))
}

/** One entry of the word picker; [refusal] non-null means it is shown disabled with that reason. */
sealed interface WordAction {
    val edit: SlotEdit
    val refusal: SlotEditRefusal?

    data class AddTo(val slot: SlotId, val letter: String, override val edit: SlotEdit, override val refusal: SlotEditRefusal?) : WordAction

    data class NewSlot(override val edit: SlotEdit, override val refusal: SlotEditRefusal?) : WordAction

    data class Remove(val slot: SlotId, val letter: String, override val edit: SlotEdit) : WordAction {
        override val refusal: SlotEditRefusal? get() = null
    }
}

/**
 * The picker for a tapped word: "Add to <letter>" for every slot the word is not already the sole
 * member of (in slot order), "New slot", and "Remove from <letter>" when the word is in a slot.
 * Each entry is pre-checked, so an edit that would break order shows disabled with its reason.
 */
fun wordActions(board: SlotBoard, lineIndex: Int, word: Int): List<WordAction> {
    if (!wordExists(board, lineIndex, word)) return emptyList()
    val actions = mutableListOf<WordAction>()
    board.slots.forEachIndexed { index, slot ->
        val edit = SlotEdit.AddWord(slot.id, lineIndex, word)
        when (val result = applySlotEdit(board, edit)) {
            SlotEditResult.Unchanged -> Unit
            is SlotEditResult.Applied -> actions += WordAction.AddTo(slot.id, slotLetter(index), edit, null)
            is SlotEditResult.Refused -> actions += WordAction.AddTo(slot.id, slotLetter(index), edit, result.reason)
        }
    }
    val newEdit = SlotEdit.NewSlot(lineIndex, word)
    actions += WordAction.NewSlot(newEdit, (applySlotEdit(board, newEdit) as? SlotEditResult.Refused)?.reason)
    board.slotAt(lineIndex, word)?.let { slot ->
        actions += WordAction.Remove(slot.id, slotLetter(board.indexOf(slot.id)), SlotEdit.RemoveWord(lineIndex, word))
    }
    return actions
}
