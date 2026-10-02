package dev.nymph.lyrics.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.nymph.lyrics.app.nav.AppNavHost
import dev.nymph.lyrics.app.ui.theme.LyricsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as LyricsApp).container
        setContent { LyricsTheme { AppNavHost(container) } }
    }
}
