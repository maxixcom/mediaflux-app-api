package ru.mediaflux.app.api.catalog

import org.springframework.stereotype.Component
import ru.mediaflux.app.api.user.AppUser

/**
 * Сборка представлений каталога из кэша — одна на каталог, «Продолжить просмотр» и избранное. Всё пачками: один
 * запрос (или попадание в кэш) на набор id, без N+1. Невидимое (чужое, скрытое, не готовое) выпадает само.
 */
@Component
class CatalogAssembler(private val catalog: CatalogService) {

    fun mediaViews(uid: Long, ids: List<Long>): List<MediaView> = catalog.media(uid, ids).map { mediaView(it) }

    fun mediaView(card: MediaCard) = MediaView(
        id = card.id.toString(),
        code = card.code,
        title = card.title,
        description = card.description,
        durationMs = card.durationMs,
        createdAt = card.createdAt.utc(),
        libraryId = card.libraryId.toString(),
        coverUrl = catalog.coverUrl(card),
        previewManifestUrl = catalog.previewManifestUrl(card),
    )

    /** Библиотеки с числом медиа и обложкой самого свежего — одна пачка карточек на все обложки. */
    fun libraryViews(uid: Long, ids: List<Long>): List<LibraryView> {
        val cards = catalog.libraries(uid, ids)
        val items = cards.associate { it.id to catalog.libraryItemIds(uid, it.id) }
        val covers = catalog.media(uid, items.values.mapNotNull { it.firstOrNull() }).associateBy { it.id }
        return cards.map { lib ->
            val libItems = items[lib.id].orEmpty()
            LibraryView(lib.id.toString(), lib.title, lib.description, libItems.size, catalog.coverUrl(libItems.firstOrNull()?.let { covers[it] }))
        }
    }

    /** Плейлисты, в которых есть что смотреть, с числом элементов и обложкой первого. */
    fun playlistViews(uid: Long, ids: List<Long>): List<PlaylistView> {
        val items = ids.associateWith { catalog.playlistItemIds(uid, it) }.filterValues { it.isNotEmpty() }
        val cards = catalog.playlists(uid, ids.filter { it in items })
        val covers = catalog.media(uid, items.values.mapNotNull { it.firstOrNull() }).associateBy { it.id }
        return cards.map { pl ->
            val plItems = items[pl.id].orEmpty()
            PlaylistView(pl.id.toString(), pl.code, pl.title, pl.description, plItems.size, catalog.coverUrl(plItems.firstOrNull()?.let { covers[it] }))
        }
    }

    /** Каналы владельца, если они открыты тарифом; [ids] — только эти, в их порядке. */
    fun channelViews(user: AppUser, ids: List<Long>? = null): List<ChannelView> {
        val all = catalog.channels(user.id, user.channelsEnabled)
        if (ids == null) return all.map { it.view() }
        val byId = all.associateBy { it.id }
        return ids.mapNotNull { byId[it]?.view() }
    }
}
