package ru.mediaflux.app.api.device

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import ru.mediaflux.app.api.config.AppApiProperties
import ru.mediaflux.app.api.entity.AppDeviceEntity
import ru.mediaflux.app.api.keycloak.KeycloakAdminClient
import ru.mediaflux.app.api.repository.AppDeviceRepository
import ru.mediaflux.app.api.security.AppPrincipal
import ru.mediaflux.app.api.support.cache.Caches
import ru.mediaflux.app.api.support.cache.await
import ru.mediaflux.app.api.support.db.DbGate
import ru.mediaflux.app.api.support.error.AppErrorType
import ru.mediaflux.app.api.support.error.AppException
import ru.mediaflux.app.api.support.error.ErrorCodes
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** Состояние устройства, которое нужно на каждом запросе. */
data class DeviceState(val id: Long, val publicId: UUID, val userId: Long, val revoked: Boolean)

data class DeviceRegistration(val installationId: UUID, val name: String, val platform: String, val appVersion: String)

/**
 * Устройства (APPS.md §6): регистрация с лимитом, переименование, отзыв, проверка на каждом запросе.
 *
 * **Кэш устройства по `sid` — только найденное, 60 с.** Отзыв доходит до соседней копии за минуту (принято, §9),
 * на своей копии — сразу. «Не найдено» не кэшируем: иначе после `deviceRegister` на одной копии соседняя ещё
 * минуту отвечала бы `DEVICE_NOT_REGISTERED` по старому ответу. Незарегистрированные сессии бывают только во
 * время подключения, их запросы в базу дёшевы (индекс по `session_id`).
 */
