package dev.nymph.lyrics.sync.snapshot

import dev.nymph.lyrics.model.SyncSnapshot
import dev.nymph.lyrics.sync.outbox.EntityRef
import dev.nymph.lyrics.sync.store.LocalData
import dev.nymph.lyrics.sync.store.LocalPlacement
import dev.nymph.lyrics.sync.store.LocalSnippet
import dev.nymph.lyrics.sync.store.LocalTag
import dev.nymph.lyrics.sync.store.LocalWord

/**
 * Merge a server snapshot into the local copy (pure):
 *
 * - The server wins for every entity no queued or parked operation touches.
 * - An entity some operation touches keeps its local copy (or its local absence: a pending
 *   delete), placements included. That covers pending creates, which the snapshot cannot have
 *   yet, and parked conflicts, whose local version the review shows.
 * - Everything else missing from the snapshot is gone locally too.
 * - A placement needs its canvas and its snippet; a snippet with no placement left goes.
 */
object SnapshotMerge {
    fun merge(local: LocalData, snapshot: SyncSnapshot): LocalData {
        val kept = local.outbox.touchedEntities()
        fun keeps(ref: EntityRef) = ref in kept

        val canvases = snapshot.canvases.filterNot { keeps(EntityRef.Canvas(it.id)) } +
            local.canvases.filter { keeps(EntityRef.Canvas(it.id)) }
        val canvasIds = canvases.mapTo(HashSet()) { it.id }

        val keptSnippets = local.snippets.filter { keeps(EntityRef.Snippet(it.id)) }
        val serverSnippets = snapshot.snippets.filterNot { keeps(EntityRef.Snippet(it.id)) }.map(LocalSnippet::of)
        val placements = (
            snapshot.placements.filterNot { keeps(EntityRef.Snippet(it.snippetId)) }.map(LocalPlacement::of) +
                local.placements.filter { keeps(EntityRef.Snippet(it.snippetId)) }
            ).filter { it.canvasId in canvasIds }
        val placed = placements.mapTo(HashSet()) { it.snippetId }
        // A kept snippet stays even with no placement (its parked change still needs reviewing).
        val snippets = keptSnippets + serverSnippets.filter { it.id in placed }

        val tags = snapshot.tags.filterNot { keeps(EntityRef.Tag(it.id)) }.map(LocalTag::of) + local.tags.filter { keeps(EntityRef.Tag(it.id)) }
        val words = snapshot.wordBank.filterNot { keeps(EntityRef.Word(it.id)) }.map(LocalWord::of) + local.words.filter { keeps(EntityRef.Word(it.id)) }

        val snippetIds = snippets.mapTo(HashSet()) { it.id }
        return local.copy(
            canvases = canvases,
            snippets = snippets,
            placements = placements.filter { it.snippetId in snippetIds },
            tags = tags,
            presets = snapshot.tagPresets,
            words = words,
            snapshotAt = snapshot.serverTime,
        )
    }
}
