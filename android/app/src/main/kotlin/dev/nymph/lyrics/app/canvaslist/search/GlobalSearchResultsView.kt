package dev.nymph.lyrics.app.canvaslist.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.logic.search.SearchQuery
import dev.nymph.lyrics.logic.search.highlightSpans
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.CanvasSearchHit
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SnippetSearchHit
import java.text.Normalizer

/** The canvas list's search field (search across every canvas). */
@Composable
fun GlobalSearchField(text: String, onEdit: (String) -> Unit, onClear: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = text,
        onValueChange = onEdit,
        singleLine = true,
        placeholder = { Text("Search canvases and lyrics") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = if (text.isNotEmpty()) {
            { IconButton(onClick = onClear) { Icon(Icons.Default.Close, contentDescription = "Clear search") } }
        } else {
            null
        },
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/**
 * Search hits in two sections: Canvases (tap opens) and Snippets (type chip, highlighted excerpt,
 * canvas titles; each title opens that canvas focused on the snippet, the row opens the first).
 */
@Composable
fun GlobalSearchResultsView(
    results: GlobalSearchResults,
    onOpenCanvas: (CanvasId) -> Unit,
    onOpenSnippet: (CanvasId, SnippetId) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        when (results) {
            GlobalSearchResults.Off -> Unit
            GlobalSearchResults.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            is GlobalSearchResults.Failed -> Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(results.error.userMessage(), color = Palette.Error)
                Button(onClick = onRetry) { Text("Retry") }
            }
            is GlobalSearchResults.Loaded -> if (results.isEmpty) {
                Text(NO_MATCHES, color = Palette.Muted, modifier = Modifier.align(Alignment.Center).padding(24.dp))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (results.canvases.isNotEmpty()) {
                        item(key = "h-canvases") { SectionHeader("Canvases") }
                        items(results.canvases, key = { "c-" + it.id.value }) { hit ->
                            CanvasHitRow(hit, onOpen = { onOpenCanvas(hit.id) })
                            HorizontalDivider(color = Palette.Border)
                        }
                    }
                    if (results.snippets.isNotEmpty()) {
                        item(key = "h-snippets") { SectionHeader("Snippets") }
                        items(results.snippets, key = { "s-" + it.snippetId.value }) { hit ->
                            SnippetHitRow(hit, results.query, onOpen = { canvas -> onOpenSnippet(canvas, hit.snippetId) })
                            HorizontalDivider(color = Palette.Border)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        color = Palette.Muted,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun CanvasHitRow(hit: CanvasSearchHit, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClickLabel = "Open canvas", onClick = onOpen).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(hit.title, color = Palette.Text)
        Text("Updated ${hit.updatedAt.take(10)}", color = Palette.Muted, fontSize = 12.sp)
    }
}

/**
 * The excerpt (NFC, as the match rule reads it; it may start or end with "…" where the server cut
 * it) with every term occurrence in bold on the range-highlight colour. Positions are found here,
 * never assumed from the line start.
 */
private fun highlighted(text: String, query: SearchQuery): AnnotatedString = buildAnnotatedString {
    val shown = Normalizer.normalize(text, Normalizer.Form.NFC)
    append(shown)
    for (span in highlightSpans(shown, query)) {
        addStyle(SpanStyle(fontWeight = FontWeight.Bold, background = Palette.RangeHighlight, color = Palette.RangeHighlightText), span.start, span.end)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SnippetHitRow(hit: SnippetSearchHit, query: SearchQuery, onOpen: (CanvasId) -> Unit) {
    val first = hit.canvases.firstOrNull()
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = first != null, onClickLabel = "Open snippet") { first?.let { onOpen(it.id) } }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                hit.snippetType.name,
                color = Palette.Text,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Palette.snippetType(hit.snippetType)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
            if (hit.excerpt.isEmpty()) {
                // The snippet has no non-empty display line (e.g. only rests).
                Text("(no text)", color = Palette.Muted, modifier = Modifier.weight(1f))
            } else {
                Text(highlighted(hit.excerpt, query), color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
        if (hit.canvases.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                hit.canvases.forEach { canvas ->
                    Text(
                        canvas.title,
                        color = Palette.Primary,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Palette.Strip)
                            .clickable(onClickLabel = "Open ${canvas.title}") { onOpen(canvas.id) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}
