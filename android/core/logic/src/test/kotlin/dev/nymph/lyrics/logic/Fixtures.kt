package dev.nymph.lyrics.logic

import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.GridLine
import dev.nymph.lyrics.model.GridMode
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SnippetType

/** A snippet with only the fields a test cares about (the TS tests cast partial objects). */
fun snippet(
    id: String,
    rawText: String = "",
    gridData: GridData? = null,
    x: Double = 0.0,
    y: Double = 0.0,
    width: Double = 200.0,
    height: Double = 100.0,
    zIndex: Int = 0,
): Snippet = Snippet(
    id = SnippetId(id),
    canvasId = CanvasId("c1"),
    snippetType = SnippetType.VERSE,
    gridMode = GridMode.AUTO,
    gridData = gridData,
    rawText = rawText,
    positionX = x,
    positionY = y,
    width = width,
    height = height,
    zIndex = zIndex,
    createdAt = "",
    updatedAt = "",
)

/** Rest cells render as "·", others as their syllable (the TS tests' `syls`). */
fun syls(line: GridLine?): List<String?>? = line?.cells?.map { if (it.isRest) "·" else it.syllable }
