package dev.nymph.lyrics.app.ui.theme

import androidx.compose.ui.graphics.Color
import dev.nymph.lyrics.app.ui.parseHexColor
import dev.nymph.lyrics.model.Provenance
import dev.nymph.lyrics.model.SnippetType

/** The web client's dark palette (frontend/src: CanvasViewport, SnippetCard, gridDraw, sidebarStyles). */
object Palette {
    val CanvasBackground = Color(0xFF12122A)
    val Card = Color(0xFF1C1C38)
    val Sidebar = Color(0xFF1A1A2E)
    val Border = Color(0xFF2A2A4A)
    val InputBorder = Color(0xFF3A3A5A)
    val Primary = Color(0xFF6366F1)
    val Text = Color(0xFFE0E0E0)
    val Muted = Color(0xFF8A8AAA)
    val Error = Color(0xFFF87171)
    val Fits = Color(0xFF4ADE80)
    val RangeHighlight = Color(0xFF0EA5E9)
    val RangeHighlightText = Color(0xFF0B1120)
    val RowDivider = Color(0xFF22223F)

    // Beat grid.
    val GridStripe = Color(0xFF242448).copy(alpha = 0.5f)
    val GridLine = Color(0xFF4A4A6A).copy(alpha = 0.5f)
    val CellText = Color(0xFFD0D0E8)
    val StressedText = Color(0xFFFFFFFF)
    val RestText = Color(0xFF555577)
    val CountText = Color(0xFF7A7A9A)
    val FallbackText = Color(0xFFA0A0C0)
    val CanvasGridLine = Color(0xFF2A2A4A).copy(alpha = 0.3f)
    val Shadow = Color.Black.copy(alpha = 0.25f)
    val SelectionBorder = Color.White.copy(alpha = 0.9f)
    val Strip = Color(0xFF242448)

    /** SNIPPET_TYPE_COLORS in frontend/src/types/index.ts. */
    fun snippetType(type: SnippetType): Color = when (type) {
        SnippetType.VERSE -> Color(0xFF3B82F6)
        SnippetType.CHORUS -> Color(0xFFF59E0B)
        SnippetType.BRIDGE -> Color(0xFF8B5CF6)
        SnippetType.HOOK -> Color(0xFFEF4444)
        SnippetType.FREESTYLE -> Color(0xFF6B7280)
    }

    /** PROVENANCE_COLORS in components/rhyme/rhymeColors.ts. */
    fun provenance(provenance: Provenance): Color = when (provenance) {
        Provenance.NOTE -> Color(0xFF0EA5E9)
        Provenance.DICTIONARY -> Color(0xFF14B8A6)
        Provenance.DATAMUSE -> Color(0xFFA855F7)
        Provenance.GENERATED -> Color(0xFF71717A)
    }

    fun provenanceLabel(provenance: Provenance): String = when (provenance) {
        Provenance.NOTE -> "NOTE"
        Provenance.DICTIONARY -> "DICT"
        Provenance.DATAMUSE -> "DATAMUSE"
        Provenance.GENERATED -> "TEXT"
    }

    /** Opacity for candidates that do not fit the slot (NON_FIT_ALPHA). */
    const val NON_FIT_ALPHA: Float = 0.45f

    /** Alignment cell colour: red (0) through amber to green (1), hsl(t * 120, 65%, 42%). */
    fun alignment(total: Double): Color {
        val t = if (total.isFinite()) total.coerceIn(0.0, 1.0) else 0.0
        return Color.hsl(Math.round(t * 120).toFloat(), 0.65f, 0.42f)
    }

    /** A server tag colour, falling back to the freestyle grey for a malformed one. */
    fun tag(hex: String): Color = parseHexColor(hex)?.let { Color(it.toInt()) } ?: snippetType(SnippetType.FREESTYLE)
}
