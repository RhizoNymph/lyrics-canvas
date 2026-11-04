package dev.nymph.lyrics.app.wordbank.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nymph.lyrics.app.tags.ui.Hint
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.app.wordbank.Remote
import dev.nymph.lyrics.app.wordbank.RhymeView
import dev.nymph.lyrics.app.wordbank.WordBankUiState
import dev.nymph.lyrics.app.wordbank.WordBankViewModel
import dev.nymph.lyrics.app.wordbank.userMessage
import dev.nymph.lyrics.model.BankWord
import dev.nymph.lyrics.model.BankWordId
import java.util.Locale

private fun score(value: Double): String = String.format(Locale.US, "%.2f", value)

/** Families of the filtered bank, or the bank ranked against one word / typed text. Matches toggle the selection. */
@Composable
internal fun RhymesTab(state: WordBankUiState, vm: WordBankViewModel, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = state.rhymeView == RhymeView.FAMILIES, onClick = { vm.setRhymeView(RhymeView.FAMILIES) }, label = { Text("Families") })
            FilterChip(selected = state.rhymeView == RhymeView.RHYMES_WITH, onClick = { vm.setRhymeView(RhymeView.RHYMES_WITH) }, label = { Text("Rhymes with") })
        }
        when (state.rhymeView) {
            RhymeView.FAMILIES -> Families(state, vm)
            RhymeView.RHYMES_WITH -> RhymesWith(state, vm)
        }
    }
}

@Composable
private fun MinScore(label: String, value: Double, onChange: (Double) -> Unit, onDone: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$label ${score(value)}", fontSize = 12.sp, color = Palette.Muted, modifier = Modifier.width(110.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toDouble()) },
            onValueChangeFinished = onDone,
            valueRange = 0f..1f,
            steps = 19,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MatchRow(word: BankWord, selected: Boolean, onToggle: (BankWordId) -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().clickable { onToggle(word.id) }.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = selected, onCheckedChange = { onToggle(word.id) })
        Text(word.text, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun Families(state: WordBankUiState, vm: WordBankViewModel) {
    MinScore("Min score", state.families.minScore, vm::setFamiliesMinScore, vm::loadFamilies)
    when (val result = state.families.result) {
        Remote.Idle -> Hint("Families appear here.", Palette.Muted)
        Remote.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
        is Remote.Failed -> FailedLine(result.error.userMessage(), vm::loadFamilies)
        is Remote.Loaded -> LazyColumn(Modifier.fillMaxSize()) {
            val response = result.value
            if (response.families.isEmpty() && response.unmatched.isEmpty()) item { Hint("No words match the filter.") }
            response.families.forEachIndexed { index, family ->
                group(
                    key = "family-$index",
                    title = "${family.label}  ·  ${family.words.size} words  ·  cohesion ${score(family.cohesion)}",
                    words = family.words,
                    state = state,
                    vm = vm,
                )
            }
            if (response.unmatched.isNotEmpty()) group("unmatched", "Unmatched  ·  ${response.unmatched.size}", response.unmatched, state, vm)
        }
    }
}

private fun LazyListScope.group(key: String, title: String, words: List<BankWord>, state: WordBankUiState, vm: WordBankViewModel) {
    item(key = "$key-header") {
        HorizontalDivider(color = Palette.Border)
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.selectAll(words.map { it.id }) }) { Text("Select all", fontSize = 12.sp) }
        }
    }
    items(words, key = { "$key-${it.id.value}" }) { word -> MatchRow(word, word.id in state.selection, vm::toggleSelected) }
}

@Composable
private fun RhymesWith(state: WordBankUiState, vm: WordBankViewModel) {
    val rhymes = state.rhymesWith
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = rhymes.typed,
            onValueChange = vm::setRhymesTyped,
            label = { Text("Rhymes with (or select one word)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.loadRhymesWith() }),
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = vm::loadRhymesWith) { Text("Find") }
    }
    MinScore("Min score", rhymes.minScore, vm::setRhymesMinScore, vm::loadRhymesWith)
    if (rhymes.needsAnchor) Hint("Select exactly one word, or type one.", Palette.Muted)
    when (val result = rhymes.result) {
        Remote.Idle -> Unit
        Remote.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
        is Remote.Failed -> FailedLine(result.error.userMessage(), vm::loadRhymesWith)
        is Remote.Loaded -> LazyColumn(Modifier.fillMaxSize()) {
            val response = result.value
            item(key = "anchor") {
                Text(
                    "${response.anchor.text}  ·  ${response.anchor.rhymeKey.joinToString(" ")}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Palette.Muted,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            if (response.matches.isEmpty()) item { Hint("No bank words rhyme at this score.") }
            items(response.matches, key = { it.word.id.value }) { match ->
                MatchRow(match.word, match.word.id in state.selection, vm::toggleSelected) {
                    LinearProgressIndicator(progress = { match.score.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.width(72.dp))
                    Text(score(match.score), fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
internal fun FailedLine(text: String, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Palette.Error, fontSize = 12.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry) { Text("Retry", fontSize = 12.sp) }
    }
}
