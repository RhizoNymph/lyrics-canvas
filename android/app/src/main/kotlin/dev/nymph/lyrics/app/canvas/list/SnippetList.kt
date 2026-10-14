package dev.nymph.lyrics.app.canvas.list

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.nymph.lyrics.app.canvas.render.CardLayout
import dev.nymph.lyrics.app.canvas.render.CardMetrics
import dev.nymph.lyrics.app.canvas.render.CardTextStyles
import dev.nymph.lyrics.app.canvas.render.cardLayout
import dev.nymph.lyrics.app.canvas.render.drawCard
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetId
import kotlin.math.ceil

/** Text measurements for the visible rows' syllables and labels. */
private const val TEXT_CACHE_SIZE = 512

/** Horizontal gutter of the list (each side). */
private val ROW_GUTTER = 16.dp

/** Space reserved right of / below a card for its drop shadow. */
private val SHADOW_SPACE = CardMetrics.SHADOW_OFFSET.dp

/**
 * List mode: the canvas's snippets as a vertically scrolling column of cards, in the order the
 * ViewModel captured ([rows]). Each row is the canvas card ([cardLayout] + [drawCard]) sized by
 * [listCardWidth] / [listCardHeight]. Tap -> [onTap]; long-press -> [onLongPress] (selection).
 */
@Composable
fun SnippetList(
    rows: List<Snippet>,
    selected: Set<SnippetId>,
    onTap: (SnippetId) -> Unit,
    onLongPress: (SnippetId) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (rows.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No snippets yet", color = Palette.FallbackText)
        }
        return
    }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = TEXT_CACHE_SIZE)
    val styles = remember(density) { CardTextStyles(density) }
    BoxWithConstraints(modifier.fillMaxSize().background(Palette.CanvasBackground)) {
        val availableDp = (maxWidth - ROW_GUTTER * 2 - SHADOW_SPACE).value.toDouble()
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = ROW_GUTTER, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(rows, key = { it.id.value }) { snippet ->
                SnippetListRow(
                    snippet = snippet,
                    selected = snippet.id in selected,
                    availableDp = availableDp,
                    measurer = measurer,
                    styles = styles,
                    onTap = { onTap(snippet.id) },
                    onLongPress = { onLongPress(snippet.id) },
                )
            }
        }
    }
}

/** A row card: the list-sized snippet, its layout and the scale it is drawn at. */
private data class RowCard(val layout: CardLayout, val card: Snippet, val scale: Double)

private fun rowCard(snippet: Snippet, availableDp: Double, measurer: TextMeasurer, styles: CardTextStyles): RowCard {
    val fit = listCardWidth(snippet, availableDp)
    // The raw-text fallback wraps inside the card, so its height is measured at the card's inner width.
    val fallback = cardLayout(listCard(snippet, fit.width, CardMetrics.HEADER_HEIGHT)).fallbackText
    val fallbackHeight = fallback?.let {
        val innerWidth = (fit.width - CardMetrics.PADDING * 2).toInt().coerceAtLeast(1)
        measurer.measure(it, styles.fallback, constraints = Constraints(maxWidth = innerWidth)).size.height.toDouble()
    } ?: 0.0
    val card = listCard(snippet, fit.width, ceil(listCardHeight(snippet, fallbackHeight)))
    return RowCard(cardLayout(card), card, fit.scale)
}

@Composable
private fun SnippetListRow(
    snippet: Snippet,
    selected: Boolean,
    availableDp: Double,
    measurer: TextMeasurer,
    styles: CardTextStyles,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val row = remember(snippet, availableDp, styles) { rowCard(snippet, availableDp, measurer, styles) }
    val widthDp = (row.layout.width * row.scale).dp + SHADOW_SPACE
    val heightDp = (row.layout.height * row.scale).dp + SHADOW_SPACE
    Box(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
    ) {
        Canvas(Modifier.size(widthDp, heightDp)) {
            drawCard(
                snippet = row.card,
                layout = row.layout,
                originPx = Offset.Zero,
                zoom = row.scale,
                pxPerDp = density,
                selected = selected,
                measurer = measurer,
                styles = styles,
            )
        }
    }
}
