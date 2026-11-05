package dev.nymph.lyrics.app.wordbank

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.nymph.lyrics.app.di.AppContainer
import dev.nymph.lyrics.app.tags.ForwardTagChanges
import dev.nymph.lyrics.app.tags.TagControlsViewModel
import dev.nymph.lyrics.model.BankWordId

/** The quick-add ViewModel of a canvas back-stack entry (shared by the canvas's rhyme sheet and the editor). */
@Composable
fun wordBankQuickAdd(
    container: AppContainer,
    owner: ViewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current),
): WordBankQuickAddViewModel = viewModel(owner) { WordBankQuickAddViewModel(container.wordBank) }

/** Show each quick-add result as a toast. */
@Composable
fun QuickAddToasts(quickAdd: WordBankQuickAddViewModel) {
    val context = LocalContext.current
    LaunchedEffect(quickAdd) { quickAdd.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }
}

/** The word bank screen's ViewModel, creating drafts on [canvas]. */
@Composable
fun wordBankViewModel(container: AppContainer, canvas: WordBankCanvasPort): WordBankViewModel =
    viewModel { WordBankViewModel(container.wordBank, canvas) }

/**
 * The word bank's tag controls: its own filter (remembered under `word_bank_filter`), the shared
 * presets, the picker on words. Tag creates, edits and deletes are forwarded to [canvasTags], so
 * the canvas below shows them on return.
 */
@Composable
fun wordBankTagsViewModel(container: AppContainer, bank: WordBankViewModel, canvasTags: TagControlsViewModel<*>): TagControlsViewModel<BankWordId> =
    viewModel(key = "word-bank-tags") {
        TagControlsViewModel(container.tags, container.tagPresets, container.wordBankFilter, bank, ForwardTagChanges(canvasTags))
    }
