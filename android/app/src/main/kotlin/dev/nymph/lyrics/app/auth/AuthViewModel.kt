package dev.nymph.lyrics.app.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nymph.lyrics.app.ui.userMessage
import dev.nymph.lyrics.data.repository.AuthRepository
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuthMode { LOGIN, REGISTER }

enum class AuthFieldError { MISSING_FIELDS }

data class AuthUiState(
    val mode: AuthMode = AuthMode.LOGIN,
    val email: String = "",
    val password: String = "",
    val displayName: String = "",
    val submitting: Boolean = false,
    val fieldError: AuthFieldError? = null,
    /** The server's answer to a failed sign-in / registration. */
    val error: String? = null,
)

/**
 * Email / password sign-in and registration. Success stores the token (in the repository); the
 * session observes the token store and navigates, so this only reports failures.
 */
class AuthViewModel(private val auth: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    fun setMode(mode: AuthMode) = _state.update { it.copy(mode = mode, fieldError = null, error = null) }

    fun setEmail(value: String) = _state.update { it.copy(email = value, fieldError = null) }

    fun setPassword(value: String) = _state.update { it.copy(password = value, fieldError = null) }

    fun setDisplayName(value: String) = _state.update { it.copy(displayName = value, fieldError = null) }

    fun submit() {
        val s = _state.value
        if (s.submitting) return
        val email = s.email.trim()
        val name = s.displayName.trim()
        if (email.isEmpty() || s.password.isEmpty() || (s.mode == AuthMode.REGISTER && name.isEmpty())) {
            _state.update { it.copy(fieldError = AuthFieldError.MISSING_FIELDS) }
            return
        }
        _state.update { it.copy(submitting = true, error = null, fieldError = null) }
        viewModelScope.launch {
            val result = when (s.mode) {
                AuthMode.LOGIN -> auth.login(email, s.password)
                AuthMode.REGISTER -> auth.register(email, s.password, name)
            }
            val error = (result as? ApiResult.Failure)?.error?.let { e ->
                // A 401 here means wrong credentials, not an expired session.
                if (e is ApiError.Unauthorized) e.detail ?: "Invalid email or password" else e.userMessage()
            }
            _state.update { it.copy(submitting = false, error = error) }
        }
    }
}
