package dev.nymph.lyrics.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.nymph.lyrics.app.nav.AppRoot
import dev.nymph.lyrics.app.status.connectivityChanges
import dev.nymph.lyrics.app.ui.theme.LyricsTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as LyricsApp).container
        // Server health and alerts are polled only while the activity is visible.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.status.runInForeground(connectivityChanges(applicationContext))
            }
        }
        setContent { LyricsTheme { AppRoot(container) } }
    }
}
