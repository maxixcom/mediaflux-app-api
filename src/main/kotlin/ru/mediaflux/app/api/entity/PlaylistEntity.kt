package ru.mediaflux.app.api.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime
import org.hibernate.annotations.Immutable

/**
 * Плейлист — подборка или курс в приложении.
 *
 * Частичная сущность ТОЛЬКО ДЛЯ ЧТЕНИЯ (`@Immutable`): только колонки, нужные app-api. Схемой владеют миграции и
 * console/data-service; `ddl-auto: validate` сверяет эти колонки на старте. Статусы — строками, а не enum: появится
 * новое значение в другом сервисе — чтение не упадёт на «No enum constant», а правило каталога его просто не пропустит.
 */
@Entity
@Immutable
@Table(name = "t_playlist")
class PlaylistEntity(
    @Id
    @Column(name = "id")
    var id: Long = 0,

    @Column(name = "user_id")
    var userId: Long = 0,

    @Column(name = "code")
    var code: String = "",

    @Column(name = "title")
    var title: String = "",

    @Column(name = "description")
    var description: String? = null,

    @Column(name = "status")
    var status: String = "",

    @Column(name = "moderation_status")
    var moderationStatus: String = "",

    @Column(name = "created_at")
    var createdAt: LocalDateTime = LocalDateTime.MIN,
)
