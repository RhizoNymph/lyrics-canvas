package dev.nymph.lyrics.logic.score

import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.score.Score

/** What a content save does to the snippet's score. */
sealed interface ScoreEdit {
    /** No score sent: the stored score follows the grid change (rest edit or reconcile). */
    data object Keep : ScoreEdit

    /** Explicit null ("Remove score"). */
    data object Remove : ScoreEdit

    /** A score sent with the save; validated against the new grid. */
    data class Set(val score: Score) : ScoreEdit
}

/** The snippet content the server stores after a save. */
data class SavedScoreContent(val rawText: String, val grid: GridData?, val score: Score?)

/**
 * The server's save flow run locally (round-4 contract section 3 / 9), so the optimistic or offline
 * copy equals what the server will store:
 *
 * 1. [ScoreEdit.Remove]: score null; grid and text as given.
 * 2. [ScoreEdit.Set]: the sent score, validated against [newGrid] (`Invalid` -> Err).
 * 3. [ScoreEdit.Keep] with no [storedScore]: nothing changes.
 * 4. [ScoreEdit.Keep] with a stored score: unchanged grid keeps it; a rest-only grid edit moves it
 *    ([applyGridRestEdit]); any other grid change [reconcile]s it.
 * 5. With a score, the rest link makes the grid canonical ([applyRestLink]) and the snippet text
 *    takes the rebuilt lines ([spliceLineTexts] of [changedLineTexts]). When the text does not have
 *    a line the grid has (`TextMismatch`; the server would not get there with a consistent
 *    snippet), the text is kept as given rather than refusing the save.
 */
fun applyScoreSave(storedScore: Score?, oldGrid: GridData?, newGrid: GridData?, rawText: String, edit: ScoreEdit): ScoreResult<SavedScoreContent> {
    val score: Score = when (edit) {
        ScoreEdit.Remove -> return ScoreResult.Ok(SavedScoreContent(rawText, newGrid, null))
        is ScoreEdit.Set -> {
            validateScore(edit.score, newGrid)?.let { return ScoreResult.Err(it) }
            edit.score
        }
        ScoreEdit.Keep -> when {
            storedScore == null -> return ScoreResult.Ok(SavedScoreContent(rawText, newGrid, null))
            newGrid == oldGrid -> storedScore
            isRestOnlyEdit(oldGrid, newGrid) -> when (val moved = applyGridRestEdit(storedScore, oldGrid, newGrid)) {
                is ScoreResult.Ok -> moved.value.score
                is ScoreResult.Err -> return moved
            }
            else -> when (val kept = reconcile(storedScore, oldGrid, newGrid)) {
                is ScoreResult.Ok -> kept.value
                is ScoreResult.Err -> return kept
            }
        }
    }
    val linked = when (val result = applyRestLink(score, newGrid)) {
        is ScoreResult.Ok -> result.value
        is ScoreResult.Err -> return result
    }
    val text = spliceLineTexts(rawText, changedLineTexts(newGrid, linked)).getOrNull() ?: rawText
    return ScoreResult.Ok(SavedScoreContent(text, linked, score))
}

/** `score_view` as stored: only meaningful (and only true) with a score. */
fun scoreViewAfter(score: Score?, requested: Boolean): Boolean = score != null && requested
