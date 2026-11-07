package dev.nymph.lyrics.app.voice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.nymph.lyrics.app.ui.theme.Palette

/**
 * "Use word bank as hints": the server prompts the transcriber with the user's bank words
 * (`meta.vocabulary_hints`). The whole row toggles, so it takes its own touches above the
 * recording tap surface.
 */
@Composable
fun VocabularyHintsSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Use word bank as hints", style = MaterialTheme.typography.labelMedium, color = Palette.FallbackText)
        Switch(checked = checked, onCheckedChange = null)
    }
}
