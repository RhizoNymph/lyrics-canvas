package dev.nymph.lyrics.app.rhyme.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.nymph.lyrics.app.rhyme.RhymeUiState
import dev.nymph.lyrics.app.rhyme.RhymeViewModel
import dev.nymph.lyrics.app.rhyme.pattern.PatternSeed
import dev.nymph.lyrics.app.rhyme.pattern.PatternUiState
import dev.nymph.lyrics.app.rhyme.pattern.PatternViewModel
import dev.nymph.lyrics.app.rhyme.pattern.ui.PatternEntry
import dev.nymph.lyrics.app.rhyme.pattern.ui.PatternPanel
import dev.nymph.lyrics.app.tags.TagsUiState

/** The single-mode controls a pattern session starts from. */
fun RhymeUiState.patternSeed(): PatternSeed = PatternSeed(params.minScore, params.sources, params.includeRare, tagFilter)

/**
 * The rhyme sheet's content: pattern mode while a pattern session is active, else the single-anchor
 * panel (whose chips can start pattern mode). [onClose] must end both sessions.
 */
@Composable
fun RhymeSidebar(
    rhyme: RhymeUiState,
    rhymeVm: RhymeViewModel,
    pattern: PatternUiState,
    patternVm: PatternViewModel,
    tags: TagsUiState<*>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onAddToBank: ((String) -> Unit)? = null,
) {
    val session = pattern.session
    if (session != null) {
        PatternPanel(pattern, session, patternVm, tags, onClose, modifier, onAddToBank)
    } else {
        RhymePanel(rhyme, rhymeVm, tags, onClose, modifier, onAddToBank, PatternEntry(pattern, patternVm, rhyme.patternSeed()))
    }
}
