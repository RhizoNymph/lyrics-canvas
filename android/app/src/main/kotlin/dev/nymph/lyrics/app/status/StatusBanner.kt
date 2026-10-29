package dev.nymph.lyrics.app.status

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nymph.lyrics.app.ui.theme.Palette

/** Upper bound so many alerts never push the screen below out of reach; the banner scrolls. */
private val MAX_BANNER_HEIGHT = 240.dp

/**
 * The app-wide status banner, above every screen. Draws nothing when there is nothing to say.
 * It owns the status bar inset while shown (the caller consumes it for the content below), never
 * takes focus or blocks input to the screen, and announces each item as a live region.
 */
@Composable
fun StatusBanner(
    state: StatusBannerUiState,
    onDismiss: (BannerKey) -> Unit,
    onToggleExpanded: (BannerKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.isVisible) return
    Column(
        modifier
            .fillMaxWidth()
            .background(Palette.Sidebar)
            .windowInsetsPadding(WindowInsets.statusBars)
            .heightIn(max = MAX_BANNER_HEIGHT)
            .verticalScroll(rememberScrollState()),
    ) {
        state.rows.forEach { row ->
            BannerRowView(row, onDismiss, onToggleExpanded)
            HorizontalDivider(color = Palette.Border)
        }
        if (state.showStaleNote) {
            Text(
                "Alert status may be out of date: the alert service is not answering.",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Muted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun BannerRowView(row: BannerRow, onDismiss: (BannerKey) -> Unit, onToggleExpanded: (BannerKey) -> Unit) {
    val item = row.item
    val accent = toneColor(item.tone)
    val spoken = listOfNotNull(item.label, item.title, item.detail, item.started).joinToString(". ")
    Row(
        Modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.14f))
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(toneIcon(item.tone), contentDescription = null, tint = accent, modifier = Modifier.padding(top = 2.dp).size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(
            Modifier
                .weight(1f)
                .padding(top = 1.dp)
                // One announcement per item: polite or assertive by urgency.
                .clearAndSetSemantics {
                    contentDescription = spoken + (item.description?.takeIf { row.expanded }?.let { ". $it" } ?: "")
                    liveRegion = when (item.announce) {
                        Announce.ASSERTIVE -> LiveRegionMode.Assertive
                        Announce.POLITE -> LiveRegionMode.Polite
                    }
                },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                listOfNotNull(item.label, item.title).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Palette.Text,
            )
            val secondary = listOfNotNull(item.detail, item.started).joinToString(" · ")
            if (secondary.isNotEmpty()) {
                Text(secondary, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            }
            if (row.expanded && item.description != null) {
                Text(item.description, style = MaterialTheme.typography.bodySmall, color = Palette.Text)
            }
        }
        if (item.description != null) {
            IconButton(
                onClick = { onToggleExpanded(item.key) },
                modifier = Modifier.semantics { stateDescription = if (row.expanded) "Expanded" else "Collapsed" },
            ) {
                Icon(
                    if (row.expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (row.expanded) "Hide details of ${item.title}" else "Show details of ${item.title}",
                    tint = Palette.Muted,
                )
            }
        }
        IconButton(onClick = { onDismiss(item.key) }) {
            Icon(Icons.Filled.Close, contentDescription = "Dismiss ${item.title}", tint = Palette.Muted)
        }
    }
}

private fun toneColor(tone: BannerTone): Color = when (tone) {
    BannerTone.DANGER -> Palette.Error
    BannerTone.WARNING -> Color(0xFFF59E0B)
    BannerTone.INFO -> Palette.RangeHighlight
    BannerTone.SUCCESS -> Palette.Fits
}

private fun toneIcon(tone: BannerTone): ImageVector = when (tone) {
    BannerTone.DANGER, BannerTone.WARNING -> Icons.Filled.Warning
    BannerTone.INFO -> Icons.Filled.Info
    BannerTone.SUCCESS -> Icons.Filled.CheckCircle
}
