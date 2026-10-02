package dev.nymph.lyrics.app.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nymph.lyrics.data.TokenStore
import dev.nymph.lyrics.data.repository.AuthRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface SessionState {
    /** The token store has not answered yet (first frame after launch). */
    data object Unknown : SessionState

    data object SignedOut : SessionState

    data object SignedIn : SessionState
}

/**
 * Signed-in state, straight from the token store. A 401 on any call clears the store (ApiClient),
 * so it shows up here as [SessionState.SignedOut] and the nav host returns to login.
 */
class SessionViewModel(tokenStore: TokenStore, private val auth: AuthRepository) : ViewModel() {
    val state: StateFlow<SessionState> = tokenStore.tokens
        .map { if (it == null) SessionState.SignedOut else SessionState.SignedIn }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, SessionState.Unknown)

    fun signOut() {
        viewModelScope.launch { auth.logout() }
    }
}
