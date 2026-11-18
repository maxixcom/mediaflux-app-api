package ru.mediaflux.app.api.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable

/**
 * Файл медиа: отпечатки обложки и манифеста превью (`checksum`) — для адресов с `?v=`.
 *
 * Частичная сущность ТОЛЬКО ДЛЯ ЧТЕНИЯ (`@Immutable`): только колонки, нужные app-api. Схемой владеют миграции и
 * console/data-service; `ddl-auto: validate` сверяет эти колонки на старте. Статусы — строками, а не enum: появится
 * новое значение в другом сервисе — чтение не упадёт на «No enum constant», а правило каталога его просто не пропустит.
 */
@Entity
@Immutable
@Table(name = "t_media_file")
class MediaFileEntity(
    @Id
    @Column(name = "id")
    var id: Long = 0,

    @Column(name = "media_id")
    var mediaId: Long = 0,

    @Column(name = "role")
    var role: String = "",

    @Column(name = "status")
    var status: String = "",

    @Column(name = "is_deleted")
    var deleted: Boolean = false,

    @Column(name = "checksum")
    var checksum: String? = null,
)
