package ru.mediaflux.app.api.security

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.security.oauth2.jwt.Jwt
import ru.mediaflux.app.api.config.AppApiProperties
import ru.mediaflux.app.api.user.AppUser
import ru.mediaflux.app.api.user.UserLookup
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class AppAuthenticationConverterTest {

    private val props = AppApiProperties(clients = mapOf("tvos" to "mediaflux-app-tvos"))
    private val users = mock<UserLookup>()
    private val converter = AppAuthenticationConverter(props, users)
    private val sub = UUID.randomUUID()

    private fun jwt(azp: String?, subject: String = sub.toString(), sid: String? = "sid-1"): Jwt {
        val b = Jwt.withTokenValue("t").header("alg", "RS256").subject(subject)
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
        azp?.let { b.claim("azp", it) }
        sid?.let { b.claim("sid", it) }
        return b.build()
    }

    private fun user(active: Boolean = true) = AppUser(7, sub, "Имя", "a@b.c", active, channelsEnabled = true)

    @Test
    fun `токен кабинета не принимается`() {
        val ex = assertThrows<AccessRefusedException> { converter.convert(jwt(azp = "console-client-ui")) }
        assertEquals(AccessRefusedException.CLIENT_NOT_ALLOWED, ex.reason)
        verify(users, never()).bySub(any())
    }

    @Test
    fun `токен без azp не принимается`() {
        val ex = assertThrows<AccessRefusedException> { converter.convert(jwt(azp = null)) }
        assertEquals(AccessRefusedException.CLIENT_NOT_ALLOWED, ex.reason)
    }

    @Test
    fun `нет записи пользователя — account_not_ready`() {
        whenever(users.bySub(sub)).thenReturn(null)
        val ex = assertThrows<AccessRefusedException> { converter.convert(jwt(azp = "mediaflux-app-tvos")) }
        assertEquals(AccessRefusedException.ACCOUNT_NOT_READY, ex.reason)
    }

    @Test
    fun `заблокированный — account_disabled`() {
        whenever(users.bySub(sub)).thenReturn(user(active = false))
        val ex = assertThrows<AccessRefusedException> { converter.convert(jwt(azp = "mediaflux-app-tvos")) }
        assertEquals(AccessRefusedException.ACCOUNT_DISABLED, ex.reason)
    }

    @Test
    fun `свой клиент и активный пользователь — вход с sid`() {
        whenever(users.bySub(sub)).thenReturn(user())
        val auth = converter.convert(jwt(azp = "mediaflux-app-tvos")) as AppAuthentication
        assertEquals(7, auth.principal.user.id)
        assertEquals("sid-1", auth.principal.sid)
        assertEquals("mediaflux-app-tvos", auth.principal.clientId)
    }
}
