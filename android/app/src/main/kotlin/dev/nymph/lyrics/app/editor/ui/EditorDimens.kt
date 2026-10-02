package dev.nymph.lyrics.app.editor.ui

import androidx.compose.ui.unit.dp

/** Touch sizes of the grid editor (cells at least 48 dp, per the touch contract). */
internal object EditorDimens {
    val CellWidth = 56.dp
    val CellHeight = 48.dp
    /** Strip area below the cells that holds the selection handles. */
    val HandleZone = 28.dp
    val HandleSize = 18.dp
    /** Horizontal hit radius of a selection handle. */
    val HandleSlop = 24.dp
    /** Width of the strip edge zones that auto-scroll during a chunk drag. */
    val AutoScrollEdge = 48.dp
    /** Auto-scroll step per frame at the very edge. */
    val AutoScrollStep = 14.dp
    val RowControl = 48.dp
    val NumberWidth = 32.dp
    val CountWidth = 32.dp
}