@Service
class DeviceService(
    private val repo: AppDeviceRepository,
    private val tx: TransactionTemplate,
    private val dbGate: DbGate,
    private val keycloak: KeycloakAdminClient,
    private val caches: Caches,
    private val props: AppApiProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val bySid = caches.plain<String, DeviceState>("app_device_by_sid", maxSize = 100_000, ttl = Duration.ofSeconds(60))

    /** Когда последний раз писали `last_seen_at` по устройству (в памяти копии). */
    private val lastSeenWritten = ConcurrentHashMap<Long, Long>()

    val limit: Int get() = props.devices.limit

    /** Устройство текущей сессии; null — не зарегистрировано (или сессия чужая). */
    fun current(principal: AppPrincipal): DeviceState? {
        val sid = principal.sid ?: return null
        val state = bySid.getIfPresent(sid)?.await()
            ?: loadBySid(sid)?.also { bySid.put(sid, CompletableFuture.completedFuture(it)) }
            ?: return null
        return state.takeIf { it.userId == principal.user.id }
    }

    /** Отметить заход — не чаще интервала на устройство и не задерживая ответ. */
    fun touch(device: DeviceState) {
        val now = System.currentTimeMillis()
        val interval = props.devices.lastSeenWriteInterval.toMillis()
        val prev = lastSeenWritten[device.id]
        if (prev != null && now - prev < interval) return
        lastSeenWritten[device.id] = now
        caches.executor.execute {
            runCatching { dbGate.run { tx.executeWithoutResult { repo.touch(device.id, nowUtc()) } } }
                .onFailure { log.debug("last_seen_at устройства {} не записан: {}", device.id, it.message) }
        }
    }

    fun list(principal: AppPrincipal): List<AppDeviceEntity> =
        dbGate.run { repo.findByUserIdAndRevokedAtIsNullOrderByCreatedAtAsc(principal.user.id) }

    /**
     * Подключить устройство: запись по паре «пользователь + установка». Повтор для той же установки слот не тратит —
     * иначе его тратил бы каждый новый вход (смена сервера, истёкшая за 30 дней офлайн-сессия).
     *
     * Гонку двух регистраций одного пользователя закрывает advisory-lock по пользователю в той же транзакции.
     */
    fun register(principal: AppPrincipal, input: DeviceRegistration): AppDeviceEntity {
        val sid = principal.sid
            ?: throw AppException(AppErrorType.BAD_INPUT, ErrorCodes.BAD_INPUT, "В токене нет sid — устройство не к чему привязать")
        val userId = principal.user.id
        var previousSid: String? = null
        val saved = dbGate.run {
            tx.execute {
                lockUser(userId)
                val existing = repo.findByUserIdAndInstallationId(userId, input.installationId)
                if (existing?.revokedAt != null && existing.sessionId == sid) {
                    // Отключили именно эту сессию — вернуть её нельзя, нужен новый вход.
                    throw AppException(AppErrorType.FORBIDDEN, ErrorCodes.DEVICE_REVOKED, "Сессия $sid отключена")
                }
                if (existing == null || existing.revokedAt != null) {
                    val active = repo.countActiveExcept(userId, input.installationId)
                    if (active >= limit) {
                        throw AppException(
                            AppErrorType.LIMIT, ErrorCodes.DEVICE_LIMIT_REACHED,
                            "Подключено $active из $limit устройств", mapOf("limit" to limit),
                        )
                    }
                }
                val now = nowUtc()
                val device = existing ?: AppDeviceEntity(userId = userId, installationId = input.installationId, createdAt = now)
                previousSid = existing?.sessionId
                device.name = input.name.trim()
                device.platform = input.platform
                device.appVersion = input.appVersion.trim()
                device.sessionId = sid
                device.revokedAt = null
                device.lastSeenAt = now
                device.updatedAt = now
                repo.save(device)
            }!!
        }
        bySid.synchronous().invalidate(sid)
        previousSid?.takeIf { it != sid }?.let { bySid.synchronous().invalidate(it) }
        log.info("Устройство {} пользователя {} подключено ({}, {})", saved.publicId, userId, saved.platform, saved.appVersion)
        return saved
    }

    fun rename(principal: AppPrincipal, publicId: UUID, name: String): AppDeviceEntity = dbGate.run {
        tx.execute {
            val device = ownActive(principal, publicId)
            device.name = name.trim()
            device.updatedAt = nowUtc()
            repo.save(device)
        }!!
    }

    /**
     * Отключить устройство (APPS.md §6, оба шага). Сначала пометка — она сразу закрывает устройство на этой копии и
     * за минуту на остальных. Потом удаление офлайн-сессии в Keycloak, чтобы новых токенов оно не получило; сбой
     * второго шага пометку не отменяет.
     */
    fun revoke(principal: AppPrincipal, publicId: UUID): Boolean {
        val sid = dbGate.run {
            tx.execute {
                val device = ownActive(principal, publicId)
                val now = nowUtc()
                device.revokedAt = now
                device.updatedAt = now
                repo.save(device)
                device.sessionId
            }
        }
        if (sid != null) {
            bySid.synchronous().invalidate(sid)
            keycloak.deleteOfflineSession(sid)
        }
        log.info("Устройство {} пользователя {} отключено", publicId, principal.user.id)
        return true
    }

    private fun ownActive(principal: AppPrincipal, publicId: UUID): AppDeviceEntity =
        repo.findByUserIdAndPublicId(principal.user.id, publicId)?.takeIf { it.revokedAt == null }
            ?: throw AppException.notFound("Устройство $publicId")

    private fun loadBySid(sid: String): DeviceState? = dbGate.run {
        repo.findFirstBySessionIdOrderByUpdatedAtDesc(sid)
            ?.let { DeviceState(it.id!!, it.publicId, it.userId, it.revokedAt != null) }
    }

    private fun lockUser(userId: Long) {
        repo.lockUser(LOCK_SPACE, Math.toIntExact(userId))
    }

    private fun nowUtc(): LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)

    private companion object {
        /** Пространство advisory-блокировок устройств (номер задачи, где они появились). */
        const val LOCK_SPACE = 88
    }
}
