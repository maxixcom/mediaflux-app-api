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
 * Недавние поиски (MF-114, APPS.md §7): материал, на чью карточку перешли из результатов поиска. Общие на аккаунт,
 * тексты запросов не храним. Цель — как в избранном ([CatalogTargetType] + id), без внешнего ключа на неё: скрытое и
 * удалённое отсеивает правило видимости каталога.
 */
@Entity
@Table(name = "t_app_search_recent")
class AppSearchRecentEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_app_search_recent_id")
    @SequenceGenerator(name = "seq_app_search_recent_id", sequenceName = "seq_app_search_recent_id", allocationSize = 1)
    @Column(name = "id")
    var id: Long? = null,

    @Column(name = "user_id", nullable = false)
    var userId: Long = 0,

    @Column(name = "target_type", nullable = false, length = 16)
    var targetType: String = "",

    @Column(name = "target_id", nullable = false)
    var targetId: Long = 0,

    /** Когда выбрали в последний раз: повторный выбор поднимает наверх. */
    @Column(name = "selected_at", nullable = false)
    var selectedAt: LocalDateTime = LocalDateTime.MIN,
)
