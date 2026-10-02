package dev.nymph.lyrics.app.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * A scope that lives as long as the process, for writes that must finish after the screen that
 * started them is gone (the canvas viewport saved on leave). ApiClient calls are main-safe.
 */
object ProcessScope {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}
