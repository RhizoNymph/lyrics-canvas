package dev.nymph.lyrics.data.status

import dev.nymph.lyrics.data.MockServerTest
import dev.nymph.lyrics.model.status.AlertsFetch
import dev.nymph.lyrics.model.status.AlertsFetchError
import dev.nymph.lyrics.model.status.DownCause
import dev.nymph.lyrics.model.status.HealthSignal
import dev.nymph.lyrics.model.status.Outage
import dev.nymph.lyrics.model.status.UnreachableReason
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class HttpStatusRepositoryTest : MockServerTest() {
    private fun repo(alerts: AlertsEndpoint = AlertsEndpoint.At(server.url("/_status/alerts")), client: OkHttpClient = http) =
        HttpStatusRepository(server.url("/"), alerts, client)

    private val impatient = OkHttpClient.Builder().readTimeout(200, TimeUnit.MILLISECONDS).build()

    // --- health ---

    @Test
    fun `200 status ok is up, probed with GET api health and no auth`() = runTest {
        tokens.save("tok")
        enqueue(200, """{"status":"ok"}""")
        assertEquals(HealthSignal.Up, repo().probeHealth())
        val request = take()
        assertRequest(request, "GET", "/api/health")
        assertNull(request.getHeader("Authorization"))
    }

    @Test
    fun `any non-200 status is backend down with that status`() = runTest {
        for (status in listOf(502, 503, 504, 500, 404)) {
            enqueue(status, "<html>$status</html>")
            assertEquals(HealthSignal.Failed(Outage.BackendDown(DownCause.Http(status))), repo().probeHealth(), "status $status")
        }
    }

    @Test
    fun `200 with another body is backend down with an invalid body`() = runTest {
        for (body in listOf("""{"status":"degraded"}""", "<!doctype html><html></html>", "", """{"status":1}""")) {
            enqueue(200, body)
            assertEquals(HealthSignal.Failed(Outage.BackendDown(DownCause.InvalidBody)), repo().probeHealth(), body)
        }
    }

    @Test
    fun `no answer is unreachable`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(HealthSignal.Failed(Outage.Unreachable(UnreachableReason.NETWORK)), repo().probeHealth())
        val url = server.url("/")
        server.shutdown()
        assertEquals(
            HealthSignal.Failed(Outage.Unreachable(UnreachableReason.NETWORK)),
            HttpStatusRepository(url, AlertsEndpoint.Disabled, http).probeHealth(),
        )
    }

    @Test
    fun `a hung server is unreachable by timeout`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertEquals(HealthSignal.Failed(Outage.Unreachable(UnreachableReason.TIMEOUT)), repo(client = impatient).probeHealth())
    }

    @Test
    fun `health is resolved under a base path prefix`() = runTest {
        enqueue(200, """{"status":"ok"}""")
        HttpStatusRepository(server.url("/lyrics/"), AlertsEndpoint.Disabled, http).probeHealth()
        assertEquals("/lyrics/api/health", take().path)
    }

    // --- alerts ---

    @Test
    fun `200 array parses the alerts from the configured URL`() = runTest {
        enqueue(200, "[${AlertBodies.CRITICAL}, 7]")
        val fetch = repo().fetchAlerts() as AlertsFetch.Alerts
        assertEquals(listOf("BackendDown"), fetch.alerts.map { it.name })
        assertEquals(1, fetch.malformed)
        assertRequest(take(), "GET", "/_status/alerts")
    }

    @Test
    fun `200 empty array is no alerts`() = runTest {
        enqueue(200, "[]")
        assertEquals(AlertsFetch.Alerts(emptyList(), 0), repo().fetchAlerts())
    }

    @Test
    fun `503 is unavailable`() = runTest {
        enqueue(503, """{"error":"alertmanager_unavailable"}""")
        assertEquals(AlertsFetch.Unavailable(503), repo().fetchAlerts())
    }

    @Test
    fun `404 and an html fallback are absent`() = runTest {
        enqueue(404, """{"detail":"Not Found"}""")
        assertEquals(AlertsFetch.Absent, repo().fetchAlerts())
        enqueue(200, "<!doctype html><html></html>")
        assertEquals(AlertsFetch.Absent, repo().fetchAlerts())
    }

    @Test
    fun `other statuses are http failures`() = runTest {
        enqueue(403, "")
        assertEquals(AlertsFetch.Failed(AlertsFetchError.Http(403)), repo().fetchAlerts())
    }

    @Test
    fun `no answer is a network failure, a hung server a timeout`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val fetch = repo().fetchAlerts()
        assertTrue((fetch as AlertsFetch.Failed).error is AlertsFetchError.Network, "was $fetch")
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val slow = repo(client = impatient).fetchAlerts()
        assertTrue((slow as AlertsFetch.Failed).error is AlertsFetchError.Timeout, "was $slow")
    }

    @Test
    fun `a disabled endpoint never makes a request`() = runTest {
        assertEquals(AlertsFetch.Disabled, repo(AlertsEndpoint.Disabled).fetchAlerts())
        assertEquals(0, server.requestCount)
    }

    // --- endpoint configuration ---

    @Test
    fun `blank derives the alerts URL from the API base (nginx origin)`() {
        val base = "http://10.1.1.69:32443/".toHttpUrl()
        assertEquals(AlertsEndpoint.At("http://10.1.1.69:32443/_status/alerts".toHttpUrl()), AlertsEndpoint.resolve("", base))
        assertEquals(AlertsEndpoint.At("http://10.1.1.69:32443/_status/alerts".toHttpUrl()), AlertsEndpoint.resolve("  ", "http://10.1.1.69:32443".toHttpUrl()))
        assertEquals(AlertsEndpoint.At("http://h/app/_status/alerts".toHttpUrl()), AlertsEndpoint.resolve("", "http://h/app/".toHttpUrl()))
    }

    @Test
    fun `an explicit URL is used as given (backend-direct API base)`() {
        val base = "http://100.64.0.10:8000".toHttpUrl()
        assertEquals(
            AlertsEndpoint.At("http://10.1.1.69:32443/_status/alerts".toHttpUrl()),
            AlertsEndpoint.resolve("http://10.1.1.69:32443/_status/alerts", base),
        )
    }

    @Test
    fun `none disables alerts, anything else is invalid`() {
        val base = "http://h".toHttpUrl()
        assertEquals(AlertsEndpoint.Disabled, AlertsEndpoint.resolve("none", base))
        assertNull(AlertsEndpoint.resolve("10.1.1.69:32443/_status/alerts", base))
        assertNull(AlertsEndpoint.resolve("ftp://x/_status/alerts", base))
    }
}
