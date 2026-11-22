package ru.mediaflux.app.api.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

/**
 * Устройство = установка приложения (APPS.md §6). Единственная таблица, в которую app-api пишет.
 * Время — UTC без зоны, задаётся явно в коде (`LocalDateTime.now(ZoneOffset.UTC)`).
 */
@Entity
@Table(name = "t_app_device")
class AppDeviceEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_app_device_id")
    @SequenceGenerator(name = "seq_app_device_id", sequenceName = "seq_app_device_id", allocationSize = 1)
    @Column(name = "id", nullable = false)
    var id: Long? = null,

    /** Наружный идентификатор (feedback: PK — bigint, наружу — отдельный UUID). */
    @Column(name = "public_id", nullable = false, updatable = false)
    var publicId: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false, updatable = false)
    var userId: Long = 0,

    @Column(name = "installation_id", nullable = false, updatable = false)
    var installationId: UUID = UUID(0, 0),

    @Column(name = "name", nullable = false, length = 100)
    var name: String = "",

    @Column(name = "platform", nullable = false, length = 16)
    var platform: String = "",

    @Column(name = "app_version", length = 32)
    var appVersion: String? = null,

    /** Сессия Keycloak (`sid`): по ней устройство находится на каждом запросе и гасится при отзыве. */
    @Column(name = "session_id", length = 64)
    var sessionId: String? = null,

    @Column(name = "last_seen_at")
    var lastSeenAt: LocalDateTime? = null,

    /** Отключено: в лимите не считается, запросы с его сессии не принимаются. */
    @Column(name = "revoked_at")
    var revokedAt: LocalDateTime? = null,

    /**
     * Офлайн-сессия Keycloak отключённого устройства закрыта (второй шаг отзыва). Отзыв из кабинета ставит только
     * `revoked_at` — сессию закрывает досылка app-api ([ru.mediaflux.app.api.device.DeviceSessionCloser]).
     */
    @Column(name = "session_closed_at")
    var sessionClosedAt: LocalDateTime? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.MIN,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.MIN,
)
