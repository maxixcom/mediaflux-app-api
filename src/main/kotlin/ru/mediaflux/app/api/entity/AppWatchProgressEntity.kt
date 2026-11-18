package ru.mediaflux.app.api.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * Позиция просмотра и отметка «просмотрено» (MF-89, APPS.md §7): строка на пару «пользователь + медиа», общая для всех
 * устройств. Пишется не через сущность, а upsert'ом из буфера (`AppWatchProgressRepository.upsert`) — с условием по
 * времени отчёта клиента; сущность — для чтения и сверки схемы на старте.
 */
@Entity
@Table(name = "t_app_watch_progress")
class AppWatchProgressEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_app_watch_progress_id")
    @SequenceGenerator(name = "seq_app_watch_progress_id", sequenceName = "seq_app_watch_progress_id", allocationSize = 1)
    @Column(name = "id")
    var id: Long? = null,

    @Column(name = "user_id", nullable = false)
    var userId: Long = 0,

    @Column(name = "media_id", nullable = false)
    var mediaId: Long = 0,

    @Column(name = "position_ms", nullable = false)
    var positionMs: Long = 0,

    @Column(name = "duration_ms")
    var durationMs: Long? = null,

    @Column(name = "watched", nullable = false)
    var watched: Boolean = false,

    @Column(name = "watched_at")
    var watchedAt: LocalDateTime? = null,

    @Column(name = "reported_at", nullable = false)
    var reportedAt: LocalDateTime = LocalDateTime.MIN,

    @Column(name = "device_id")
    var deviceId: Long? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.MIN,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.MIN,
)
