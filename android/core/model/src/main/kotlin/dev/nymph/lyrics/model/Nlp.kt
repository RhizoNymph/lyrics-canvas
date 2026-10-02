package dev.nymph.lyrics.model

import kotlinx.serialization.Serializable

/** Body of `POST /api/nlp/syllabify`; the response is a [GridData]. */
@Serializable
data class SyllabifyRequest(
    val text: String,
)
