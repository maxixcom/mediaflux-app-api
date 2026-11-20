package ru.mediaflux.app.api.viewer

import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import ru.mediaflux.app.api.catalog.CatalogAssembler
import ru.mediaflux.app.api.catalog.CatalogService
import ru.mediaflux.app.api.entity.CatalogTargetType
import ru.mediaflux.app.api.repository.AppFavoriteRepository
import ru.mediaflux.app.api.support.db.DbGate
import ru.mediaflux.app.api.support.error.AppException
import ru.mediaflux.app.api.user.AppUser
import java.time.LocalDateTime
import java.time.ZoneOffset

data class FavoriteTarget(val type: CatalogTargetType, val id: Long)

/**
 * Избранное (MF-89, APPS.md §7): медиа, плейлисты и каналы. Пишется сразу (записи редкие), без кэша: читаем базу —
 * запрос по индексу пользователя, а свежесть важнее (§9, «своя запись»).
 *
 * Добавить можно только своё и видимое. Убрать — всегда (в том числе ставшее скрытым). Скрытое в список не попадает,
 * запись остаётся: вернётся, когда цель снова станет видимой.
 */
@Service
class FavoriteService(
    private val repo: AppFavoriteRepository,
    private val catalog: CatalogService,
    private val views: CatalogAssembler,
    private val tx: TransactionTemplate,
    private val dbGate: DbGate,
) {

    fun add(user: AppUser, target: FavoriteTarget) {
        if (!visible(user, target)) throw AppException.notFound("${target.type} ${target.id}")
        dbGate.run { tx.execute { repo.add(user.id, target.type.name, target.id, LocalDateTime.now(ZoneOffset.UTC)) } }
    }

    fun remove(user: AppUser, target: FavoriteTarget) {
        dbGate.run { tx.execute { repo.remove(user.id, target.type.name, target.id) } }
    }

    /** Цели в порядке добавления (новые сверху); видимость — потом, пачками по видам. */
    fun targets(userId: Long): List<FavoriteTarget> = dbGate.run {
        repo.findByUserIdOrderByCreatedAtDescIdDesc(userId).mapNotNull { f ->
            runCatching { CatalogTargetType.valueOf(f.targetType) }.getOrNull()?.let { FavoriteTarget(it, f.targetId) }
        }
    }

    /** Видимые элементы избранного в порядке добавления: пачка на каждый вид, без N+1. */
    fun items(user: AppUser, targets: List<FavoriteTarget> = targets(user.id)): List<Any> {
        val byType = targets.groupBy({ it.type }, { it.id })
        val media = views.mediaViews(user.id, byType[CatalogTargetType.MEDIA].orEmpty()).associateBy { it.id }
        val playlists = views.playlistViews(user.id, byType[CatalogTargetType.PLAYLIST].orEmpty()).associateBy { it.id }
        val channels = views.channelViews(user, byType[CatalogTargetType.CHANNEL].orEmpty()).associateBy { it.id }
        return targets.mapNotNull { t ->
            val key = t.id.toString()
            when (t.type) {
                CatalogTargetType.MEDIA -> media[key]
                CatalogTargetType.PLAYLIST -> playlists[key]
                CatalogTargetType.CHANNEL -> channels[key]
            }
        }
    }

    /** Что из [ids] вида [type] в избранном — для поля `favorite` пачкой. */
    fun favoriteIds(userId: Long, type: CatalogTargetType, ids: Collection<Long>): Set<Long> =
        if (ids.isEmpty()) emptySet() else dbGate.run { repo.favoriteIds(userId, type.name, ids).toSet() }

    private fun visible(user: AppUser, target: FavoriteTarget): Boolean = when (target.type) {
        CatalogTargetType.MEDIA -> catalog.media(user.id, listOf(target.id)).isNotEmpty()
        CatalogTargetType.PLAYLIST -> views.playlistViews(user.id, listOf(target.id)).isNotEmpty()
        CatalogTargetType.CHANNEL -> views.channelViews(user, listOf(target.id)).isNotEmpty()
    }
}
