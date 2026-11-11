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
import dev.nymph.lyrics.app.tags.tagsViewModel
import dev.nymph.lyrics.app.tags.ui.TagPickerSheet
import dev.nymph.lyrics.app.wordbank.QuickAddToasts
import dev.nymph.lyrics.app.wordbank.ui.WordBankScreen
import dev.nymph.lyrics.app.wordbank.wordBankQuickAdd
import dev.nymph.lyrics.app.wordbank.wordBankTagsViewModel
import dev.nymph.lyrics.app.wordbank.wordBankViewModel
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import dev.nymph.lyrics.app.session.SessionViewModel
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.voice.RecordingId
import dev.nymph.lyrics.app.voice.check.CheckTranscriptScreen

/**
 * Login / register -> canvas list -> canvas -> editor (-> check transcript) / word bank. The session (token store) decides the
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
                onOpenSnippet = { canvas, snippet -> nav.navigate(CanvasRoute(canvas.value, focusSnippetId = snippet.value)) },
            )
        }
        composable<CanvasRoute> { entry ->
            val route = entry.toRoute<CanvasRoute>()
            CanvasScreen(
                container = container,
                canvasId = CanvasId(route.canvasId),
                onBack = { nav.popBackStack() },
                onOpenEditor = { id -> nav.navigate(EditorRoute(route.canvasId, id.value)) },
                onOpenWordBank = { nav.navigate(WordBankRoute(route.canvasId)) },
                onOpenCanvas = { id -> if (id.value != route.canvasId) nav.navigate(CanvasRoute(id.value)) },
            )
        }
        composable<EditorRoute> { entry -> EditorDestination(container, nav, entry) }
        composable<WordBankRoute> { entry -> WordBankDestination(container, nav, entry) }
        composable<CheckTranscriptRoute> { entry ->
            val route = entry.toRoute<CheckTranscriptRoute>()
            CheckTranscriptScreen(
                container = container,
                snippetId = SnippetId(route.snippetId),
                recordingId = RecordingId(route.recordingId),
                onClose = { if (nav.currentBackStackEntry?.id == entry.id) nav.popBackStack() },
            )
        }
    }
}

/**
 * The word bank shares the canvas entry's ViewModels: drafts are created on that canvas, and tag
 * edits made here reach its tag controls. Its own ViewModels (list, filter, picker) live here.
 */
@Composable
private fun WordBankDestination(container: AppContainer, nav: NavHostController, entry: NavBackStackEntry) {
    val route = entry.toRoute<WordBankRoute>()
    val canvasEntry = remember(entry) { nav.getBackStackEntry<CanvasRoute>() }
    val canvas = canvasViewModel(container, CanvasId(route.canvasId), canvasEntry)
    val canvasTags = tagsViewModel(container, CanvasId(route.canvasId), canvas, canvasEntry)
    val bank = wordBankViewModel(container, canvas)
    val bankTags = wordBankTagsViewModel(container, bank, canvasTags)
    WordBankScreen(bank, bankTags, onBack = { if (nav.currentBackStackEntry?.id == entry.id) nav.popBackStack() })
}

/**
 * The editor shares the canvas entry's ViewModels: it reads the snippet there and writes the saved
 * one back, and its "Tags" opens the canvas entry's tag picker for this snippet.
 */
@Composable
private fun EditorDestination(container: AppContainer, nav: NavHostController, entry: NavBackStackEntry) {
    val route = entry.toRoute<EditorRoute>()
    val canvasEntry = remember(entry) { nav.getBackStackEntry<CanvasRoute>() }
    val canvas = canvasViewModel(container, CanvasId(route.canvasId), canvasEntry)
    val tags = tagsViewModel(container, CanvasId(route.canvasId), canvas, canvasEntry)
    val tagsState by tags.state.collectAsStateWithLifecycle()
    val snippet = remember(entry) { canvas.snippet(SnippetId(route.snippetId)) }
    // Pop only while the editor is on top, so a save followed by a close leaves one screen.
    val close = { if (nav.currentBackStackEntry?.id == entry.id) nav.popBackStack() }
    if (snippet == null) {
        LaunchedEffect(entry) { close() }
        return
    }
    val quickAdd = wordBankQuickAdd(container, canvasEntry)
    QuickAddToasts(quickAdd)
    GridEditorRoute(
        container = container,
        snippet = snippet,
        onAddToWordBank = quickAdd::add,
        onSaved = { saved ->
            canvas.replaceSnippet(saved)
            close()
        },
        onClose = { close() },
        onSnippetChanged = canvas::replaceSnippet,
        extraTopBarActions = { TextButton(onClick = { tags.openPicker(setOf(snippet.id)) }) { Text("Tags") } },
        onCheckTranscript = { id -> nav.navigate(CheckTranscriptRoute(snippet.id.value, id.value)) },
        onOpenCanvas = { id -> if (nav.currentBackStackEntry?.id == entry.id) nav.navigate(CanvasRoute(id.value)) },
    )
    TagPickerSheet(tagsState, tags)
}
