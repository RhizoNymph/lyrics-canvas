package dev.nymph.lyrics.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * The one Json configuration for the Lyrics Canvas API.
 *
 * - `ignoreUnknownKeys`: the backend may add fields; old clients keep working.
 * - `explicitNulls = false`: a null property is omitted on encode (the backend treats an absent
 *   field and `null` the same on every route the app calls) and a missing nullable decodes as null.
 * - `encodeDefaults`: non-null defaults (e.g. `context: []`, `k: 2`) are always sent.
 */
@OptIn(ExperimentalSerializationApi::class)
val LyricsJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}
