package dev.nymph.lyrics.sync.engine

import dev.nymph.lyrics.model.BankWord
import dev.nymph.lyrics.model.Canvas
import dev.nymph.lyrics.model.MergeResponse
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.TagId
import dev.nymph.lyrics.model.TransferResponse
import dev.nymph.lyrics.model.UserTag
import dev.nymph.lyrics.model.WordBankAddResponse
import dev.nymph.lyrics.model.error.ApiError
import kotlinx.serialization.json.JsonElement

/** What the server answered for a successful operation, by kind. */
sealed interface OpResult {
    /** Create, content edit or layout update: the snippet in the operation's canvas context. */
    data class SnippetSaved(val snippet: Snippet) : OpResult

    /** A tag link / unlink: the snippet's new version (null when the server did not say). */
    data class SnippetVersioned(val id: SnippetId, val version: Int?) : OpResult

    data class CanvasSaved(val canvas: Canvas) : OpResult

    data class TagSaved(val tag: UserTag) : OpResult

    /** A create refused as `tag_name_taken`: the server's tag of that name, which the local one becomes. */
    data class TagMatched(val local: TagId, val server: UserTag) : OpResult

    data class WordsSaved(val words: List<BankWord>) : OpResult

    data class WordsAdded(val response: WordBankAddResponse) : OpResult

    data class Merged(val response: MergeResponse) : OpResult

    data class Transferred(val response: TransferResponse) : OpResult

    /** A delete or remove (204, or 404: already gone). */
    data object Done : OpResult
}

/** The outcome of sending one batch. Nothing here is thrown. */
sealed interface SendOutcome {
    data class Ok(val result: OpResult) : SendOutcome

    /** 409 `version_conflict`: nothing changed on the server; [current] is `detail.current` (one object or a list). */
    data class VersionConflict(val current: JsonElement?) : SendOutcome

    /** 404 for an operation that is not a delete. */
    data class NotFound(val code: String?) : SendOutcome

    /** Refused for good: 4xx (422, `id_conflict`, a taken name). */
    data class Refused(val error: ApiError) : SendOutcome

    /** Network, timeout, 5xx, 401: retried later, in order. */
    data class Transient(val error: ApiError) : SendOutcome

    /** A 2xx the app could not read: applied on the server; the snapshot brings the result. */
    data class Unreadable(val error: ApiError) : SendOutcome
}
