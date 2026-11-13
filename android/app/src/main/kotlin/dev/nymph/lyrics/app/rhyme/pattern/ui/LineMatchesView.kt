package dev.nymph.lyrics.app.rhyme.pattern.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nymph.lyrics.app.rhyme.pattern.PatternSession
import dev.nymph.lyrics.app.rhyme.pattern.PatternViewModel
import dev.nymph.lyrics.app.rhyme.pattern.SlotChip
import dev.nymph.lyrics.app.rhyme.pattern.matchWords
import dev.nymph.lyrics.app.rhyme.pattern.slotsHitText
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.model.LineMatch
import java.util.Locale

/** The user's own lines that already hit 2+ slots in order; tap one to materialize it next to the anchor. */
@Composable
fun LineMatchesView(session: PatternSession, chips: List<SlotChip>, vm: PatternViewModel, modifier: Modifier = Modifier) {
    val list = session.lineMatches
    LazyColumn(modifier) {
        if (list.matches.isEmpty()) {
            item(key = "empty") {
                Text(
                    if (session.loading) "Searching…" else "None of your lines hit 2 or more of these slots.",
                    color = Palette.Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        items(list.matches, key = { it.unit.id }) { match ->
            LineMatchRow(match, chips, session.order.size, match.unit.id in session.materializing, vm)
        }
        if (list.canLoadMore) {
            item(key = "more") {
                OutlinedButton(onClick = vm::loadMoreLines, enabled = !session.pagingLines, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Text(if (session.pagingLines) "Loading…" else "More", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun LineMatchRow(match: LineMatch, chips: List<SlotChip>, slotCount: Int, materializing: Boolean, vm: PatternViewModel) {
    val text = buildAnnotatedString {
        matchWords(match, chips).forEachIndexed { index, (word, chip) ->
            if (index > 0) append(" ")
            if (chip == null) append(word) else withStyle(SpanStyle(color = slotColor(chip), fontWeight = FontWeight.Bold)) { append(word) }
        }
    }
    Column(
        Modifier.fillMaxWidth().clickable(enabled = !materializing) { vm.materializeLine(match.unit.id) }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(slotsHitText(match, slotCount), color = Palette.Muted, fontSize = 11.sp)
            Text(String.format(Locale.US, "%.2f", match.score), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Palette.CellText)
            if (materializing) Text("Adding…", color = Palette.Muted, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Text("×", fontSize = 14.sp, color = Palette.CellText, modifier = Modifier.clickable { vm.dismiss(match.unit.id) }.padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }
    HorizontalDivider(color = Palette.RowDivider)
}
