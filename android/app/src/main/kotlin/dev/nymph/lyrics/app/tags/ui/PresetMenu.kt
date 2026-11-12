package dev.nymph.lyrics.app.tags.ui

import dev.nymph.lyrics.app.sync.LocalSyncUi
import dev.nymph.lyrics.app.ui.NEEDS_CONNECTION
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.model.TagPreset

/** A menu action below the preset list. */
data class MenuAction(val label: String, val onClick: () -> Unit)

/** "Presets ▾": one tap applies a preset; [actions] follow the list (save as, manage). */
@Composable
fun PresetMenu(presets: List<TagPreset>, onApply: (TagPreset) -> Unit, actions: List<MenuAction> = emptyList()) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("Presets ▾", fontSize = 13.sp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (presets.isEmpty()) DropdownMenuItem(text = { Text("No presets yet", color = Palette.Muted) }, onClick = {}, enabled = false)
            presets.forEach { preset ->
                DropdownMenuItem(
                    text = {
                        androidx.compose.foundation.layout.Column {
                            Text(preset.name)
                            Text(preset.expression, color = Palette.Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    onClick = {
                        open = false
                        onApply(preset)
                    },
                )
            }
            if (actions.isNotEmpty()) HorizontalDivider()
            // Presets are saved on the server only.
            val online = LocalSyncUi.current.online
            actions.forEach { action ->
                DropdownMenuItem(text = { Text(if (online) action.label else "${action.label} ($NEEDS_CONNECTION)") }, enabled = online, onClick = {
                    open = false
                    action.onClick()
                })
            }
        }
    }
}
