package dev.nymph.lyrics.logic.rhyme.pattern

import dev.nymph.lyrics.logic.canvas.Point
import dev.nymph.lyrics.logic.wordbank.WordTextCheck
import dev.nymph.lyrics.logic.wordbank.validateWordText
import dev.nymph.lyrics.model.SnippetCreateRequest
import dev.nymph.lyrics.model.SnippetType

/** A row picked for a slot. */
data class TuplePick(val candidateId: String, val text: String)

/** One tray position, in slot order. */
sealed interface TrayCell {
    val slot: SlotId

    data class Picked(override val slot: SlotId, val pick: TuplePick) : TrayCell

    data class Gap(override val slot: SlotId) : TrayCell
}

/** What a gap shows in the tray line. */
const val TRAY_GAP: String = "___"

/** The tuple being built: at most one pick per slot. */
data class TupleTray(val picks: Map<SlotId, TuplePick> = emptyMap()) {
    val isEmpty: Boolean get() = picks.isEmpty()

    fun isPicked(slot: SlotId, candidateId: String): Boolean = picks[slot]?.candidateId == candidateId

    /** Tap a row: pick it for its slot (replacing that slot's pick), or unpick it when it is the pick. */
    fun toggle(slot: SlotId, candidateId: String, text: String): TupleTray =
        if (isPicked(slot, candidateId)) TupleTray(picks - slot) else TupleTray(picks + (slot to TuplePick(candidateId, text)))

    /** Drop picks of slots not in [slots] (dropped, or turned off). */
    fun prune(slots: Collection<SlotId>): TupleTray {
        val keep = slots.toSet()
        return if (picks.keys.all { it in keep }) this else TupleTray(picks.filterKeys { it in keep })
    }

    fun cells(order: List<SlotId>): List<TrayCell> = order.map { slot -> picks[slot]?.let { TrayCell.Picked(slot, it) } ?: TrayCell.Gap(slot) }

    /** The tray line: `steady · ___ · strong`. */
    fun display(order: List<SlotId>): String = cells(order).joinToString(" · ") { cell ->
        when (cell) {
            is TrayCell.Picked -> cell.pick.text
            is TrayCell.Gap -> TRAY_GAP
        }
    }

    /** The picks in slot order (gaps skipped). */
    fun pickedTexts(order: List<SlotId>): List<String> = order.mapNotNull { picks[it]?.text }

    /** "Make snippet" text: one line, the picks in slot order joined by spaces; null with no picks. */
    fun snippetText(order: List<SlotId>): String? = pickedTexts(order).takeIf { it.isNotEmpty() }?.joinToString(" ")

    /** "Add picks to word bank": the picks that are valid word bank entries, in slot order, without repeats. */
    fun bankWords(order: List<SlotId>): List<String> =
        pickedTexts(order).filter { validateWordText(it) is WordTextCheck.Valid }.distinctBy { it.lowercase() }
}

/** The create body for "Make snippet" (next to the anchor, like materialize; no `derived_from`). */
fun makeSnippetRequest(tray: TupleTray, order: List<SlotId>, position: Point): SnippetCreateRequest? {
    val text = tray.snippetText(order) ?: return null
    return SnippetCreateRequest(positionX = position.x, positionY = position.y, snippetType = SnippetType.FREESTYLE, rawText = text)
}
