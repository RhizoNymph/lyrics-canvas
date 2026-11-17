package dev.nymph.lyrics.app.rhyme.rhythm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.logic.rhyme.RHYTHM_FIT_LABEL
import dev.nymph.lyrics.logic.rhyme.rhythmPercent

/**
 * The "Fit rhythm" switch with its unavailable note ([note], e.g. "Fit rhythm unavailable: this
 * snippet has no score"). The caller shows it only for snippet anchors.
 */
@Composable
fun RhythmFitToggle(on: Boolean, onChange: (Boolean) -> Unit, note: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = on, onCheckedChange = onChange, modifier = Modifier.semantics { contentDescription = RHYTHM_FIT_LABEL })
            Column {
                Text(RHYTHM_FIT_LABEL, fontSize = 13.sp)
                Text("Rank by the snippet's placed rhythm", color = Palette.Muted, fontSize = 11.sp)
            }
        }
        if (note != null) Text(note, color = Palette.Muted, fontSize = 11.sp)
    }
}

/** A row's quiet "♩NN%" rhythm label; nothing without a fit. */
@Composable
fun RhythmFitLabel(fit: Double?) {
    val percent = rhythmPercent(fit) ?: return
    Text(
        "♩$percent%",
        color = Palette.Muted,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.semantics { contentDescription = "rhythm $percent percent" },
    )
}
