package dev.nymph.lyrics.model.voice

/**
 * The `error` codes the voice routes put in `{"detail": {"error": code, "message": ...}}`
 * (see docs/features/voice_capture.md). Unknown codes parse to null.
 */
enum class VoiceErrorCode(val wire: String) {
    /** 404: the canvas is missing or not the caller's. */
    CANVAS_NOT_FOUND("canvas_not_found"),

    /** 404: the append target (or a recording's snippet) is missing or not the caller's. */
    SNIPPET_NOT_FOUND("snippet_not_found"),

    /** 404: the recording is missing or not the caller's. */
    RECORDING_NOT_FOUND("recording_not_found"),

    /** 413: larger than the server's upload limit. */
    AUDIO_TOO_LARGE("audio_too_large"),

    /** 415: a mime type the server does not accept. */
    UNSUPPORTED_AUDIO_TYPE("unsupported_audio_type"),

    /** 422: the `meta` field was rejected. */
    INVALID_META("invalid_meta"),

    /** 422: the transcriber heard no words. */
    EMPTY_TRANSCRIPT("empty_transcript"),

    /** 503: speech-to-text is not configured on this server. */
    STT_DISABLED("stt_disabled"),

    /** 503: the speech-to-text service did not answer (starting, overloaded, down). */
    STT_UNAVAILABLE("stt_unavailable"),

    /** 502: the speech-to-text service answered with an error. */
    STT_FAILED("stt_failed"),

    /** 422: a checked transcript that is empty after normalising, or over 10 000 code points. */
    INVALID_VERIFICATION("invalid_verification"),

    /** 413: the dataset export holds more audio than the server allows (web only; Android never exports). */
    EXPORT_TOO_LARGE("export_too_large"),
    ;

    companion object {
        fun parse(code: String?): VoiceErrorCode? = entries.firstOrNull { it.wire == code }
    }
}
