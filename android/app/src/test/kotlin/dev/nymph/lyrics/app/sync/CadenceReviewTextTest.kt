package dev.nymph.lyrics.app.sync

import dev.nymph.lyrics.model.SnippetType
import dev.nymph.lyrics.model.score.Score
import dev.nymph.lyrics.model.score.ScoreLine
import dev.nymph.lyrics.model.score.ScoreMeter
import dev.nymph.lyrics.model.score.ScoreNote
import dev.nymph.lyrics.model.score.ScoreSnap
import dev.nymph.lyrics.model.score.ScoreTempo
import dev.nymph.lyrics.sync.conflict.EntityKind
import dev.nymph.lyrics.sync.conflict.ReviewItem
import dev.nymph.lyrics.sync.conflict.ReviewSide
import dev.nymph.lyrics.sync.outbox.OpId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The review's wording for score conflicts ("Cadence changed"). */
class CadenceReviewTextTest {
    private val score = Score(
        meters = listOf(ScoreMeter(0, 6, 8), ScoreMeter(4, 4, 4)),
        tempos = listOf(ScoreTempo(0, 72.5)),
        snap = ScoreSnap.EIGHTH,
        lines = listOf(ScoreLine(listOf(ScoreNote(0, 0, 24, 0), ScoreNote(1, 200, 24, 0)))),
    )

    private fun item(mine: Score?, server: Score?) = ReviewItem(
        OpId(1), EntityKind.SNIPPET, "x", "why",
        ReviewSide.OfSnippet("a", SnippetType.VERSE, emptyList(), mine),
        ReviewSide.OfSnippet("a", SnippetType.VERSE, emptyList(), server),
        emptyList(),
    )

    @Test
    fun `a score is summarised in one line`() {
        assertEquals("Score: 6/8, 72.5 bpm, 2 bar(s), 2 placed, 1 change(s)", scoreSummary(score))
        assertNull(scoreSummary(null))
    }

    @Test
    fun `different scores are a cadence change`() {
        assertTrue(item(score, null).cadenceChanged)
        assertFalse(item(score, score).cadenceChanged)
        assertEquals("Cadence changed", CADENCE_CHANGED)
        assertTrue(sideSummary(ReviewSide.OfSnippet("a", SnippetType.VERSE, emptyList(), score)).any { it.startsWith("Score:") })
    }
}
