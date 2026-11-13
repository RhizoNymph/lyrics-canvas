package dev.nymph.lyrics.app.rhyme.pattern

import dev.nymph.lyrics.logic.rhyme.pattern.BoardSlot
import dev.nymph.lyrics.logic.rhyme.pattern.SlotBoard
import dev.nymph.lyrics.logic.rhyme.pattern.SlotId
import dev.nymph.lyrics.logic.rhyme.pattern.TupleTray
import dev.nymph.lyrics.logic.rhyme.pattern.slotLetter
import dev.nymph.lyrics.model.LineMatch
import dev.nymph.lyrics.model.PatternLine
import dev.nymph.lyrics.model.PatternSlot

/** A legend chip: `A  EH D IY  3/3`. */
data class SlotChip(
    val id: SlotId,
    val letter: String,
    /** Position in the board: picks the slot colour. */
    val index: Int,
    /** The last member's rhyme key, from the server's view of this exact slot; null while it is being recomputed. */
    val key: String?,
    val coverage: Int,
    val lines: Int,
    val on: Boolean,
    val full: Boolean,
) {
    val coverageText: String get() = "$coverage/$lines"

    /** Partial or off slots are drawn dimmed. */
    val dimmed: Boolean get() = !on || !full
}

/** One word of a pattern line as drawn in the lines block. */
data class LineWord(
    val lineIndex: Int,
    val word: Int,
    val text: String,
    /** The chip of the slot holding the word, if any. */
    val slot: SlotChip?,
    val selected: Boolean,
)

/** The server's slot is current when it has exactly the board slot's members. */
fun serverViewMatches(slot: BoardSlot, info: PatternSlot?): Boolean = info != null && info.members.map { it.ref } == slot.refs()

fun slotChips(board: SlotBoard, info: Map<SlotId, PatternSlot>): List<SlotChip> = board.slots.mapIndexed { index, slot ->
    val server = info[slot.id]?.takeIf { serverViewMatches(slot, it) }
    SlotChip(
        id = slot.id,
        letter = slotLetter(index),
        index = index,
        key = server?.members?.lastOrNull()?.rhymeKey?.joinToString(" "),
        coverage = slot.coverage,
        lines = board.linesWithWords,
        on = slot.on,
        full = board.isFull(slot),
    )
}

/** The pattern lines' words with their slot (for highlighting) and the open picker's word marked. */
fun lineWords(lines: List<PatternLine>, board: SlotBoard, chips: List<SlotChip>, picker: WordRef?): List<List<LineWord>> {
    val byId = chips.associateBy { it.id }
    return lines.map { line ->
        line.words.map { w ->
            val slot = board.slotAt(line.lineIndex, w.wordIndex)?.let { byId[it.id] }
            LineWord(line.lineIndex, w.wordIndex, w.text, slot, picker == WordRef(line.lineIndex, w.wordIndex))
        }
    }
}

/** The column header: letter and key (`A · EH D IY`). */
fun columnTitle(chip: SlotChip): String = if (chip.key == null) chip.letter else "${chip.letter} · ${chip.key}"

/** "Add picks to word bank": the valid picks joined by commas (the quick-add splits on commas); null with none. */
fun bankText(tray: TupleTray, order: List<SlotId>): String? = tray.bankWords(order).takeIf { it.isNotEmpty() }?.joinToString(", ")

/** A whole-line match's words, each with the chip of the slot it hits (in that slot's colour), if any. */
fun matchWords(match: LineMatch, chips: List<SlotChip>): List<Pair<String, SlotChip?>> {
    val byId = chips.associateBy { it.id.value }
    val words = match.unit.text.split(Regex("\\s+")).filter { it.isNotEmpty() }
    return words.mapIndexed { index, word ->
        val hit = match.hits.firstOrNull { index >= it.wordStart && index < it.wordEnd }
        word to hit?.let { byId[it.slotId] }
    }
}

/** "3 of 3 slots". */
fun slotsHitText(match: LineMatch, slotCount: Int): String = "${match.slotsHit} of $slotCount slots"
