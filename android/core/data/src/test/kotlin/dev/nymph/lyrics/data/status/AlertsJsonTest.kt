package dev.nymph.lyrics.data.status

import dev.nymph.lyrics.model.status.AlertSeverity
import dev.nymph.lyrics.model.status.AlertsFetch
import dev.nymph.lyrics.model.status.AlertsFetchError
import dev.nymph.lyrics.model.status.ClusterAlert
import dev.nymph.lyrics.model.status.IncidentKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant

class AlertsJsonTest {
    private fun parsed(body: String): AlertsParse.Parsed = parseAlerts(body) as? AlertsParse.Parsed ?: error("not parsed: ${parseAlerts(body)}")

    private fun single(entry: String): ClusterAlert = parsed("[$entry]").alerts.single()

    /** A valid active alert with [fields] replacing or adding top-level fields (raw JSON values). */
    private fun alert(vararg fields: Pair<String, String?>): String {
        val base = linkedMapOf(
            "labels" to """{"alertname":"BackendDown","severity":"critical","namespace":"lyrics"}""",
            "annotations" to """{"summary":"Backend has no ready pods","description":"All backend pods are not ready."}""",
            "startsAt" to "\"2026-10-28T10:00:00.000Z\"",
            "fingerprint" to "\"abc123\"",
            "status" to """{"state":"active","silencedBy":[],"inhibitedBy":[]}""",
        )
        fields.forEach { (k, v) -> if (v == null) base.remove(k) else base[k] = v }
        return base.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":$v" }
    }

    @Test
    fun `a full Alertmanager v2 alert becomes a domain object`() {
        val alert = single(AlertBodies.CRITICAL)
        assertEquals("BackendDown", alert.name)
        assertEquals(AlertSeverity.Critical, alert.severity)
        assertEquals("Backend has no ready pods", alert.summary)
        assertEquals("The lyrics-canvas backend deployment has 0 ready replicas.", alert.description)
        assertEquals(Instant.parse("2026-10-27T10:00:00.123Z"), alert.startsAt)
        assertEquals(IncidentKey("abc123@${Instant.parse("2026-10-27T10:00:00.123Z").toEpochMilli()}#critical"), alert.key)
    }

    @Test
    fun `an empty array is no alerts`() {
        assertEquals(AlertsParse.Parsed(emptyList(), malformed = 0), parseAlerts("[]"))
    }

    @Test
    fun `a body that is not a JSON array is not an alert list`() {
        listOf("""{"error":"x"}""", "<!DOCTYPE html><html></html>", "", "null", "\"[]\"").forEach {
            assertEquals(AlertsParse.NotAnArray, parseAlerts(it), it)
        }
    }

    @Test
    fun `malformed entries are dropped and counted, valid ones kept`() {
        val body = listOf(
            "null",
            "\"nope\"",
            "42",
            "{}",
            alert("labels" to """{"severity":"critical"}"""), // no alertname
            alert("labels" to """{"alertname":""}"""),
            alert("labels" to """{"alertname":"   "}"""),
            alert("labels" to """{"alertname":7}"""),
            alert("labels" to "\"x\""),
            alert("startsAt" to "\"not a date\""),
            alert("startsAt" to null),
            alert("startsAt" to "12"),
            alert("status" to null),
            alert("status" to """{"state":7}"""),
            alert("status" to "\"active\""),
            alert("fingerprint" to "\"ok1\""),
        ).joinToString(",", "[", "]")
        val result = parsed(body)
        assertEquals(1, result.alerts.size)
        assertEquals(15, result.malformed)
    }

    @Test
    fun `suppressed and unprocessed alerts are skipped without counting as malformed`() {
        val result = parsed(
            listOf(
                alert("status" to """{"state":"suppressed"}"""),
                alert("status" to """{"state":"unprocessed"}"""),
                alert("fingerprint" to "\"live\""),
            ).joinToString(",", "[", "]"),
        )
        assertEquals(listOf("BackendDown"), result.alerts.map { it.name })
        assertEquals(0, result.malformed)
    }

    @Test
    fun `severities map to critical, warning, or other with the label kept`() {
        fun sev(value: String?): AlertSeverity {
            val severity = if (value == null) "" else ""","severity":$value"""
            return single(alert("labels" to """{"alertname":"A"$severity}""")).severity
        }
        assertEquals(AlertSeverity.Critical, sev("\"critical\""))
        assertEquals(AlertSeverity.Critical, sev("\"CRITICAL\""))
        assertEquals(AlertSeverity.Warning, sev("\"warning\""))
        assertEquals(AlertSeverity.Other("info"), sev("\"info\""))
        assertEquals(AlertSeverity.Other(null), sev(null))
        assertEquals(AlertSeverity.Other(null), sev("3"))
    }

    @Test
    fun `missing or wrongly typed annotations become null`() {
        assertNull(single(alert("annotations" to null)).summary)
        val odd = single(alert("annotations" to """{"summary":5,"description":""}"""))
        assertNull(odd.summary)
        assertNull(odd.description)
        assertNull(single(alert("annotations" to "[]")).description)
    }

    @Test
    fun `startsAt with an offset is converted to an instant`() {
        assertEquals(Instant.parse("2026-10-27T10:00:00Z"), single(alert("startsAt" to "\"2026-10-27T12:00:00+02:00\"")).startsAt)
    }

    @Test
    fun `without a fingerprint the key comes from the sorted labels`() {
        val a = single(alert("fingerprint" to null, "labels" to """{"alertname":"X","b":"2","a":"1"}"""))
        val reordered = single(alert("fingerprint" to null, "labels" to """{"a":"1","alertname":"X","b":"2"}"""))
        val otherLabel = single(alert("fingerprint" to null, "labels" to """{"alertname":"X","b":"3","a":"1"}"""))
        assertEquals(a.key, reordered.key)
        assertNotEquals(a.key, otherLabel.key)
        assertTrue(a.key.value.contains("X"))
    }

    @Test
    fun `a new firing or a severity change is a new incident key`() {
        val base = single(alert()).key
        assertNotEquals(base, single(alert("startsAt" to "\"2026-10-28T12:00:00Z\"")).key)
        assertNotEquals(base, single(alert("labels" to """{"alertname":"BackendDown","severity":"warning","namespace":"lyrics"}""")).key)
        assertEquals(base, single(alert("annotations" to """{"summary":"changed text"}""")).key)
    }

    @Test
    fun `unknown extra fields are ignored`() {
        val alert = single(alert("generatorURL" to "\"http://x\"", "receivers" to """[{"name":"r"}]""", "updatedAt" to "\"2026-10-27T10:00:00Z\""))
        assertEquals("BackendDown", alert.name)
    }

    // --- interpretAlertsResponse: the endpoint contract ---

    @Test
    fun `200 with an array is the alert list`() {
        val fetch = interpretAlertsResponse(200, "[${AlertBodies.CRITICAL}, 7]") as AlertsFetch.Alerts
        assertEquals(listOf("BackendDown"), fetch.alerts.map { it.name })
        assertEquals(1, fetch.malformed)
    }

    @Test
    fun `200 empty array is no alerts`() {
        assertEquals(AlertsFetch.Alerts(emptyList(), 0), interpretAlertsResponse(200, "[]"))
    }

    @Test
    fun `503 alertmanager_unavailable is unavailable`() {
        assertEquals(AlertsFetch.Unavailable(503), interpretAlertsResponse(503, """{"error":"alertmanager_unavailable"}"""))
    }

    @ParameterizedTest
    @ValueSource(ints = [500, 502, 504])
    fun `other 5xx (proxy errors) are unavailable too`(status: Int) {
        assertEquals(AlertsFetch.Unavailable(status), interpretAlertsResponse(status, "<html>bad gateway</html>"))
    }

    @Test
    fun `404 is absent`() {
        assertEquals(AlertsFetch.Absent, interpretAlertsResponse(404, """{"detail":"Not Found"}"""))
    }

    @Test
    fun `200 html (SPA fallback) or non-array JSON is absent`() {
        assertEquals(AlertsFetch.Absent, interpretAlertsResponse(200, "<!DOCTYPE html><html></html>"))
        assertEquals(AlertsFetch.Absent, interpretAlertsResponse(200, """{"a":1}"""))
        assertEquals(AlertsFetch.Absent, interpretAlertsResponse(200, ""))
    }

    @ParameterizedTest
    @ValueSource(ints = [201, 301, 400, 403, 405])
    fun `an unexpected status is a typed http failure`(status: Int) {
        assertEquals(AlertsFetch.Failed(AlertsFetchError.Http(status)), interpretAlertsResponse(status, "x"))
    }
}

object AlertBodies {
    const val CRITICAL: String = """
        {
          "labels": {"alertname": "BackendDown", "severity": "critical", "namespace": "lyrics"},
          "annotations": {
            "summary": "Backend has no ready pods",
            "description": "The lyrics-canvas backend deployment has 0 ready replicas."
          },
          "startsAt": "2026-10-27T10:00:00.123Z",
          "endsAt": "2026-10-27T10:05:00Z",
          "fingerprint": "abc123",
          "status": {"state": "active", "silencedBy": [], "inhibitedBy": []}
        }
    """
}
