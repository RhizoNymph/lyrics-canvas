package dev.nymph.lyrics.app.fakes

import dev.nymph.lyrics.model.Canvas
import dev.nymph.lyrics.model.CanvasDetail
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.GridCell
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.GridLine
import dev.nymph.lyrics.model.GridMode
import dev.nymph.lyrics.model.Provenance
import dev.nymph.lyrics.model.RhymeCandidate
import dev.nymph.lyrics.model.RhymeSuggestResponse
import dev.nymph.lyrics.model.RhymeUnit
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SnippetType
import dev.nymph.lyrics.model.SourceName
import dev.nymph.lyrics.model.Tag
import dev.nymph.lyrics.model.TagId
import dev.nymph.lyrics.model.User
import dev.nymph.lyrics.model.UserId
import dev.nymph.lyrics.model.ViewportState

val CANVAS_ID = CanvasId("c1")

/** A grid line from "syl:stress:word" tokens; "_" is a rest. */
fun line(vararg cells: String): GridLine {
    val parsed = cells.map { token ->
        if (token == "_") {
            GridCell.REST
        } else {
            val (syl, stress, word) = token.split(":")
            GridCell(syllable = syl, stress = stress.toInt(), wordIndex = word.toInt())
        }
    }
    val words = cells.filter { it != "_" }.groupBy { it.split(":")[2].toInt() }
        .toSortedMap().values.map { group -> group.joinToString("") { it.split(":")[0] } }
    return GridLine(parsed, parsed.count { !it.isRest }, words.joinToString(" "))
}

fun snippet(
    id: String,
    rawText: String = "",
    lines: List<GridLine>? = null,
    x: Double = 0.0,
    y: Double = 0.0,
    width: Double = 300.0,
    height: Double = 150.0,
    zIndex: Int = 0,
    type: SnippetType = SnippetType.VERSE,
    tags: List<Tag> = emptyList(),
): Snippet = Snippet(
    id = SnippetId(id),
    canvasId = CANVAS_ID,
    snippetType = type,
    gridMode = GridMode.AUTO,
    gridData = lines?.let { GridData(it) },
    rawText = rawText,
    positionX = x,
    positionY = y,
    width = width,
    height = height,
    zIndex = zIndex,
    tags = tags,
    createdAt = "",
    updatedAt = "",
)

fun tag(name: String, color: String = "#ff0000"): Tag = Tag(TagId("t-$name"), CANVAS_ID, name, color)

/** "the cat sat" / "on the mat": two lines with word indexes. */
fun catSnippet(id: String = "s1", x: Double = 100.0, y: Double = 50.0): Snippet = snippet(
    id = id,
    rawText = "the cat sat\non the mat",
    lines = listOf(
        line("the:0:0", "cat:1:1", "sat:1:2"),
        line("on:0:0", "the:0:1", "mat:1:2"),
    ),
    x = x,
    y = y,
)

fun canvasDetail(snippets: List<Snippet>, viewport: ViewportState? = null): CanvasDetail = CanvasDetail(
    id = CANVAS_ID,
    ownerId = UserId("u1"),
    title = "Song",
    isPublic = false,
    viewportState = viewport,
    createdAt = "",
    updatedAt = "",
    snippets = snippets,
)

fun canvas(id: String, title: String): Canvas =
    Canvas(CanvasId(id), UserId("u1"), title, isPublic = false, createdAt = "", updatedAt = "")

val USER = User(UserId("u1"), "a@b.c", "A", createdAt = "")

fun unit(id: String, text: String, snippetId: SnippetId? = null): RhymeUnit = RhymeUnit(
    id = id,
    text = text,
    provenance = if (snippetId == null) Provenance.GENERATED else Provenance.NOTE,
    ephemeral = snippetId == null,
    syllableCount = 1,
    stress = "1",
    rhymeKey = listOf("AE1", "T"),
    syllables = emptyList(),
    gridLine = line("$text:1:0"),
    snippetId = snippetId,
)

fun candidate(id: String, text: String = id, bestAnchor: String = "a1", fits: Boolean = true): RhymeCandidate = RhymeCandidate(
    id = id,
    text = text,
    provenance = Provenance.DICTIONARY,
    ephemeral = true,
    syllableCount = 1,
    stress = "1",
    rhymeKey = listOf("AE1", "T"),
    syllables = emptyList(),
    gridLine = line("$text:1:0"),
    score = 0.9,
    matchDepth = 1,
    bestAnchorId = bestAnchor,
    fitsSlot = fits,
    alignment = emptyList(),
    source = SourceName.DICTIONARY,
)

fun response(vararg candidates: RhymeCandidate, total: Int = candidates.size, anchors: List<RhymeUnit> = listOf(unit("a1", "mat"))) =
    RhymeSuggestResponse(anchors, candidates.toList(), total, 1.0)
