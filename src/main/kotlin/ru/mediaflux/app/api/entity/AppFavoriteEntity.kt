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
 * Избранное (MF-89, APPS.md §7): медиа, плейлисты и каналы одной таблицей. Внешнего ключа на цель нет — скрытое и
 * удалённое отсеивает правило видимости каталога, запись остаётся.
 */
@Entity
@Table(name = "t_app_favorite")
class AppFavoriteEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_app_favorite_id")
    @SequenceGenerator(name = "seq_app_favorite_id", sequenceName = "seq_app_favorite_id", allocationSize = 1)
    @Column(name = "id")
    var id: Long? = null,

    @Column(name = "user_id", nullable = false)
    var userId: Long = 0,

    @Column(name = "target_type", nullable = false, length = 16)
    var targetType: String = "",

    @Column(name = "target_id", nullable = false)
    var targetId: Long = 0,

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.MIN,
)
