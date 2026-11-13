package dev.nymph.lyrics.app.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.sync.SyncCenter
import dev.nymph.lyrics.sync.conflict.ReviewItem
import dev.nymph.lyrics.sync.conflict.ReviewSide
import dev.nymph.lyrics.sync.conflict.lineDiff
import dev.nymph.lyrics.sync.outbox.OpId
import kotlinx.coroutines.launch

/** Every parked change, opened from the sync status indicator or a conflict alert. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotSyncedScreen(center: SyncCenter, onBack: () -> Unit, onReview: (OpId) -> Unit) {
    val items by center.reviewItems.collectAsStateWithLifecycle(initialValue = emptyList())
    Scaffold(
        containerColor = Palette.CanvasBackground,
        topBar = {
            TopAppBar(
                title = { Text("Not synced") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { SyncStatusChip() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Sidebar),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (items.isEmpty()) {
                Text(
                    "Every change is synced or waiting to sync. Nothing needs your review.",
                    color = Palette.Muted,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(items, key = { it.opId.value }) { item ->
                        ListItem(
                            overlineContent = { Text(kindLabel(item.kind)) },
                            headlineContent = { Text(item.label) },
                            supportingContent = { Text("Not synced: ${item.reason}", color = Palette.Error) },
                            colors = ListItemDefaults.colors(containerColor = Palette.CanvasBackground),
                            modifier = Modifier.clickable(onClickLabel = "Review") { onReview(item.opId) },
                        )
                        HorizontalDivider(color = Palette.Border)
                    }
                }
            }
        }
    }
}

/** Mine next to the server's, and the choices the user decisions allow. Closes once resolved. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(center: SyncCenter, opId: OpId, onClose: () -> Unit) {
    val item by center.reviewItem(opId).collectAsStateWithLifecycle(initialValue = null)
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(item) {
        if (item != null) loaded = true else if (loaded) onClose()
    }
    Scaffold(
        containerColor = Palette.CanvasBackground,
        topBar = {
            TopAppBar(
                title = { Text("Review change") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Sidebar),
            )
        },
    ) { padding ->
        val current = item
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                Text("This change is no longer waiting for review.", color = Palette.Muted, modifier = Modifier.align(Alignment.Center).padding(24.dp))
            }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("${kindLabel(current.kind)}: ${current.label}", style = MaterialTheme.typography.titleMedium)
            Text("Not synced: ${current.reason}.", color = Palette.Error)
            if (current.isMerge) MergeDetails(current) else Sides(current)
            error?.let { Text(it, color = Palette.Error) }
            current.resolutions.forEachIndexed { i, resolution ->
                val onClick: () -> Unit = {
                    busy = true
                    scope.launch {
                        error = center.resolve(current.opId, resolution)?.let(::resolveErrorMessage)
                        busy = false
                    }
                }
                val label = resolutionLabel(current, resolution)
                if (i == 0) {
                    OutlinedButton(onClick = onClick, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(label) }
                } else {
                    Button(onClick = onClick, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(label) }
                }
            }
        }
    }
}

@Composable
private fun MergeDetails(item: ReviewItem) {
    if (item.changedOriginals.isNotEmpty()) {
        Text("Originals changed on the server:", style = MaterialTheme.typography.labelLarge)
        item.changedOriginals.forEach { Text("• $it") }
    }
    if (item.missingOriginals > 0) Text("${item.missingOriginals} original(s) are no longer on the server.")
}

/** Both sides; for snippet texts, the lines the other side lacks are highlighted. */
@Composable
private fun Sides(item: ReviewItem) {
    val mine = item.mine
    val server = item.server
    val diff = if (mine is ReviewSide.OfSnippet && server is ReviewSide.OfSnippet) lineDiff(mine.text, server.text) else null
    Side("Mine (on this phone)", mine, diff?.mineOnly.orEmpty())
    Side("Server's", server, diff?.serverOnly.orEmpty())
}

@Composable
private fun Side(title: String, side: ReviewSide?, highlighted: Set<Int>) {
    Column(Modifier.fillMaxWidth().background(Palette.Card).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = Palette.Muted)
        if (side == null) {
            Text("Not available", color = Palette.Muted)
            return@Column
        }
        if (side is ReviewSide.OfSnippet) {
            side.text.split("\n").forEachIndexed { i, line ->
                val mark = Modifier.fillMaxWidth().let { if (i in highlighted) it.background(Palette.Primary.copy(alpha = 0.25f)) else it }
                Text(line.ifEmpty { " " }, fontFamily = FontFamily.Monospace, modifier = mark)
            }
        }
        sideSummary(side).forEach { Text(it, color = if (side is ReviewSide.OfSnippet) Palette.Muted else Palette.Text) }
    }
}
