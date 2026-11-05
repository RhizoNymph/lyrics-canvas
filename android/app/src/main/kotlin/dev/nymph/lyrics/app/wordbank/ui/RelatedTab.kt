package dev.nymph.lyrics.app.wordbank.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nymph.lyrics.app.tags.ui.Hint
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.app.wordbank.RELATED_WARMING_MESSAGE
import dev.nymph.lyrics.app.wordbank.RelatedStatus
import dev.nymph.lyrics.app.wordbank.WordBankUiState
import dev.nymph.lyrics.app.wordbank.WordBankViewModel
import dev.nymph.lyrics.app.wordbank.userMessage
import dev.nymph.lyrics.logic.wordbank.MAX_RELATED_RETRIES
import java.util.Locale

/** "Related words": words near a word, phrase or theme by meaning; "+" adds one with the add box's tags. */
@Composable
internal fun RelatedTab(state: WordBankUiState, vm: WordBankViewModel, modifier: Modifier = Modifier) {
    val related = state.related
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = related.query,
                onValueChange = vm::setRelatedQuery,
                label = { Text("Related words") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.searchRelated() }),
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = vm::searchRelated) { Text("Search") }
        }
        if (state.add.tagIds.isNotEmpty()) Hint("“+” adds with the add box's tags.", Palette.Muted)
        when (val status = related.status) {
            RelatedStatus.Idle -> Unit
            RelatedStatus.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            is RelatedStatus.Warming -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Hint("$RELATED_WARMING_MESSAGE (retry ${status.retries + 1} of $MAX_RELATED_RETRIES)", Palette.Muted)
            }
            is RelatedStatus.Invalid -> Hint(status.message, Palette.Error)
            is RelatedStatus.Failed -> FailedLine(status.error.userMessage(), vm::searchRelated)
            is RelatedStatus.Loaded -> LazyColumn(Modifier.fillMaxSize()) {
                if (status.response.results.isEmpty()) item { Hint("No related words.") }
                items(status.response.results, key = { it.text }) { result ->
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(result.text, modifier = Modifier.weight(1f))
                        Text(String.format(Locale.US, "%.2f", result.similarity), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Palette.Muted)
                        if (result.bankWordId != null) {
                            Text("in bank", fontSize = 12.sp, color = Palette.Fits, modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp))
                        } else {
                            val adding = result.text in related.adding
                            TextButton(onClick = { vm.addRelated(result.text) }, enabled = !adding) { Text(if (adding) "…" else "+") }
                        }
                    }
                    HorizontalDivider(color = Palette.RowDivider)
                }
            }
        }
    }
}
