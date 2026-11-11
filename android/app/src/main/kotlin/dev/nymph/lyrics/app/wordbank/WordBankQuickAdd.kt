package dev.nymph.lyrics.app.wordbank

import dev.nymph.lyrics.logic.rests.displayText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nymph.lyrics.data.repository.WordBankRepository
import dev.nymph.lyrics.logic.wordbank.AddPlan
import dev.nymph.lyrics.logic.wordbank.addSummary
import dev.nymph.lyrics.logic.wordbank.planAdd
import dev.nymph.lyrics.logic.wordbank.toWordBankError
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Adding to the word bank from elsewhere: a rhyme candidate's "+ bank" and the editor's "Add to
 * word bank" on a text selection. The text's rest marks are stripped (display text), then it is trimmed, split on commas and newlines, validated and
 * posted (no tags); [messages] carries the result line for a toast. Lives in the canvas back-stack
 * entry, so the canvas and the editor share it.
 */
class WordBankQuickAddViewModel(private val repo: WordBankRepository) : ViewModel() {
    private val _messages = Channel<String>(Channel.BUFFERED)

    val messages: Flow<String> = _messages.receiveAsFlow()

    fun add(raw: String) {
        // Snippet text (an editor selection) can carry rest marks; the bank gets the words only.
        val words = when (val plan = planAdd(displayText(raw))) {
            AddPlan.Nothing -> return
            is AddPlan.Invalid, is AddPlan.TooMany -> return message(checkNotNull(plan.problemMessage()))
            is AddPlan.Ready -> plan.words.map { it.value }
        }
        viewModelScope.launch {
            when (val result = repo.add(words)) {
                is ApiResult.Success -> message(addSummary(result.value.created.size, result.value.existing.size))
                is ApiResult.Failure -> message(toWordBankError(result.error).userMessage())
            }
        }
    }

    private fun message(text: String) {
        _messages.trySend(text)
    }
}
