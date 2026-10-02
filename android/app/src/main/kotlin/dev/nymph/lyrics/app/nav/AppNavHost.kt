package dev.nymph.lyrics.app.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.nymph.lyrics.app.auth.AuthScreen
import dev.nymph.lyrics.app.canvas.CanvasScreen
import dev.nymph.lyrics.app.canvas.canvasViewModel
import dev.nymph.lyrics.app.canvaslist.CanvasListScreen
import dev.nymph.lyrics.app.di.AppContainer
import dev.nymph.lyrics.app.editor.GridEditorRoute
import dev.nymph.lyrics.app.session.SessionState
import dev.nymph.lyrics.app.session.SessionViewModel
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.SnippetId

/**
 * Login / register -> canvas list -> canvas -> editor. The session (token store) decides the
 * start destination and sends the user back to login whenever the token goes away (sign out or
 * a 401 anywhere).
 */
@Composable
fun AppNavHost(container: AppContainer) {
    val session: SessionViewModel = viewModel { SessionViewModel(container.tokenStore, container.auth) }
    val sessionState by session.state.collectAsStateWithLifecycle()
    if (sessionState == SessionState.Unknown) {
        Box(Modifier.fillMaxSize().background(Palette.CanvasBackground))
        return
    }
    val nav = rememberNavController()
    val start: Any = remember { if (sessionState == SessionState.SignedIn) CanvasListRoute else AuthRoute }

    LaunchedEffect(sessionState) {
        val onAuth = nav.currentDestination?.hierarchy?.any { it.hasRoute<AuthRoute>() } == true
        when (sessionState) {
            SessionState.SignedOut -> if (!onAuth) nav.navigate(AuthRoute) { popUpTo(nav.graph.id) { inclusive = true } }
            SessionState.SignedIn -> if (onAuth) nav.navigate(CanvasListRoute) { popUpTo(nav.graph.id) { inclusive = true } }
            SessionState.Unknown -> Unit
        }
    }

    NavHost(nav, startDestination = start) {
        composable<AuthRoute> { AuthScreen(container) }
        composable<CanvasListRoute> {
            CanvasListScreen(
                container = container,
                onOpen = { id -> nav.navigate(CanvasRoute(id.value)) },
                onSignOut = session::signOut,
            )
        }
        composable<CanvasRoute> { entry ->
            val route = entry.toRoute<CanvasRoute>()
            CanvasScreen(
                container = container,
                canvasId = CanvasId(route.canvasId),
                onBack = { nav.popBackStack() },
                onOpenEditor = { id -> nav.navigate(EditorRoute(route.canvasId, id.value)) },
            )
        }
        composable<EditorRoute> { entry -> EditorDestination(container, nav, entry) }
    }
}

/** The editor shares the canvas entry's ViewModel: it reads the snippet there and writes the saved one back. */
@Composable
private fun EditorDestination(container: AppContainer, nav: NavHostController, entry: NavBackStackEntry) {
    val route = entry.toRoute<EditorRoute>()
    val canvasEntry = remember(entry) { nav.getBackStackEntry<CanvasRoute>() }
    val canvas = canvasViewModel(container, CanvasId(route.canvasId), canvasEntry)
    val snippet = remember(entry) { canvas.snippet(SnippetId(route.snippetId)) }
    // Pop only while the editor is on top, so a save followed by a close leaves one screen.
    val close = { if (nav.currentBackStackEntry?.id == entry.id) nav.popBackStack() }
    if (snippet == null) {
        LaunchedEffect(entry) { close() }
        return
    }
    GridEditorRoute(
        container = container,
        snippet = snippet,
        onSaved = { saved ->
            canvas.replaceSnippet(saved)
            close()
        },
        onClose = { close() },
    )
}
