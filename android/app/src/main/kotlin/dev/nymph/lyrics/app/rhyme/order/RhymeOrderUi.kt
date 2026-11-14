package dev.nymph.lyrics.app.rhyme.order

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.logic.rhyme.BandedItem
import dev.nymph.lyrics.logic.rhyme.MEANING_UNAVAILABLE_NOTE
import dev.nymph.lyrics.logic.rhyme.OrderPresentation
import dev.nymph.lyrics.logic.rhyme.label
import dev.nymph.lyrics.logic.rhyme.similarityPercent
import dev.nymph.lyrics.model.RhymeBand
import dev.nymph.lyrics.model.RhymeOrder

/** "Order: [Rhyme] [Rhyme, then meaning]": one chip per order, the current one selected. */
@Composable
fun RhymeOrderToggle(order: RhymeOrder, onOrder: (RhymeOrder) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Order", color = Palette.Muted, fontSize = 11.sp)
        RhymeOrder.entries.forEach { option ->
            FilterChip(selected = order == option, onClick = { if (option != order) onOrder(option) }, label = { Text(option.label, fontSize = 12.sp) })
        }
    }
}

/** A band section header (Perfect / Strong / Near / Loose). */
@Composable
fun BandHeader(band: RhymeBand, modifier: Modifier = Modifier) {
    Text(
        band.label,
        color = Palette.Muted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .fillMaxWidth()
            .background(Palette.Border.copy(alpha = 0.4f))
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .semantics { heading() },
    )
}

/** The quiet "≈NN%" meaning label of a row; nothing without a similarity. */
@Composable
fun SimilarityLabel(similarity: Double?) {
    val percent = similarityPercent(similarity) ?: return
    Text(
        "≈$percent%",
        color = Palette.Muted,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.semantics { contentDescription = "meaning $percent percent" },
    )
}

/** "Meaning order unavailable" when meaning was asked and the server answered in rhyme order. */
@Composable
fun OrderNote(presentation: OrderPresentation, modifier: Modifier = Modifier) {
    if (presentation != OrderPresentation.MEANING_UNAVAILABLE) return
    Text(MEANING_UNAVAILABLE_NOTE, color = Palette.Muted, fontSize = 11.sp, modifier = modifier)
}

/**
 * A stable lazy-list key for entry [index]: a row's own id, a header keyed by the row it heads
 * (a band can only head one row, so keys never repeat).
 */
fun <T> bandedKey(entries: List<BandedItem<T>>, index: Int, id: (T) -> String): String =
    when (val entry = entries[index]) {
        is BandedItem.Row -> id(entry.value)
        is BandedItem.Header -> {
            val next = entries.getOrNull(index + 1) as? BandedItem.Row
            "band:${entry.band.name}:${next?.let { id(it.value) } ?: index}"
        }
    }
