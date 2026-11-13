package dev.nymph.lyrics.logic.rhyme.pattern

import dev.nymph.lyrics.model.PatternDetectResponse
import dev.nymph.lyrics.model.PatternLimits
import dev.nymph.lyrics.model.SlotSpec
import dev.nymph.lyrics.model.SpanRef

/** A slot's id: `s0`.. from detection, `u0`.. for slots the user started. */
@JvmInline
value class SlotId(val value: String)

/** A word range `[start, end)` of one line; never empty. */
data class WordSpan(val start: Int, val end: Int) {
    init {
        require(start in 0 until end) { "bad word span [$start, $end)" }
    }

    operator fun contains(word: Int): Boolean = word in start until end

    /** True when this span lies entirely before [other] on the same line. */
    fun before(other: WordSpan): Boolean = end <= other.start

    companion object {
        fun word(index: Int): WordSpan = WordSpan(index, index + 1)
    }
}

/** A pattern line: its absolute index in the snippet and how many words it has. */
data class BoardLine(val lineIndex: Int, val wordCount: Int)

/** One editable slot: at most one member per line, at least one member. */
data class BoardSlot(
    val id: SlotId,
    /** Line index -> member span. */
    val members: Map<Int, WordSpan>,
    /** Off slots are not queried and have no list; detection decides the start value (`default_on`). */
    val on: Boolean,
) {
    init {
        require(members.isNotEmpty()) { "a slot needs a member" }
    }

    val coverage: Int get() = members.size

    /** The member on [lineIndex] containing [word], if any. */
    fun holds(lineIndex: Int, word: Int): Boolean = members[lineIndex]?.contains(word) == true

    /** The members as the request sends them, in line order. */
    fun refs(): List<SpanRef> = members.entries.sortedBy { it.key }.map { (line, span) -> SpanRef(line, span.start, span.end) }
}

/**
 * The editable slots of one pattern, in slot order. Invariants (the server's `pattern_span_invalid`
 * and `pattern_slot_order` rules, kept locally): every member lies inside its line, members of
 * different slots never overlap on a line, and on every line the members follow slot order; ids
 * are unique; at most [PatternLimits.MAX_SLOTS] slots. Only [SlotBoard.fromDetect] and the edit
 * reducer ([applySlotEdit]) build one.
 */
@ConsistentCopyVisibility
data class SlotBoard internal constructor(
    val lines: List<BoardLine>,
    val slots: List<BoardSlot>,
    /** The next `u<n>` suffix to try for a user slot (never reused within a board's history). */
    internal val nextUser: Int,
) {
    /** Pattern lines that have words; a slot covering all of them is full. */
    val linesWithWords: Int get() = lines.count { it.wordCount > 0 }

    val onSlots: List<BoardSlot> get() = slots.filter { it.on }

    fun slot(id: SlotId): BoardSlot? = slots.firstOrNull { it.id == id }

    fun indexOf(id: SlotId): Int = slots.indexOfFirst { it.id == id }

    fun line(lineIndex: Int): BoardLine? = lines.firstOrNull { it.lineIndex == lineIndex }

    /** The slot holding [word] of [lineIndex], if any. */
    fun slotAt(lineIndex: Int, word: Int): BoardSlot? = slots.firstOrNull { it.holds(lineIndex, word) }

    fun isFull(slot: BoardSlot): Boolean = slot.coverage >= linesWithWords

    /** The on slots as the suggest request sends them (ids kept, k derived by the server). */
    fun specs(): List<SlotSpec> = onSlots.map { SlotSpec(id = it.id.value, members = it.refs(), k = null) }

    companion object {
        /**
         * The board for a detect response, in the server's slot order with `default_on`. A slot that
         * would break an invariant against the ones before it (which a correct server never sends)
         * is left out.
         */
        fun fromDetect(response: PatternDetectResponse): SlotBoard {
            val lines = response.lines.map { BoardLine(it.lineIndex, it.words.size) }
            val accepted = mutableListOf<BoardSlot>()
            for (slot in response.slots) {
                if (accepted.size >= PatternLimits.MAX_SLOTS) break
                if (accepted.any { it.id.value == slot.id }) continue
                val spans = slot.members.mapNotNull { m -> spanOrNull(m.wordStart, m.wordEnd)?.let { m.lineIndex to it } }
                if (spans.size != slot.members.size || spans.isEmpty() || spans.map { it.first }.toSet().size != spans.size) continue
                val candidate = BoardSlot(SlotId(slot.id), spans.toMap(), slot.defaultOn)
                if (orderViolation(lines, accepted + candidate) == null) accepted += candidate
            }
            return SlotBoard(lines, accepted, 0)
        }

        /** An empty board over [lines] (no slots detected). */
        fun empty(lines: List<BoardLine>): SlotBoard = SlotBoard(lines, emptyList(), 0)

        private fun spanOrNull(start: Int, end: Int): WordSpan? = if (start in 0 until end) WordSpan(start, end) else null
    }
}

/** Where a slot list breaks the board invariants: a member outside its line, or two slots out of order / overlapping. */
internal sealed interface Violation {
    data class OutsideLine(val slot: SlotId, val lineIndex: Int) : Violation

    /** On [lineIndex], [later] (after [earlier] in slot order) does not come after it in the line. */
    data class Order(val lineIndex: Int, val earlier: SlotId, val later: SlotId) : Violation
}

internal fun orderViolation(lines: List<BoardLine>, slots: List<BoardSlot>): Violation? {
    val words = lines.associate { it.lineIndex to it.wordCount }
    for (slot in slots) {
        for ((line, span) in slot.members) {
            val count = words[line] ?: return Violation.OutsideLine(slot.id, line)
            if (span.end > count) return Violation.OutsideLine(slot.id, line)
        }
    }
    for (line in lines) {
        var previous: BoardSlot? = null
        for (slot in slots) {
            val span = slot.members[line.lineIndex] ?: continue
            val prior = previous
            if (prior != null && !prior.members.getValue(line.lineIndex).before(span)) {
                return Violation.Order(line.lineIndex, prior.id, slot.id)
            }
            previous = slot
        }
    }
    return null
}

/** The slot's letter in the legend and the list headers: A, B, C... by position. */
fun slotLetter(index: Int): String = if (index in 0 until 26) ('A' + index).toString() else "S${index + 1}"
