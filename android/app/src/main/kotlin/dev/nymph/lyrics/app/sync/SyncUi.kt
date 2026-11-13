package dev.nymph.lyrics.app.sync

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.nymph.lyrics.app.canvas.render.NOT_SYNCED_LABEL
import dev.nymph.lyrics.app.ui.NEEDS_CONNECTION
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.model.BankWordId
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.TagId
import dev.nymph.lyrics.sync.outbox.EntityRef
import dev.nymph.lyrics.sync.status.SyncStatus

/**
 * What every screen may show about sync, provided once above the navigation host: the status
 * indicator (tapping it opens the Not-synced list), the "Not synced" badges, and reachability
 * for the server-only actions ("Needs connection").
 */
data class SyncUi(
    val status: SyncStatus = SyncStatus.Synced,
    val notSynced: Set<EntityRef> = emptySet(),
    val online: Boolean = true,
    val openNotSynced: () -> Unit = {},
) {
    val notSyncedSnippets: Set<SnippetId> get() = notSynced.mapNotNullTo(HashSet()) { (it as? EntityRef.Snippet)?.id }

    fun isNotSynced(id: CanvasId): Boolean = EntityRef.Canvas(id) in notSynced

    fun isNotSynced(id: TagId): Boolean = EntityRef.Tag(id) in notSynced

    fun isNotSynced(id: BankWordId): Boolean = EntityRef.Word(id) in notSynced
}

/** The default (previews, tests): synced and online. */
val LocalSyncUi = compositionLocalOf { SyncUi() }

private fun SyncStatus.color(): Color = when (this) {
    SyncStatus.Synced -> Palette.Fits
    is SyncStatus.Syncing -> Palette.Primary
    is SyncStatus.Offline -> Palette.Muted
    is SyncStatus.NotSynced -> Palette.Error
}

/** The sync status indicator for top bars: "● Synced", "● Offline (3 pending)", "● 2 not synced". */
@Composable
fun SyncStatusChip(ui: SyncUi = LocalSyncUi.current) {
    val label = ui.status.label
    TextButton(onClick = ui.openNotSynced, modifier = Modifier.semantics { contentDescription = "Sync status: $label. Show changes not synced" }) {
        Text("● ", color = ui.status.color())
        Text(label, color = if (ui.status is SyncStatus.NotSynced) Palette.Error else Palette.Text, style = MaterialTheme.typography.labelMedium)
    }
}

/** The "Not synced" badge for list rows. */
@Composable
fun NotSyncedBadge(modifier: Modifier = Modifier) {
    Text(NOT_SYNCED_LABEL, color = Palette.Error, style = MaterialTheme.typography.labelSmall, modifier = modifier)
}

/**
 * A server-only action: enabled while the server is reachable; offline it is disabled and says
 * "Needs connection" under its label.
 */
@Composable
fun NeedsConnectionButton(label: String, onClick: () -> Unit, enabled: Boolean = true, online: Boolean = LocalSyncUi.current.online) {
    if (online) {
        TextButton(onClick = onClick, enabled = enabled) { Text(label) }
    } else {
        TextButton(onClick = {}, enabled = false, modifier = Modifier.semantics { contentDescription = "$label: $NEEDS_CONNECTION" }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label)
                Text(NEEDS_CONNECTION, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** A placeholder for a server-only panel while offline. */
@Composable
fun NeedsConnectionNote(what: String, modifier: Modifier = Modifier) {
    Text("$what: $NEEDS_CONNECTION", color = Palette.Muted, modifier = modifier)
}
