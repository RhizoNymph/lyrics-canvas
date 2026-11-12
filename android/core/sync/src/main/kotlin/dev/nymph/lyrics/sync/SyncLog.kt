package dev.nymph.lyrics.sync

/** Log levels (debug: development detail, info: operational, warn: recovered, error: needs attention). */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** Structured logging: an event name plus key-value fields. The app writes them to logcat as `event=... key=value`. */
fun interface SyncLog {
    fun log(level: LogLevel, event: String, fields: Map<String, Any?>)

    companion object {
        val None: SyncLog = SyncLog { _, _, _ -> }

        /** `event=<event> k=v ...` (the app's logcat format). */
        fun format(event: String, fields: Map<String, Any?>): String =
            (listOf("event=$event") + fields.map { (k, v) -> "$k=$v" }).joinToString(" ")
    }
}

fun SyncLog.debug(event: String, vararg fields: Pair<String, Any?>) = log(LogLevel.DEBUG, event, fields.toMap())

fun SyncLog.info(event: String, vararg fields: Pair<String, Any?>) = log(LogLevel.INFO, event, fields.toMap())

fun SyncLog.warn(event: String, vararg fields: Pair<String, Any?>) = log(LogLevel.WARN, event, fields.toMap())

fun SyncLog.error(event: String, vararg fields: Pair<String, Any?>) = log(LogLevel.ERROR, event, fields.toMap())
