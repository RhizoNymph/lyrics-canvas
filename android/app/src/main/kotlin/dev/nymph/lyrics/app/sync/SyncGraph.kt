package dev.nymph.lyrics.app.sync

import android.util.Log
import dev.nymph.lyrics.data.ApiClient
import dev.nymph.lyrics.logic.status.HealthState
import dev.nymph.lyrics.sync.LogLevel
import dev.nymph.lyrics.sync.SyncCenter
import dev.nymph.lyrics.sync.SyncLog
import dev.nymph.lyrics.sync.apply.LocalEdits
import dev.nymph.lyrics.sync.conflict.Resolver
import dev.nymph.lyrics.sync.engine.HttpOpSender
import dev.nymph.lyrics.sync.engine.SyncEngine
import dev.nymph.lyrics.sync.engine.Wake
import dev.nymph.lyrics.sync.repo.LocalFirst
import dev.nymph.lyrics.sync.repo.OnlineGate
import dev.nymph.lyrics.sync.store.JsonStoreFile
import dev.nymph.lyrics.sync.store.LocalStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.util.UUID

/** Logcat, `event=... key=value` (tag `Sync`). */
object LogcatSyncLog : SyncLog {
    private const val TAG = "Sync"

    override fun log(level: LogLevel, event: String, fields: Map<String, Any?>) {
        val line = SyncLog.format(event, fields)
        when (level) {
            LogLevel.DEBUG -> Log.d(TAG, line)
            LogLevel.INFO -> Log.i(TAG, line)
            LogLevel.WARN -> Log.w(TAG, line)
            LogLevel.ERROR -> Log.e(TAG, line)
        }
    }
}

/** Reachable unless the health monitor has confirmed an outage (the status banner's source). */
fun isOnline(state: HealthState): Boolean = state !is HealthState.Down

/** Whether [next] is the server coming back (worth sending now and refreshing). */
fun cameBack(previous: HealthState, next: HealthState): Boolean =
    (next is HealthState.Healthy || next is HealthState.BackOnline) && previous !is HealthState.Healthy && previous !is HealthState.BackOnline

/**
 * The offline copy and sync (docs/features/android_offline_sync.md): the store in the app's files
 * directory, the engine running for the process, and what the repositories and screens need.
 */
class SyncGraph(filesDir: File, api: ApiClient, health: StateFlow<HealthState>, private val scope: CoroutineScope) {
    private val log: SyncLog = LogcatSyncLog
    private val foregroundState = MutableStateFlow(false)

    val store = LocalStore(JsonStoreFile(File(File(filesDir, "offline"), "store.json")), log)

    val online: StateFlow<Boolean> = health.map(::isOnline).stateIn(scope, SharingStarted.Eagerly, isOnline(health.value))

    val gate = OnlineGate(online)

    val engine = SyncEngine(store, HttpOpSender(api), log, foreground = foregroundState.asStateFlow())

    val local = LocalFirst(store, engine)

    val edits = LocalEdits(now = { Instant.now().toString() }, newId = { UUID.randomUUID().toString() })

    val center = SyncCenter(
        store = store,
        trigger = engine,
        resolver = Resolver(newId = { UUID.randomUUID().toString() }, now = { Instant.now().toString() }),
        online = online,
        activity = engine.activity,
        alerts = engine.alerts,
    )

    private var started = false

    /** Start the engine and the health wake-ups (once per process). */
    fun start(health: StateFlow<HealthState>) {
        if (started) return
        started = true
        scope.launch { engine.run() }
        scope.launch {
            var previous = health.value
            health.collect { next ->
                if (cameBack(previous, next)) engine.wake(Wake.HEALTHY)
                previous = next
            }
        }
    }

    /** The activity is visible: send and refresh now, and tick while something is pending. */
    fun onForeground() {
        foregroundState.value = true
        engine.wake(Wake.FOREGROUND)
    }

    fun onBackground() {
        foregroundState.value = false
    }
}
