package ru.mediaflux.app.api.device

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import ru.mediaflux.app.api.config.AppApiProperties
import ru.mediaflux.app.api.entity.AppDeviceEntity
import ru.mediaflux.app.api.keycloak.KeycloakAdminClient
import ru.mediaflux.app.api.repository.AppDeviceRepository
import ru.mediaflux.app.api.security.AppPrincipal
import ru.mediaflux.app.api.support.cache.Caches
import ru.mediaflux.app.api.support.db.DbGate
import ru.mediaflux.app.api.support.error.AppException
import ru.mediaflux.app.api.support.error.ErrorCodes
import ru.mediaflux.app.api.user.AppUser
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceServiceTest {

    private val props = AppApiProperties(devices = AppApiProperties.Devices(limit = 3))
    private val repo = mock<AppDeviceRepository>()
    private val keycloak = mock<KeycloakAdminClient>()
    private val txManager = mock<PlatformTransactionManager>().also {
        whenever(it.getTransaction(any())).thenReturn(SimpleTransactionStatus())
    }
    private val meters = SimpleMeterRegistry()
    private val service = DeviceService(
        repo, TransactionTemplate(txManager), DbGate(props, meters), keycloak, Caches(meters), props,
    )

    private val user = AppUser(10, UUID.randomUUID(), "Имя", null, true, true)
    private val principal = AppPrincipal(user, sid = "sid-new", clientId = "mediaflux-app-tvos")
    private val installation = UUID.randomUUID()
    private val input = DeviceRegistration(installation, "Гостиная", "TVOS", "0.1.0")

    init {
        whenever(repo.save(any<AppDeviceEntity>())).thenAnswer { it.arguments[0] }
    }

    @Test
    fun `новое устройство сверх лимита — DEVICE_LIMIT_REACHED`() {
        whenever(repo.findByUserIdAndInstallationId(10, installation)).thenReturn(null)
        whenever(repo.countActiveExcept(10, installation)).thenReturn(3)
        val ex = assertThrows<AppException> { service.register(principal, input) }
        assertEquals(ErrorCodes.DEVICE_LIMIT_REACHED, ex.code)
        assertEquals(3, ex.params["limit"])
        verify(repo, never()).save(any<AppDeviceEntity>())
    }

    @Test
    fun `повторная регистрация той же установки слот не тратит`() {
        val existing = AppDeviceEntity(id = 1, userId = 10, installationId = installation, sessionId = "sid-old", createdAt = LocalDateTime.now())
        whenever(repo.findByUserIdAndInstallationId(10, installation)).thenReturn(existing)
        val saved = service.register(principal, input)
        assertEquals("sid-new", saved.sessionId)
        assertEquals("Гостиная", saved.name)
        verify(repo, never()).countActiveExcept(any(), any())
    }

    @Test
    fun `отключённая сессия не возвращается — DEVICE_REVOKED`() {
        val revoked = AppDeviceEntity(id = 1, userId = 10, installationId = installation, sessionId = "sid-new", revokedAt = LocalDateTime.now())
        whenever(repo.findByUserIdAndInstallationId(10, installation)).thenReturn(revoked)
        val ex = assertThrows<AppException> { service.register(principal, input) }
        assertEquals(ErrorCodes.DEVICE_REVOKED, ex.code)
    }

    @Test
    fun `после нового входа отключённая установка оживает, если есть слот`() {
        val revoked = AppDeviceEntity(id = 1, userId = 10, installationId = installation, sessionId = "sid-old", revokedAt = LocalDateTime.now())
        whenever(repo.findByUserIdAndInstallationId(10, installation)).thenReturn(revoked)
        whenever(repo.countActiveExcept(10, installation)).thenReturn(2)
        val saved = service.register(principal, input)
        assertNull(saved.revokedAt)
        assertEquals("sid-new", saved.sessionId)
    }

    @Test
    fun `чужая сессия не даёт устройство`() {
        val other = AppDeviceEntity(id = 5, userId = 99, installationId = installation, sessionId = "sid-new")
        whenever(repo.findFirstBySessionIdOrderByUpdatedAtDesc("sid-new")).thenReturn(other)
        assertNull(service.current(principal))
    }

    @Test
    fun `отзыв ставит пометку и удаляет сессию в Keycloak`() {
        val publicId = UUID.randomUUID()
        val device = AppDeviceEntity(id = 3, publicId = publicId, userId = 10, installationId = installation, sessionId = "sid-tv")
        whenever(repo.findByUserIdAndPublicId(10, publicId)).thenReturn(device)
        service.revoke(principal, publicId)
        assert(device.revokedAt != null)
        verify(keycloak).deleteOfflineSession("sid-tv")
    }
}
