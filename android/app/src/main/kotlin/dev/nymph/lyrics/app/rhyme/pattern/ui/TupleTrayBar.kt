package dev.nymph.lyrics.app.rhyme.pattern.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nymph.lyrics.app.rhyme.pattern.PatternSession
import dev.nymph.lyrics.app.rhyme.pattern.PatternViewModel
import dev.nymph.lyrics.app.rhyme.pattern.bankText
import dev.nymph.lyrics.app.ui.theme.Palette

/** The tuple tray: one pick per on slot in slot order with gaps (`steady · ___ · strong`), Make snippet, + bank, Clear. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TupleTrayBar(session: PatternSession, vm: PatternViewModel, onAddToBank: ((String) -> Unit)?) {
    val order = session.order
    if (order.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(session.tray.display(order), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = if (session.tray.isEmpty) Palette.Muted else Palette.Text)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = vm::makeSnippet,
                enabled = !session.tray.isEmpty && !session.creating,
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) { Text(if (session.creating) "Adding…" else "Make snippet", fontSize = 12.sp) }
            val bank = bankText(session.tray, order)
            if (onAddToBank != null) {
                OutlinedButton(onClick = { bank?.let(onAddToBank) }, enabled = bank != null, contentPadding = ButtonDefaults.TextButtonContentPadding) {
                    Text("Add picks to word bank", fontSize = 12.sp)
                }
            }
            OutlinedButton(onClick = vm::clearTray, enabled = !session.tray.isEmpty, contentPadding = ButtonDefaults.TextButtonContentPadding) {
                Text("Clear", fontSize = 12.sp)
            }
        }
    }
}
