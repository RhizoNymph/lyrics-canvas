package dev.nymph.lyrics.logic.rhyme

import dev.nymph.lyrics.logic.jsTrim
import dev.nymph.lyrics.model.AnchorRange
import dev.nymph.lyrics.model.GridLine
import dev.nymph.lyrics.model.RhymeLimits
import dev.nymph.lyrics.model.Slot
import dev.nymph.lyrics.model.Snippet
import kotlin.math.floor

/** One character per cell; rests and missing stress are "0". Null for an empty line. */
fun slotFromGridLine(line: GridLine): Slot? {
    if (line.cells.isEmpty()) return null
    val stress = line.cells.joinToString("") { if (it.isRest) "0" else (it.stress ?: 0).toString() }
    return Slot(line.cells.size, stress)
}

fun lastNonEmptyLineIndex(snippet: Snippet): Int? {
    val lines = snippet.gridData?.lines ?: return null
    return lines.indices.lastOrNull { lines[it].cells.isNotEmpty() }
}

/** `lineIndex == null` selects the last non-empty line, matching the API's anchor semantics. */
fun slotFromSnippet(snippet: Snippet, lineIndex: Int?): Slot? {
    val lines = snippet.gridData?.lines ?: emptyList()
    val index = lineIndex ?: lastNonEmptyLineIndex(snippet) ?: return null
    return lines.getOrNull(index)?.let(::slotFromGridLine)
}

/** The slot of a word range: the cells of those words (interior rests included), else null. */
fun slotFromRange(snippet: Snippet, range: AnchorRange): Slot? {
    val line = gridLineOf(snippet, range.lineIndex) ?: return null
    return slotFromGridLine(sliceGridLine(line, range))
}

/** A stress string fits a slot when it has the slot's length and no stressed syllable (1 or 2) lands on a slot "0". */
fun fits(stress: String, slot: Slot): Boolean {
    if (stress.length != slot.stress.length) return false
    return stress.indices.none { stress[it] != '0' && slot.stress[it] == '0' }
}

enum class ManualSlotError { COUNT, STRESS }

sealed interface ManualSlotResult {
    data class Ok(val slot: Slot) : ManualSlotResult
    data class Invalid(val reason: ManualSlotError) : ManualSlotResult
}

private val JS_DECIMAL = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$")

/** JS `Number(text)` for decimal input: blank is 0, anything unparseable is NaN. */
private fun jsNumber(text: String): Double {
    val t = jsTrim(text)
    if (t.isEmpty()) return 0.0
    return if (JS_DECIMAL.matches(t)) t.toDouble() else Double.NaN
}

/** Validate the panel's manual slot inputs. */
fun parseManualSlot(countText: String, stress: String): ManualSlotResult {
    if (!RhymeLimits.STRESS_PATTERN.matches(stress)) return ManualSlotResult.Invalid(ManualSlotError.STRESS)
    val count = jsNumber(countText)
    val isInteger = count.isFinite() && floor(count) == count
    if (!isInteger || count <= 0 || count != stress.length.toDouble()) return ManualSlotResult.Invalid(ManualSlotError.COUNT)
    return ManualSlotResult.Ok(Slot(stress.length, stress))
}
