package ru.mediaflux.app.api.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.LocalDateTime
import org.hibernate.annotations.Immutable

/**
 * Медиа — фильм, ролик или серия в приложении.
 *
 * Частичная сущность ТОЛЬКО ДЛЯ ЧТЕНИЯ (`@Immutable`): только колонки, нужные app-api. Схемой владеют миграции и
 * console/data-service; `ddl-auto: validate` сверяет эти колонки на старте. Статусы — строками, а не enum: появится
 * новое значение в другом сервисе — чтение не упадёт на «No enum constant», а правило каталога его просто не пропустит.
 */
@Entity
@Immutable
@Table(name = "t_media")
class MediaEntity(
    @Id
    @Column(name = "id")
    var id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    var project: ProjectEntity? = null,

    @Column(name = "user_id")
    var userId: Long = 0,

    @Column(name = "code")
    var code: String = "",

    @Column(name = "title")
    var title: String = "",

    @Column(name = "description")
    var description: String? = null,

    @Column(name = "duration_ms")
    var durationMs: Long? = null,

    @Column(name = "status")
    var status: String = "",

    @Column(name = "is_deleted")
    var deleted: Boolean = false,

    @Column(name = "moderation_status")
    var moderationStatus: String = "",

    @Column(name = "video_enabled")
    var videoEnabled: Boolean = false,

    @Column(name = "chapters_enabled")
    var chaptersEnabled: Boolean = false,

    @Column(name = "created_at")
    var createdAt: LocalDateTime = LocalDateTime.MIN,
)
