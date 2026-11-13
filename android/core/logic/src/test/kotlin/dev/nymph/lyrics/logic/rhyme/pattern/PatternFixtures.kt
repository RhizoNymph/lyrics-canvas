package dev.nymph.lyrics.logic.rhyme.pattern

import dev.nymph.lyrics.logic.snippet
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.GridCell
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.GridLine
import dev.nymph.lyrics.model.PatternCandidate
import dev.nymph.lyrics.model.PatternDetectResponse
import dev.nymph.lyrics.model.PatternLine
import dev.nymph.lyrics.model.PatternSlot
import dev.nymph.lyrics.model.PatternSpan
import dev.nymph.lyrics.model.PatternWord
import dev.nymph.lyrics.model.Provenance
import dev.nymph.lyrics.model.RhymeK
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SourceName

/** Shared pattern-test data: three lines that rhyme in three places. */
object PatternFx {
    val LINES = listOf(
        "I keep it steady when the pressure on",
        "already heavy and the weather wrong",
        "ready or not it's better long",
    )

    fun gridLine(text: String): GridLine {
        val words = text.split(" ").filter { it.isNotEmpty() }
        return GridLine(words.mapIndexed { i, w -> GridCell(syllable = w, stress = 1, wordIndex = i) }, words.size, text)
    }

    /** A snippet whose beat grid has one line per entry ("" = an empty line). */
    fun lyric(id: String, lines: List<String>): Snippet = snippet(id, lines.joinToString("\n"), GridData(lines.map { gridLine(it) }))

    fun span(line: Int, start: Int, end: Int = start + 1, key: List<String> = listOf("EH")): PatternSpan =
        PatternSpan(line, start, end, LINES.getOrElse(line) { "" }.split(" ").drop(start).take(end - start).joinToString(" "), end - start, "1".repeat(end - start), key, emptyList())

    fun slot(id: String, vararg members: PatternSpan, on: Boolean = true, k: RhymeK = RhymeK.K2): PatternSlot =
        PatternSlot(id, members.toList(), members.size, members.size == 3, on, k, 0.9, 2, "10")

    /** Lines starting at absolute line [first]. */
    fun lines(first: Int = 0, texts: List<String> = LINES): List<PatternLine> = texts.mapIndexed { i, text ->
        val words = text.split(" ").filter { it.isNotEmpty() }
        PatternLine(first + i, text, words.mapIndexed { w, t -> PatternWord(w, t, 1, "1") }, gridLine(text))
    }

    /** steady|heavy|ready, pressure|weather|better, on|wrong|long. */
    val SLOTS = listOf(
        slot("s0", span(0, 3), span(1, 1), span(2, 0)),
        slot("s1", span(0, 6), span(1, 4), span(2, 4)),
        slot("s2", span(0, 7), span(1, 5), span(2, 5)),
    )

    fun detect(slots: List<PatternSlot> = SLOTS, lines: List<PatternLine> = lines()): PatternDetectResponse = PatternDetectResponse(lines, slots, 1.0)

    fun board(slots: List<PatternSlot> = SLOTS): SlotBoard = SlotBoard.fromDetect(detect(slots))

    val TARGET = PatternTarget(CanvasId("c1"), SnippetId("a"), PatternRange(0, 3))

    fun candidate(id: String, text: String = id, score: Double = 0.8): PatternCandidate = PatternCandidate(
        id = id,
        text = text,
        provenance = Provenance.DICTIONARY,
        ephemeral = true,
        syllableCount = 2,
        stress = "10",
        rhymeKey = listOf("EH"),
        syllables = emptyList(),
        gridLine = gridLine(text),
        score = score,
        matchDepth = 2,
        bestAnchorId = "pattern:a:0:3-4",
        fitsSlot = true,
        alignment = emptyList(),
        source = SourceName.DICTIONARY,
        memberScores = listOf(score, 0.9, 0.95),
    )
}

/** The board after [edit], failing the test when it was not applied. */
fun SlotBoard.applied(edit: SlotEdit): SlotBoard = when (val r = applySlotEdit(this, edit)) {
    is SlotEditResult.Applied -> r.board
    else -> error("expected $edit to apply, got $r")
}

/** Slot ids with their members as "line:start-end", for compact assertions. */
fun SlotBoard.shape(): List<String> = slots.map { s ->
    s.id.value + "{" + s.members.entries.sortedBy { it.key }.joinToString(",") { (l, w) -> "$l:${w.start}-${w.end}" } + "}" + if (s.on) "" else "off"
}
