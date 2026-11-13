package dev.nymph.lyrics.sync.apply

import dev.nymph.lyrics.logic.rests.lineWordTokens
import dev.nymph.lyrics.model.GridCell
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.GridLine
import dev.nymph.lyrics.model.GridMode
import dev.nymph.lyrics.model.GroupId
import dev.nymph.lyrics.model.MergeMode
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SnippetType
import dev.nymph.lyrics.model.TagId

/** The parts of a snippet a merge reads (the server's `MergeSource`). */
data class MergeSource(
    val id: SnippetId,
    val rawText: String,
    val grid: GridData?,
    val snippetType: SnippetType,
    val gridMode: GridMode,
    val beatsPerLine: Int?,
    val groupId: GroupId?,
    val positionX: Double,
    val positionY: Double,
    val tagIds: List<TagId> = emptyList(),
)

/** The replacement snippet (the server's `MergedSnippet`); [sourceIds] in merge order. */
data class MergedContent(
    val rawText: String,
    val grid: GridData,
    val snippetType: SnippetType,
    val gridMode: GridMode,
    val beatsPerLine: Int?,
    val groupId: GroupId?,
    val positionX: Double,
    val positionY: Double,
    val tagIds: List<TagId>,
    val sourceIds: List<SnippetId>,
)

/**
 * The server's block merge (`backend/app/services/merge_service.py`), ported so a merge shows at
 * once offline. The server recomputes it when the merge syncs and its result replaces this one.
 * Sources combine in canvas-position order, the first supplies type, grid mode, position and
 * group; fixed grids are padded with rests (never truncated); horizontal re-bases `word_index`.
 */
object LocalMerge {
    /** Null for fewer than two distinct sources (a repeated id counts once, first occurrence). */
    fun merge(sources: List<MergeSource>, mode: MergeMode): MergedContent? {
        val distinct = sources.distinctBy { it.id }
        if (distinct.size < 2) return null
        val ordered = orderByPosition(distinct, mode)
        return when (mode) {
            MergeMode.VERTICAL -> assemble(ordered, ordered.flatMap { s -> linesOf(s).map { withCount(it.cells, it.rawText) } })
            MergeMode.HORIZONTAL -> assemble(ordered, joinHorizontally(ordered))
        }
    }

    fun orderByPosition(sources: List<MergeSource>, mode: MergeMode): List<MergeSource> = when (mode) {
        MergeMode.VERTICAL -> sources.sortedWith(compareBy<MergeSource>({ it.positionY }, { it.positionX }, { it.id.value }))
        MergeMode.HORIZONTAL -> sources.sortedWith(compareBy<MergeSource>({ it.positionX }, { it.positionY }, { it.id.value }))
    }

    /** Trimmed, blank lines dropped (as the syllabifier splits text). */
    private fun textLines(rawText: String): List<String> = rawText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    private fun wordCount(text: String): Int = textLines(text).sumOf { lineWordTokens(it).size }

    private fun linesOf(source: MergeSource): List<GridLine> =
        source.grid?.lines ?: textLines(source.rawText).map { GridLine(emptyList(), 0, it) }

    private fun withCount(cells: List<GridCell>, rawText: String) = GridLine(cells, cells.count { !it.isRest }, rawText)

    private fun joinHorizontally(sources: List<MergeSource>): List<GridLine> {
        val perSource = sources.map(::linesOf)
        val lineCount = perSource.maxOfOrNull { it.size } ?: 0
        return (0 until lineCount).map { i ->
            val cells = mutableListOf<GridCell>()
            val parts = mutableListOf<String>()
            for (lines in perSource) {
                val line = lines.getOrNull(i) ?: continue
                val offset = wordCount(parts.joinToString(" "))
                line.cells.mapTo(cells) { cell -> cell.wordIndex?.let { cell.copy(wordIndex = it + offset) } ?: cell }
                line.rawText.trim().takeIf { it.isNotEmpty() }?.let(parts::add)
            }
            withCount(cells, parts.joinToString(" "))
        }
    }

    private fun assemble(sources: List<MergeSource>, joined: List<GridLine>): MergedContent {
        val first = sources.first()
        val (lines, beats) = fitToMode(joined, first.gridMode, first.beatsPerLine)
        return MergedContent(
            rawText = lines.joinToString("\n") { it.rawText },
            grid = GridData(lines),
            snippetType = first.snippetType,
            gridMode = first.gridMode,
            beatsPerLine = beats,
            groupId = first.groupId,
            positionX = first.positionX,
            positionY = first.positionY,
            tagIds = sources.flatMap { it.tagIds }.distinct(),
            sourceIds = sources.map { it.id },
        )
    }

    /** Fixed mode pads every line with rests to `beats_per_line`, raised to the longest line. */
    private fun fitToMode(lines: List<GridLine>, mode: GridMode, beatsPerLine: Int?): Pair<List<GridLine>, Int?> {
        if (mode != GridMode.FIXED) return lines to beatsPerLine
        val longest = lines.maxOfOrNull { it.cells.size } ?: 0
        val beats = maxOf(beatsPerLine ?: 0, longest)
        if (beats == 0) return lines to beatsPerLine
        val padded = lines.map { line ->
            if (line.cells.size >= beats) line else line.copy(cells = line.cells + List(beats - line.cells.size) { GridCell.REST })
        }
        return padded to beats
    }
}
