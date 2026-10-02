package dev.nymph.lyrics.app.canvas.render

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.logic.canvas.Rect
import dev.nymph.lyrics.model.Snippet

/**
 * Text styles sized in card-local units: a card is drawn under a `scale(zoom * density)`
 * transform, so a style of N px here renders at N units of the card. Measuring at a fixed size
 * keeps the TextMeasurer cache warm while zooming.
 */
class CardTextStyles(density: Density) {
    private fun Density.units(value: Double) = value.toFloat().toSp()

    val header = with(density) { TextStyle(color = Color.White, fontSize = units(CardMetrics.HEADER_FONT), fontWeight = FontWeight.Bold) }
    val tag = with(density) { TextStyle(color = Color.White, fontSize = units(CardMetrics.TAG_FONT), fontWeight = FontWeight.Bold) }
    private val cellBase = with(density) { TextStyle(fontSize = units(CardMetrics.CELL_FONT), fontFamily = FontFamily.Monospace) }
    val cell = cellBase.copy(color = Palette.CellText)
    val stressed = cellBase.copy(color = Palette.StressedText, fontWeight = FontWeight.Bold)
    val rest = cellBase.copy(color = Palette.RestText)
    val count = with(density) { TextStyle(color = Palette.CountText, fontSize = units(CardMetrics.COUNT_FONT), fontFamily = FontFamily.Monospace) }
    val fallback = cellBase.copy(color = Palette.FallbackText)

    fun forKind(kind: CellTextKind): TextStyle = when (kind) {
        CellTextKind.REST -> rest
        CellTextKind.STRESSED -> stressed
        CellTextKind.NORMAL -> cell
    }
}

private fun Rect.offset() = Offset(left.toFloat(), top.toFloat())

private fun Rect.size() = Size(width.toFloat(), height.toFloat())

/**
 * Draw one card (SnippetCard.tsx) with its top-left at [originPx]. [zoom] is the canvas zoom
 * (dp per world unit) and [pxPerDp] the screen density.
 */
fun DrawScope.drawCard(
    snippet: Snippet,
    layout: CardLayout,
    originPx: Offset,
    zoom: Double,
    pxPerDp: Float,
    selected: Boolean,
    measurer: TextMeasurer,
    styles: CardTextStyles,
) {
    val scale = (zoom * pxPerDp).toFloat()
    // One dp expressed in card units.
    val dp = (1.0 / zoom).toFloat()
    val w = layout.width.toFloat()
    val h = layout.height.toFloat()
    val radius = CornerRadius(CardMetrics.CORNER_RADIUS.toFloat())
    val typeColor = Palette.snippetType(snippet.snippetType)

    withTransform({
        translate(originPx.x, originPx.y)
        scale(scale, scale, Offset.Zero)
    }) {
        val shadow = (CardMetrics.SHADOW_OFFSET.toFloat()) * dp
        drawRoundRect(Palette.Shadow, topLeft = Offset(shadow, shadow), size = Size(w, h), cornerRadius = radius)
        drawRoundRect(Palette.Card, size = Size(w, h), cornerRadius = radius)

        val header = Path().apply {
            addRoundRect(
                RoundRect(
                    left = 0f,
                    top = 0f,
                    right = w,
                    bottom = CardMetrics.HEADER_HEIGHT.toFloat(),
                    topLeftCornerRadius = radius,
                    topRightCornerRadius = radius,
                    bottomRightCornerRadius = CornerRadius.Zero,
                    bottomLeftCornerRadius = CornerRadius.Zero,
                ),
            )
        }
        drawPath(header, typeColor.copy(alpha = 0.85f))

        if (selected) {
            drawRoundRect(Palette.SelectionBorder, size = Size(w, h), cornerRadius = radius, style = Stroke(width = CardMetrics.SELECTION_STROKE.toFloat() * dp))
        }

        for (pill in layout.tags) {
            drawRoundRect(
                Palette.tag(pill.color).copy(alpha = 0.7f),
                topLeft = pill.bounds.offset(),
                size = pill.bounds.size(),
                cornerRadius = CornerRadius((CardMetrics.TAG_PILL_HEIGHT / 2).toFloat()),
            )
        }

        layout.grid?.let { grid ->
            for (stripe in grid.stripes()) drawRect(Palette.GridStripe, topLeft = stripe.offset(), size = stripe.size())
            val stroke = dp
            for (y in grid.horizontalLineYs()) {
                drawLine(Palette.GridLine, Offset(grid.left.toFloat(), y.toFloat()), Offset(grid.right.toFloat(), y.toFloat()), strokeWidth = stroke)
            }
            for (x in grid.verticalLineXs()) {
                drawLine(Palette.GridLine, Offset(x.toFloat(), grid.top.toFloat()), Offset(x.toFloat(), grid.bottom.toFloat()), strokeWidth = stroke)
            }
        }

        if (!textVisible(zoom)) return@withTransform

        drawText(measurer, layout.headerLabel, Offset(layout.headerLabelX.toFloat(), layout.headerLabelY.toFloat()), styles.header, maxLines = 1)
        for (pill in layout.tags) {
            drawText(measurer, pill.label, Offset(pill.labelX.toFloat(), pill.labelY.toFloat()), styles.tag, maxLines = 1)
        }
        for (cell in layout.cells) {
            if (cell.text.isEmpty()) continue
            drawText(measurer, cell.text, Offset(cell.x.toFloat(), cell.y.toFloat()), styles.forKind(cell.kind), maxLines = 1, softWrap = false)
        }
        for (count in layout.counts) {
            drawText(measurer, count.text, Offset(count.x.toFloat(), count.y.toFloat()), styles.count, maxLines = 1)
        }
        layout.fallbackText?.let { text ->
            val maxHeight = (layout.height - layout.fallbackY - CardMetrics.PADDING).coerceAtLeast(CardMetrics.CELL_HEIGHT)
            drawText(
                measurer,
                text,
                Offset(layout.fallbackX.toFloat(), layout.fallbackY.toFloat()),
                styles.fallback,
                overflow = TextOverflow.Ellipsis,
                size = Size(layout.fallbackWidth.toFloat().coerceAtLeast(1f), maxHeight.toFloat()),
            )
        }
    }
}
