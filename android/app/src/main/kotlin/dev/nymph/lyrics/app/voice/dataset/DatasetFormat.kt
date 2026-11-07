package dev.nymph.lyrics.app.voice.dataset

import dev.nymph.lyrics.model.voice.RecordingStats

/** `42s`, `4m 10s`, `1h 2m` (seconds dropped from an hour); negative is `0s`. */
fun formatAudioLength(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1_000
    val h = total / 3_600
    val m = (total % 3_600) / 60
    val s = total % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}

/** `12 of 15 takes checked · 4m 10s checked audio · 9 with word timings`. */
fun datasetSummary(stats: RecordingStats): String {
    val takes = if (stats.total == 1) "take" else "takes"
    return "${stats.verified} of ${stats.total} $takes checked · ${formatAudioLength(stats.verifiedDurationMs)} checked audio · " +
        "${stats.withTimings} with word timings"
}

/** The dialog's note: export is a web feature. */
const val EXPORT_ON_WEB: String = "Export the dataset from the web app"
