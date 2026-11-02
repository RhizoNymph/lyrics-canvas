package dev.nymph.lyrics.app.voice.recordings

import dev.nymph.lyrics.logic.list.parseServerTimestamp
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm", Locale.US)

/** `m:ss`, or `h:mm:ss` from an hour; negative durations show as 0:00. */
fun formatDuration(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1_000
    val h = total / 3_600
    val m = (total % 3_600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** A recording's `created_at` in [zone] (`Nov 1, 2026 14:05`), or the raw text when unreadable. */
fun formatRecordingDate(createdAt: String, zone: ZoneId): String =
    parseServerTimestamp(createdAt)?.atZone(zone)?.format(DATE_FORMAT) ?: createdAt

/** The first transcript line(s) joined, cut to [max] characters with an ellipsis. */
fun transcriptPreview(transcript: String, max: Int = 80): String {
    val flat = transcript.lines().map(String::trim).filter(String::isNotEmpty).joinToString(" / ")
    return if (flat.length <= max) flat else flat.take(max - 1).trimEnd() + "…"
}
