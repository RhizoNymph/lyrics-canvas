package dev.nymph.lyrics.logic.score

import dev.nymph.lyrics.model.score.Score
import dev.nymph.lyrics.model.score.ScoreNote
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A bpm as JSON: an integer when integral (90, not 90.0), else the double. */
private fun bpmJson(bpm: Double): JsonPrimitive =
    if (bpm % 1.0 == 0.0 && kotlin.math.abs(bpm) <= ScoreRules.MAX_SAFE_INT) JsonPrimitive(bpm.toLong()) else JsonPrimitive(bpm)

/** The score as JSON in the spec's key order (`v, ppq, pickup, meters, tempos, snap, lines`; notes `s, t, d, a`, then extras). */
fun scoreToJson(score: Score): JsonObject = buildJsonObject {
    put("v", score.v)
    put("ppq", score.ppq)
    put("pickup", score.pickup)
    put("meters", buildJsonArray { score.meters.forEach { m -> add(buildJsonObject { put("bar", m.bar); put("num", m.num); put("den", m.den) }) } })
    put("tempos", buildJsonArray { score.tempos.forEach { t -> add(buildJsonObject { put("bar", t.bar); put("bpm", bpmJson(t.bpm)) }) } })
    put("snap", score.snap.wire)
    put(
        "lines",
        buildJsonArray {
            score.lines.forEach { line ->
                add(buildJsonObject { put("notes", buildJsonArray { line.notes.forEach { add(noteJson(it)) } }) })
            }
        },
    )
}

private fun noteJson(note: ScoreNote): JsonObject = buildJsonObject {
    put("s", note.s)
    put("t", note.t)
    put("d", note.d)
    put("a", note.a)
    note.extra.forEach { (key, value) -> if (key !in ScoreNote.CORE_KEYS) put(key, value) }
}

/** The note of syllable [s] on grid line [line], if placed. */
fun Score.noteAt(line: Int, s: Int): ScoreNote? = lines.getOrNull(line)?.notes?.firstOrNull { it.s == s }

/** Every note in score order (lines in order, notes in order) with its line index. */
fun Score.allNotes(): List<Pair<Int, ScoreNote>> = lines.flatMapIndexed { index, line -> line.notes.map { index to it } }
