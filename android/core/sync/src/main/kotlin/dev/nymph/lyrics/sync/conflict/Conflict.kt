package dev.nymph.lyrics.sync.conflict

import dev.nymph.lyrics.model.BankWord
import dev.nymph.lyrics.model.Canvas
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.UserTag
import dev.nymph.lyrics.sync.outbox.EntityRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The server's current copy of an entity, from a 409 `version_conflict` (`detail.current`). */
@Serializable
sealed interface ServerCopy {
    val version: Int

    @Serializable
    @SerialName("snippet")
    data class OfSnippet(val snippet: Snippet) : ServerCopy {
        override val version: Int get() = snippet.version
    }

    @Serializable
    @SerialName("canvas")
    data class OfCanvas(val canvas: Canvas) : ServerCopy {
        override val version: Int get() = canvas.version
    }

    @Serializable
    @SerialName("tag")
    data class OfTag(val tag: UserTag) : ServerCopy {
        override val version: Int get() = tag.version
    }

    @Serializable
    @SerialName("word")
    data class OfWord(val word: BankWord) : ServerCopy {
        override val version: Int get() = word.version
    }
}

/**
 * Why a change is parked ("Not synced"). Parked changes are never retried on their own; the user
 * resolves them on the review screen ([Resolution]).
 */
@Serializable
sealed interface Conflict {
    /** The entity the badge goes on (for a merge, the merged snippet). */
    val entity: EntityRef

    /** The server's copy changed since the version the local change started from. */
    @Serializable
    @SerialName("changed")
    data class Changed(override val entity: EntityRef, val server: ServerCopy) : Conflict

    /** The server no longer has the entity (404 for an edit). */
    @Serializable
    @SerialName("deleted_on_server")
    data class DeletedOnServer(override val entity: EntityRef) : Conflict

    /**
     * A merge whose originals changed ([changed], the server's copies) or are gone ([missing]
     * ids) on the server. The whole merge is parked.
     */
    @Serializable
    @SerialName("merge_changed")
    data class MergeChanged(override val entity: EntityRef, val changed: List<Snippet> = emptyList(), val missing: List<SnippetId> = emptyList()) : Conflict

    /** The server refused the change for good (a taken name, an invalid value, a canvas gone). */
    @Serializable
    @SerialName("rejected")
    data class Rejected(override val entity: EntityRef, val message: String) : Conflict
}

/** What the user can do with a parked change. */
enum class Resolution {
    /** Discard mine; the server's copy (or its deletion) stays. */
    KEEP_SERVERS,

    /** Resend mine, based on the server's current version. */
    OVERWRITE_WITH_MINE,

    /** A snippet only: create a new snippet with my content next to the original; the original takes the server's copy. */
    SAVE_MINE_AS_NEW,

    /** A merge only: send it again on the originals' current versions. */
    RETRY_MERGE,
}

/** The choices the review offers for [conflict] (contract: user decisions). */
fun resolutionsFor(conflict: Conflict): List<Resolution> = when (conflict) {
    is Conflict.Changed -> when (conflict.entity) {
        is EntityRef.Snippet -> listOf(Resolution.KEEP_SERVERS, Resolution.OVERWRITE_WITH_MINE, Resolution.SAVE_MINE_AS_NEW)
        is EntityRef.Canvas, is EntityRef.Tag, is EntityRef.Word -> listOf(Resolution.KEEP_SERVERS, Resolution.OVERWRITE_WITH_MINE)
    }
    is Conflict.DeletedOnServer -> when (conflict.entity) {
        is EntityRef.Snippet -> listOf(Resolution.KEEP_SERVERS, Resolution.SAVE_MINE_AS_NEW)
        is EntityRef.Canvas, is EntityRef.Tag, is EntityRef.Word -> listOf(Resolution.KEEP_SERVERS)
    }
    is Conflict.MergeChanged -> if (conflict.missing.isEmpty()) listOf(Resolution.KEEP_SERVERS, Resolution.RETRY_MERGE) else listOf(Resolution.KEEP_SERVERS)
    is Conflict.Rejected -> listOf(Resolution.KEEP_SERVERS)
}
