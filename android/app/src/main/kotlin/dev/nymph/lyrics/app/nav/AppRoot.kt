package dev.nymph.lyrics.app.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.nymph.lyrics.app.di.AppContainer
import dev.nymph.lyrics.app.status.StatusBanner
import dev.nymph.lyrics.app.status.StatusBannerViewModel

/**
 * The status banner above the navigation host. While the banner shows it takes the status bar
 * inset, so the screens below (their top bars and scaffolds) do not pad for it twice.
 */
@Composable
fun AppRoot(container: AppContainer) {
    val banner: StatusBannerViewModel = viewModel {
        StatusBannerViewModel(container.status.health.state, container.status.alerts.state)
    }
    val bannerState by banner.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        StatusBanner(bannerState, onDismiss = banner::dismiss, onToggleExpanded = banner::toggleExpanded, modifier = Modifier.fillMaxWidth())
        val content = if (bannerState.isVisible) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier
        Box(Modifier.weight(1f).fillMaxWidth().then(content)) { AppNavHost(container) }
    }
}
