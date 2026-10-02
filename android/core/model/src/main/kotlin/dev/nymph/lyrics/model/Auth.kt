package dev.nymph.lyrics.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Body of `POST /api/auth/login`. */
@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
)

/** Body of `POST /api/auth/register`. */
@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    @SerialName("display_name") val displayName: String,
)

/** Response of login and register. */
@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
)

/** Response of `GET /api/auth/me`. */
@Serializable
data class User(
    val id: UserId,
    val email: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("oauth_provider") val oauthProvider: String? = null,
    @SerialName("created_at") val createdAt: String,
)
