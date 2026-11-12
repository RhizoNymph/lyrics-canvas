package dev.nymph.lyrics.sync.store

import dev.nymph.lyrics.model.UserId
import dev.nymph.lyrics.sync.SyncLog
import dev.nymph.lyrics.sync.error
import dev.nymph.lyrics.sync.info
import dev.nymph.lyrics.sync.warn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The local copy's single owner. Readers observe [data] (immutable snapshots); every change goes
 * through [update], one at a time under a mutex, and is written to [file] before [data] shows it,
 * so what a screen sees is what survives a restart. The file is read on first use; operations left
 * in flight by a killed process go back to pending then.
 */
class LocalStore(private val file: StoreFile, private val log: SyncLog = SyncLog.None) {
    private val mutex = Mutex()
    private val _data = MutableStateFlow(LocalData())
    private var loaded = false

    /** The current copy (empty until [ensureLoaded]). */
    val data: StateFlow<LocalData> = _data.asStateFlow()

    suspend fun ensureLoaded(): LocalData = mutex.withLock { loadLocked() }

    private suspend fun loadLocked(): LocalData {
        if (loaded) return _data.value
        loaded = true
        when (val read = file.read()) {
            StoreRead.Missing -> Unit
            is StoreRead.Loaded -> _data.value = read.data.withOutbox(read.data.outbox.recovered())
            is StoreRead.Corrupt -> log.error("sync.store_corrupt", "reason" to read.reason, "set_aside" to read.setAsideAs)
        }
        log.info("sync.store_loaded", "canvases" to _data.value.canvases.size, "snippets" to _data.value.snippets.size, "outbox" to _data.value.outbox.entries.size)
        return _data.value
    }

    /** Apply [change] to the current copy; returns its result. A change returning the same instance writes nothing. */
    suspend fun <R> update(change: (LocalData) -> Pair<LocalData, R>): R = mutex.withLock {
        val current = loadLocked()
        val (next, result) = change(current)
        if (next !== current) commitLocked(next)
        result
    }

    private suspend fun commitLocked(next: LocalData) {
        file.write(next)?.let { log.error("sync.store_write_failed", "reason" to it.reason) }
        _data.value = next
    }

    /**
     * The copy belongs to [account]: a different account's copy is deleted first (an account
     * switch); an unclaimed copy is claimed.
     */
    suspend fun claim(account: UserId) = mutex.withLock {
        val current = loadLocked()
        when (current.account) {
            account -> Unit
            null -> commitLocked(current.copy(account = account))
            else -> {
                log.warn("sync.store_account_switch", "pending" to current.outbox.entries.size)
                file.delete()
                commitLocked(LocalData(account = account))
            }
        }
    }

    /** Sign-out: the copy and its outbox are deleted. */
    suspend fun wipe() = mutex.withLock {
        loaded = true
        file.delete()
        _data.value = LocalData()
        log.info("sync.store_wiped")
    }
}
