package dev.nymph.lyrics.app.rhyme.pattern.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nymph.lyrics.app.rhyme.pattern.PatternSession
import dev.nymph.lyrics.app.rhyme.pattern.PatternViewModel
import dev.nymph.lyrics.app.rhyme.pattern.SlotChip
import dev.nymph.lyrics.app.rhyme.pattern.columnTitle
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.logic.rhyme.pattern.SlotColumn
import dev.nymph.lyrics.logic.wordbank.WordTextCheck
import dev.nymph.lyrics.logic.wordbank.validateWordText
import dev.nymph.lyrics.model.PatternCandidate
import dev.nymph.lyrics.model.SourceName
import java.util.Locale

/** Share of the pane one column takes, so the next one peeks. */
private const val COLUMN_FRACTION: Float = 0.45f

/** Columns never get narrower than this. */
private val MIN_COLUMN_WIDTH = 140.dp

/**
 * One independently scrollable list per on slot, in slot order, paged horizontally (no Cartesian
 * product): header (letter and key), rows (tap = pick for the tuple), and "More" for the next page.
 */
@Composable
fun SlotColumnsView(session: PatternSession, chips: List<SlotChip>, vm: PatternViewModel, onAddToBank: ((String) -> Unit)?, modifier: Modifier = Modifier) {
    val columns = session.shownColumns
    if (columns.isEmpty()) {
        Text(if (session.loading) "Searching…" else "No lists yet.", color = Palette.Muted, fontSize = 12.sp, modifier = modifier.padding(12.dp))
        return
    }
    val byId = chips.associateBy { it.id }
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(columns, key = { it.id.value }) { column ->
            val chip = byId[column.id] ?: return@items
            SlotColumnView(
                column,
                chip,
                session,
                vm,
                onAddToBank,
                Modifier.widthIn(min = MIN_COLUMN_WIDTH).fillParentMaxWidth(COLUMN_FRACTION).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun SlotColumnView(
    column: SlotColumn,
    chip: SlotChip,
    session: PatternSession,
    vm: PatternViewModel,
    onAddToBank: ((String) -> Unit)?,
    modifier: Modifier,
) {
    Column(modifier.border(BorderStroke(1.dp, Palette.Border), RoundedCornerShape(4.dp))) {
        Row(
            Modifier.fillMaxWidth().background(slotColor(chip).copy(alpha = 0.25f)).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(columnTitle(chip), fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${column.total}", color = Palette.Muted, fontSize = 11.sp)
        }
        LazyColumn(Modifier.weight(1f)) {
            if (column.candidates.isEmpty()) {
                item(key = "empty") { Text("No candidates for this slot.", color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.padding(8.dp)) }
            }
            items(column.candidates, key = { it.id }) { candidate ->
                PatternCandidateRow(
                    candidate = candidate,
                    picked = session.tray.isPicked(column.id, candidate.id),
                    pickColor = slotColor(chip),
                    onPick = { vm.togglePick(column.id, candidate) },
                    onDismiss = { vm.dismiss(candidate.id) },
                    onAddToBank = onAddToBank?.takeIf { validateWordText(candidate.text) is WordTextCheck.Valid }?.let { add -> { add(candidate.text) } },
                )
            }
            if (column.canLoadMore) {
                item(key = "more") {
                    OutlinedButton(onClick = { vm.loadMoreSlot(column.id) }, enabled = column.id !in session.pagingSlots, modifier = Modifier.fillMaxWidth().padding(6.dp)) {
                        Text(if (column.id in session.pagingSlots) "Loading…" else "More", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/** A slot row: text, score, fit marker, source badge, "+ bank", ×. A picked row is outlined in the slot colour. */
@Composable
private fun PatternCandidateRow(
    candidate: PatternCandidate,
    picked: Boolean,
    pickColor: Color,
    onPick: () -> Unit,
    onDismiss: () -> Unit,
    onAddToBank: (() -> Unit)?,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (picked) Modifier.background(pickColor.copy(alpha = 0.2f)).border(BorderStroke(2.dp, pickColor)) else Modifier)
            .clickable(onClick = onPick)
            .alpha(if (candidate.fitsSlot) 1f else Palette.NON_FIT_ALPHA)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(candidate.text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(String.format(Locale.US, "%.2f", candidate.score), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Palette.CellText)
            if (candidate.fitsSlot) Text("fits", fontSize = 10.sp, color = Palette.Fits)
            Text(
                if (candidate.source == SourceName.NOTES) "my line" else "dict",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Palette.provenance(candidate.provenance),
            )
            Spacer(Modifier.weight(1f))
            if (onAddToBank != null) {
                Text("+ bank", fontSize = 11.sp, color = Palette.CellText, modifier = Modifier.clickable(onClick = onAddToBank).padding(4.dp))
            }
            Text("×", fontSize = 14.sp, color = Palette.CellText, modifier = Modifier.clickable(onClick = onDismiss).padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }
    HorizontalDivider(color = Palette.RowDivider)
}
