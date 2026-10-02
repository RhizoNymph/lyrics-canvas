package dev.nymph.lyrics.data.repository

import dev.nymph.lyrics.data.ApiClient
import dev.nymph.lyrics.data.HttpMethod
import dev.nymph.lyrics.data.TokenStore
import dev.nymph.lyrics.model.LoginRequest
import dev.nymph.lyrics.model.RegisterRequest
import dev.nymph.lyrics.model.TokenResponse
import dev.nymph.lyrics.model.User
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.model.error.flatMap

/** Email/password auth. Signing in stores the token, then loads the user with it. */
interface AuthRepository {
    suspend fun login(email: String, password: String): ApiResult<User>

    suspend fun register(email: String, password: String, displayName: String): ApiResult<User>

    suspend fun me(): ApiResult<User>

    suspend fun logout()
}

class HttpAuthRepository(private val api: ApiClient, private val tokenStore: TokenStore) : AuthRepository {
    override suspend fun login(email: String, password: String): ApiResult<User> =
        api.send(HttpMethod.POST, listOf("api", "auth", "login"), LoginRequest(email, password), LoginRequest.serializer(), TokenResponse.serializer())
            .flatMap { signIn(it) }

    override suspend fun register(email: String, password: String, displayName: String): ApiResult<User> =
        api.send(
            HttpMethod.POST,
            listOf("api", "auth", "register"),
            RegisterRequest(email, password, displayName),
            RegisterRequest.serializer(),
            TokenResponse.serializer(),
        ).flatMap { signIn(it) }

    override suspend fun me(): ApiResult<User> = api.get(listOf("api", "auth", "me"), User.serializer())

    override suspend fun logout() = tokenStore.clear()

    private suspend fun signIn(token: TokenResponse): ApiResult<User> {
        tokenStore.save(token.accessToken)
        return me()
    }
}
