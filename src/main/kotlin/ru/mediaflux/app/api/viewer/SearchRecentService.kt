package ru.mediaflux.app.api.viewer

import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import ru.mediaflux.app.api.catalog.CatalogAssembler
import ru.mediaflux.app.api.catalog.CatalogService
import ru.mediaflux.app.api.catalog.MediaView
import ru.mediaflux.app.api.entity.CatalogTargetType
import ru.mediaflux.app.api.repository.AppSearchRecentRepository
import ru.mediaflux.app.api.support.db.DbGate
import ru.mediaflux.app.api.support.error.AppException
import ru.mediaflux.app.api.user.AppUser
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Недавние поиски (MF-114, APPS.md §7) — как в приложении Apple TV: не тексты запросов, а материалы, на чьи карточки
 * человек перешёл из результатов. Общие на аккаунт, последние [LIMIT], свежие первыми; повторный выбор поднимает
 * наверх. Сейчас поиск находит только медиа — поэтому только медиа.
 *
 * Как избранное: пишется сразу, без кэша (своя запись важнее свежести кэша, §9); записать можно только своё и видимое;
 * скрытое в список не попадает, а строка остаётся, пока её не вытеснят свежие.
 */
@Service
class SearchRecentService(
    private val repo: AppSearchRecentRepository,
    private val catalog: CatalogService,
    private val views: CatalogAssembler,
    private val tx: TransactionTemplate,
    private val dbGate: DbGate,
) {

    fun add(user: AppUser, mediaId: Long) {
        if (catalog.media(user.id, listOf(mediaId)).isEmpty()) throw AppException.notFound("Медиа $mediaId")
        dbGate.run {
            tx.execute {
                repo.touch(user.id, CatalogTargetType.MEDIA.name, mediaId, LocalDateTime.now(ZoneOffset.UTC))
                repo.trim(user.id, LIMIT)
            }
        }
    }

    /** Видимые материалы истории, свежие первыми — одной пачкой через кэш карточек. */
    fun items(user: AppUser): List<MediaView> {
        val ids = dbGate.run { repo.findByUserIdOrderBySelectedAtDescIdDesc(user.id) }
            .filter { it.targetType == CatalogTargetType.MEDIA.name }
            .map { it.targetId }
        return views.mediaViews(user.id, ids)
    }

    /** Очистить — на всех устройствах аккаунта: список общий. */
    fun clear(user: AppUser) {
        dbGate.run { tx.execute { repo.clear(user.id) } }
    }

    companion object {
        /** Сколько держим: ряд под клавиатурой, дальше листать никто не станет. */
        const val LIMIT = 20
    }
}
