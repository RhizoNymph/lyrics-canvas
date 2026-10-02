package dev.nymph.lyrics.app.rhyme.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.model.RhymeCandidate
import dev.nymph.lyrics.model.RhymeUnit
import java.util.Locale

/**
 * One result (CandidateRow.tsx): text (tap = search for it instead), score, depth, fit badge,
 * provenance; the syllable strip; tap the row to expand the alignment; Materialize / Dismiss.
 */
@Composable
fun CandidateRow(
    candidate: RhymeCandidate,
    anchor: RhymeUnit?,
    hasSlot: Boolean,
    expanded: Boolean,
    materializing: Boolean,
    onToggle: () -> Unit,
    onChain: () -> Unit,
    onMaterialize: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colour = Palette.provenance(candidate.provenance)
    val dimmed = hasSlot && !candidate.fitsSlot
    Column(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .drawBehind {
                drawLine(colour, Offset(1.5.dp.toPx(), 0f), Offset(1.5.dp.toPx(), size.height), strokeWidth = 3.dp.toPx())
                drawLine(Palette.RowDivider, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
            }
            .alpha(if (dimmed) Palette.NON_FIT_ALPHA else 1f)
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                candidate.text,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.weight(1f, fill = false).clickable(onClick = onChain).padding(vertical = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(String.format(Locale.US, "%.2f", candidate.score), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Palette.CellText)
            Text("d${candidate.matchDepth}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Palette.Muted)
            if (hasSlot && candidate.fitsSlot) {
                Text(
                    "fits slot",
                    fontSize = 10.sp,
                    color = Palette.Fits,
                    modifier = Modifier.border(BorderStroke(1.dp, Palette.Fits), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp),
                )
            }
            Text(Palette.provenanceLabel(candidate.provenance), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = colour)
        }
        SyllableStrip(candidate.gridLine)
        if (expanded) AlignmentTable(candidate, anchor)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
            Button(
                onClick = onMaterialize,
                enabled = !materializing,
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) { Text(if (materializing) "Adding…" else "Materialize", fontSize = 12.sp) }
            OutlinedButton(onClick = onDismiss, contentPadding = ButtonDefaults.TextButtonContentPadding) { Text("Dismiss", fontSize = 12.sp) }
        }
    }
}
