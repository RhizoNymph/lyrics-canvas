package dev.nymph.lyrics.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the bearer token lives. The app provides a DataStore-backed implementation; tests use
 * [InMemoryTokenStore]. [ApiClient] reads it before every call and clears it on a 401.
 */
interface TokenStore {
    /** The current token and every change to it; null while signed out. */
    val tokens: Flow<String?>

    suspend fun token(): String?

    suspend fun save(token: String)

    suspend fun clear()
}

/** A process-local [TokenStore]. */
class InMemoryTokenStore(initial: String? = null) : TokenStore {
    private val state = MutableStateFlow(initial)

    override val tokens: StateFlow<String?> = state.asStateFlow()

    override suspend fun token(): String? = state.value

    override suspend fun save(token: String) {
        state.value = token
    }

    override suspend fun clear() {
        state.value = null
    }
}
