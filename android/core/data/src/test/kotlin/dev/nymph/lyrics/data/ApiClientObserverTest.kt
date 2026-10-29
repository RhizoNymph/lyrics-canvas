package dev.nymph.lyrics.data

import dev.nymph.lyrics.model.User
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.model.status.DownCause
import dev.nymph.lyrics.model.status.HealthSignal
import dev.nymph.lyrics.model.status.Outage
import dev.nymph.lyrics.model.status.UnreachableReason
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ApiClientObserverTest : MockServerTest() {
    private val me = listOf("api", "auth", "me")
    private val signals = java.util.Collections.synchronizedList(mutableListOf<HealthSignal>())
    private lateinit var observed: ApiClient

    @BeforeEach
    fun observe() {
        observed = ApiClient(server.url("/"), tokens, http, observer = { signals += it })
    }

    @ParameterizedTest
    @ValueSource(ints = [502, 503, 504])
    fun `gateway and unavailable statuses report the backend down`(status: Int) = runTest {
        enqueue(status, "<html>Bad Gateway</html>")
        val result = observed.get(me, User.serializer())
        assertEquals(ApiResult.Failure(ApiError.Server(status, null)), result)
        assertEquals(listOf(HealthSignal.Failed(Outage.BackendDown(DownCause.Http(status)))), signals)
    }

    @Test
    fun `a dropped connection reports unreachable`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        observed.get(me, User.serializer())
        assertEquals(listOf(HealthSignal.Failed(Outage.Unreachable(UnreachableReason.NETWORK))), signals)
    }

    @Test
    fun `an unreachable server reports unreachable`() = runTest {
        val url = server.url("/")
        server.shutdown()
        ApiClient(url, tokens, http, observer = { signals += it }).get(me, User.serializer())
        assertEquals(listOf(HealthSignal.Failed(Outage.Unreachable(UnreachableReason.NETWORK))), signals)
    }

    @Test
    fun `a timeout reports unreachable by timeout`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val impatient = OkHttpClient.Builder().readTimeout(200, TimeUnit.MILLISECONDS).build()
        ApiClient(server.url("/"), tokens, impatient, observer = { signals += it }).get(me, User.serializer())
        assertEquals(listOf(HealthSignal.Failed(Outage.Unreachable(UnreachableReason.TIMEOUT))), signals)
    }

    @ParameterizedTest
    @ValueSource(ints = [200, 400, 401, 403, 404, 422, 500, 501])
    fun `any other answer means the backend is up`(status: Int) = runTest {
        enqueue(status, if (status == 200) Bodies.USER else """{"detail":"x"}""")
        observed.get(me, User.serializer())
        assertEquals(listOf(HealthSignal.Up), signals)
    }

    @Test
    fun `a 2xx with an unreadable body still means the backend is up`() = runTest {
        enqueue(200, "not json")
        observed.get(me, User.serializer())
        assertEquals(listOf(HealthSignal.Up), signals)
    }

    @Test
    fun `callers see the same results with or without an observer`() = runTest {
        enqueue(502)
        enqueue(502)
        assertEquals(api.get(me, User.serializer()), observed.get(me, User.serializer()))
        enqueue(200, Bodies.USER)
        enqueue(200, Bodies.USER)
        assertEquals(api.get(me, User.serializer()), observed.get(me, User.serializer()))
    }

    @Test
    fun `a 401 still clears the token when observed`() = runTest {
        tokens.save("tok")
        enqueue(401, """{"detail":"expired"}""")
        observed.get(me, User.serializer())
        assertEquals(null, tokens.token())
        assertEquals(listOf(HealthSignal.Up), signals)
    }

    @Test
    fun `a cancelled call reports nothing`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val call = async { observed.get(me, User.serializer()) }
        runCurrent()
        take()
        call.cancel()
        call.join()
        assertEquals(emptyList<HealthSignal>(), signals)
    }

    @Test
    fun `signal classification`() {
        assertEquals(
            HealthSignal.Failed(Outage.Unreachable(UnreachableReason.NETWORK)),
            healthSignalOf(ApiResult.Failure(ApiError.Network(IOException("x")))),
        )
        assertEquals(
            HealthSignal.Failed(Outage.Unreachable(UnreachableReason.TIMEOUT)),
            healthSignalOf(ApiResult.Failure(ApiError.Network(SocketTimeoutException("x")))),
        )
        assertEquals(HealthSignal.Failed(Outage.BackendDown(DownCause.Http(504))), healthSignalOf(ApiResult.Failure(ApiError.Server(504, null))))
        assertEquals(HealthSignal.Up, healthSignalOf(ApiResult.Failure(ApiError.Server(500, null))))
        assertEquals(HealthSignal.Up, healthSignalOf(ApiResult.Failure(ApiError.Decoding(IllegalStateException("x")))))
        assertEquals(HealthSignal.Up, healthSignalOf(ApiResult.Success(Unit)))
    }
}
