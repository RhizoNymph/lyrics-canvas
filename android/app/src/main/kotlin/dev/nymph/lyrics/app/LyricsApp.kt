package dev.nymph.lyrics.app

import android.app.Application
import dev.nymph.lyrics.app.di.AppContainer

class LyricsApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
