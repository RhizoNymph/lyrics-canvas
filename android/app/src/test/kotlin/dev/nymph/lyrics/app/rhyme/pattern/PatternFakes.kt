package dev.nymph.lyrics.app.rhyme.pattern

import dev.nymph.lyrics.app.fakes.snippet
import dev.nymph.lyrics.data.repository.RhymePatternRepository
import dev.nymph.lyrics.model.GridCell
import dev.nymph.lyrics.model.GridLine
import dev.nymph.lyrics.model.LineMatch
import dev.nymph.lyrics.model.LineMatchHit
import dev.nymph.lyrics.model.PatternCandidate
import dev.nymph.lyrics.model.PatternDetectRequest
import dev.nymph.lyrics.model.PatternDetectResponse
import dev.nymph.lyrics.model.PatternLine
import dev.nymph.lyrics.model.PatternSlot
import dev.nymph.lyrics.model.PatternSlotResult
import dev.nymph.lyrics.model.PatternSpan
import dev.nymph.lyrics.model.PatternSuggestRequest
import dev.nymph.lyrics.model.PatternSuggestResponse
import dev.nymph.lyrics.model.PatternWord
import dev.nymph.lyrics.model.Provenance
import dev.nymph.lyrics.model.RhymeK
import dev.nymph.lyrics.model.RhymeUnit
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SourceName
import dev.nymph.lyrics.model.error.ApiResult

/** Answers pattern calls through handlers, which may suspend; records every request. */
class FakeRhymePatternRepository(
    var onDetect: suspend (PatternDetectRequest) -> ApiResult<PatternDetectResponse> = { ApiResult.Success(PatternFx.detect()) },
    var onSuggest: suspend (PatternSuggestRequest) -> ApiResult<PatternSuggestResponse> = { ApiResult.Success(PatternFx.suggest(it)) },
) : RhymePatternRepository {
    val detects = mutableListOf<PatternDetectRequest>()
    val suggests = mutableListOf<PatternSuggestRequest>()

    override suspend fun detect(request: PatternDetectRequest): ApiResult<PatternDetectResponse> {
        detects += request
        return onDetect(request)
    }

    override suspend fun suggest(request: PatternSuggestRequest): ApiResult<PatternSuggestResponse> {
        suggests += request
        return onSuggest(request)
    }
}

object PatternFx {
    /** Line 0 is a title line; lines 1-3 rhyme in three places. */
    val TEXTS = listOf("intro", "I keep it steady when the pressure on", "already heavy and the weather wrong", "ready or not it's better long")

    fun gridLine(text: String): GridLine {
        val words = text.split(" ")
        return GridLine(words.mapIndexed { i, w -> GridCell(syllable = w, stress = 1, wordIndex = i) }, words.size, text)
    }

    /** A snippet holding [TEXTS]; with [others], two snippets are selected. */
    fun song(id: String = "a", x: Double = 100.0, y: Double = 50.0): Snippet =
        snippet(id, TEXTS.joinToString("\n"), TEXTS.map { gridLine(it) }, x = x, y = y, width = 300.0)

    fun span(line: Int, word: Int, key: String = "EH"): PatternSpan {
        val text = TEXTS[line].split(" ")[word]
        return PatternSpan(line, word, word + 1, text, 1, "1", listOf(key, line.toString()), emptyList())
    }

    fun slot(id: String, vararg members: PatternSpan, on: Boolean = true, k: RhymeK = RhymeK.K2) =
        PatternSlot(id, members.toList(), members.size, members.size == 3, on, k, 0.9, 2, "10")

    val SLOTS = listOf(
        slot("s0", span(1, 3), span(2, 1), span(3, 0)),
        slot("s1", span(1, 6), span(2, 4), span(3, 4)),
        slot("s2", span(1, 7), span(2, 5), span(3, 5), on = false),
    )

    fun lines(range: IntRange = 1..3): List<PatternLine> = range.map { i ->
        val words = TEXTS[i].split(" ")
        PatternLine(i, TEXTS[i], words.mapIndexed { w, t -> PatternWord(w, t, 1, "1") }, gridLine(TEXTS[i]))
    }

    fun detect(slots: List<PatternSlot> = SLOTS): PatternDetectResponse = PatternDetectResponse(lines(), slots, 2.0)

    fun candidate(id: String, text: String = id.substringAfter(':')): PatternCandidate = PatternCandidate(
        id = id, text = text, provenance = Provenance.DICTIONARY, ephemeral = true, syllableCount = 2, stress = "10",
        rhymeKey = listOf("EH"), syllables = emptyList(), gridLine = gridLine(text), score = 0.8, matchDepth = 2,
        bestAnchorId = "pattern:a:1:3-4", fitsSlot = true, alignment = emptyList(), source = SourceName.DICTIONARY, memberScores = listOf(0.8, 0.9, 0.9),
    )

    fun lineMatch(id: String, text: String = "ready for the weather"): LineMatch = LineMatch(
        unit = RhymeUnit(id, text, Provenance.NOTE, false, 6, "101010", listOf("ER"), emptyList(), gridLine(text), SnippetId("n1"), 0, 0),
        hits = listOf(LineMatchHit("s0", 0, 1, "ready", 0.9), LineMatchHit("s1", 3, 4, "weather", 0.7)),
        slotsHit = 2,
        score = 0.8,
    )

    /**
     * Echoes the slots sent (as server slots), with two candidates per page named
     * `<slot>-<offset>-<n>` and 61 in total; one line match per page out of 31.
     */
    fun suggest(request: PatternSuggestRequest): PatternSuggestResponse = PatternSuggestResponse(
        slots = request.slots.map { spec ->
            val offset = request.offsets[spec.id] ?: 0
            val members = spec.members.map { m -> PatternSpan(m.lineIndex, m.wordStart, m.wordEnd, "w", 1, "1", listOf("K", spec.id!!), emptyList()) }
            PatternSlotResult(
                PatternSlot(spec.id!!, members, members.size, members.size == 3, true, RhymeK.K2, 0.9, 1, "1"),
                listOf(candidate("dict:${spec.id}-$offset-0"), candidate("dict:${spec.id}-$offset-1")),
                61,
            )
        },
        lineMatches = if (request.lineMatches) listOf(lineMatch("note:n${request.lineMatchOffset}:0:0")) else emptyList(),
        lineMatchTotal = if (request.lineMatches) 31 else 0,
        elapsedMs = 3.0,
    )
}
