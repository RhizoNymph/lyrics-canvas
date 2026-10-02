package dev.nymph.lyrics.app.auth

import dev.nymph.lyrics.app.MainDispatcherExtension
import dev.nymph.lyrics.app.fakes.FakeAuthRepository
import dev.nymph.lyrics.data.InMemoryTokenStore
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val tokens = InMemoryTokenStore()
    private val auth = FakeAuthRepository(tokens)

    @Test
    fun `login sends trimmed email and stores the token`() = runTest(main.dispatcher) {
        val vm = AuthViewModel(auth)
        vm.setEmail("  a@b.c ")
        vm.setPassword("pw")
        vm.submit()
        assertTrue(vm.state.value.submitting)
        runCurrent()
        assertEquals(listOf("a@b.c" to "pw"), auth.logins)
        assertEquals("token", tokens.token())
        assertFalse(vm.state.value.submitting)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `missing fields are rejected without a request`() = runTest(main.dispatcher) {
        val vm = AuthViewModel(auth)
        vm.setEmail("a@b.c")
        vm.submit()
        runCurrent()
        assertTrue(auth.logins.isEmpty())
        assertEquals(AuthFieldError.MISSING_FIELDS, vm.state.value.fieldError)
    }

    @Test
    fun `wrong credentials show the server's message`() = runTest(main.dispatcher) {
        auth.loginResult = ApiResult.Failure(ApiError.Unauthorized(401, "Invalid email or password"))
        val vm = AuthViewModel(auth)
        vm.setEmail("a@b.c")
        vm.setPassword("bad")
        vm.submit()
        runCurrent()
        assertEquals("Invalid email or password", vm.state.value.error)
    }

    @Test
    fun `register needs a display name and sends it`() = runTest(main.dispatcher) {
        val vm = AuthViewModel(auth)
        vm.setMode(AuthMode.REGISTER)
        vm.setEmail("a@b.c")
        vm.setPassword("pw")
        vm.submit()
        runCurrent()
        assertEquals(AuthFieldError.MISSING_FIELDS, vm.state.value.fieldError)
        vm.setDisplayName("Ann")
        vm.submit()
        runCurrent()
        assertEquals(listOf(Triple("a@b.c", "pw", "Ann")), auth.registrations)
    }

    @Test
    fun `switching mode clears errors`() = runTest(main.dispatcher) {
        auth.loginResult = ApiResult.Failure(ApiError.Validation(400, "Email already registered"))
        val vm = AuthViewModel(auth)
        vm.setEmail("a@b.c")
        vm.setPassword("pw")
        vm.submit()
        runCurrent()
        assertEquals("Email already registered", vm.state.value.error)
        vm.setMode(AuthMode.REGISTER)
        assertNull(vm.state.value.error)
    }
}
