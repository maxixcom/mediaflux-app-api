package ru.mediaflux.app.api.catalog

import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.stereotype.Controller
import ru.mediaflux.app.api.security.CurrentPrincipal

/**
 * Каталог (catalog.graphqls). До сюда запрос доходит только с зарегистрированным устройством — проверка в
 * OperationGuard. Всё читается через кэш [CatalogService].
 */
@Controller
class CatalogController(private val catalog: CatalogService) {

    @QueryMapping
    fun home(): HomeView = HomeView(CurrentPrincipal.get().user.id)

    @SchemaMapping(typeName = "Home", field = "recent")
    fun homeRecent(home: HomeView): List<MediaView> =
        mediaViews(home.userId, catalog.recentIds(home.userId).take(HOME_ROW))

    /** Полка на библиотеку: первые библиотеки и первые медиа в каждой. */
    @SchemaMapping(typeName = "Home", field = "shelves")
    fun homeShelves(home: HomeView): List<ShelfView> {
        val uid = home.userId
        val libraries = libraryViews(uid, catalog.libraryIds(uid).take(HOME_SHELVES))
        val itemIds = libraries.associate { it.libraryId to catalog.libraryItemIds(uid, it.libraryId).take(HOME_ROW) }
        val cards = catalog.media(uid, itemIds.values.flatten()).associateBy { it.id }
        return libraries.map { lib ->
            ShelfView(lib, itemIds[lib.libraryId].orEmpty().mapNotNull { cards[it] }.map { mediaView(it) })
        }
    }

    @SchemaMapping(typeName = "Home", field = "channels")
    fun homeChannels(home: HomeView): List<ChannelView> = channels()

    @QueryMapping
    fun libraries(@Argument page: PageInput?): Connection<LibraryView> {
        val uid = CurrentPrincipal.get().user.id
        val ids = catalog.libraryIds(uid)
        return Connection(libraryViews(uid, ids.page(page)), ids.size)
    }

    @QueryMapping
    fun library(@Argument id: Long): LibraryView? {
        val uid = CurrentPrincipal.get().user.id
        // Библиотека без единого видимого медиа на экране не нужна — как и в списке.
        if (catalog.libraryItemIds(uid, id).isEmpty()) return null
        return libraryViews(uid, listOf(id)).firstOrNull()
    }

    @SchemaMapping(typeName = "Library", field = "items")
    fun libraryItems(library: LibraryView, @Argument page: PageInput?): Connection<MediaView> {
        val uid = CurrentPrincipal.get().user.id
        val ids = catalog.libraryItemIds(uid, library.libraryId)
        return Connection(mediaViews(uid, ids.page(page)), ids.size)
    }

    @QueryMapping
    fun media(@Argument id: Long): MediaView? {
        val uid = CurrentPrincipal.get().user.id
        return catalog.media(uid, listOf(id)).firstOrNull()?.let { mediaView(it) }
    }

    @QueryMapping
    fun playlists(@Argument page: PageInput?): Connection<PlaylistView> {
        val uid = CurrentPrincipal.get().user.id
        val ids = catalog.playlistIds(uid)
        return Connection(playlistViews(uid, ids.page(page)), ids.size)
    }

    @QueryMapping
    fun playlist(@Argument id: Long): PlaylistView? {
        val uid = CurrentPrincipal.get().user.id
        if (catalog.playlistItemIds(uid, id).isEmpty()) return null
        return playlistViews(uid, listOf(id)).firstOrNull()
    }

    @SchemaMapping(typeName = "Playlist", field = "items")
    fun playlistItems(playlist: PlaylistView): List<MediaView> {
        val uid = CurrentPrincipal.get().user.id
        return mediaViews(uid, catalog.playlistItemIds(uid, playlist.playlistId))
    }

    @QueryMapping
    fun channels(): List<ChannelView> {
        val user = CurrentPrincipal.get().user
        return catalog.channels(user.id, user.channelsEnabled).map { it.view() }
    }

    @QueryMapping
    fun search(@Argument text: String, @Argument page: PageInput?): Connection<MediaView> {
        val uid = CurrentPrincipal.get().user.id
        val ids = catalog.searchIds(uid, text)
        return Connection(mediaViews(uid, ids.page(page)), ids.size)
    }

    private fun mediaViews(uid: Long, ids: List<Long>): List<MediaView> = catalog.media(uid, ids).map { mediaView(it) }

    private fun mediaView(card: MediaCard) = MediaView(
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
    private fun libraryViews(uid: Long, ids: List<Long>): List<LibraryView> {
        val cards = catalog.libraries(uid, ids)
        val items = cards.associate { it.id to catalog.libraryItemIds(uid, it.id) }
        val covers = catalog.media(uid, items.values.mapNotNull { it.firstOrNull() }).associateBy { it.id }
        return cards.map { lib ->
            val libItems = items[lib.id].orEmpty()
            LibraryView(lib.id.toString(), lib.title, lib.description, libItems.size, catalog.coverUrl(libItems.firstOrNull()?.let { covers[it] }))
        }
    }

    private fun playlistViews(uid: Long, ids: List<Long>): List<PlaylistView> {
        val cards = catalog.playlists(uid, ids)
        val items = cards.associate { it.id to catalog.playlistItemIds(uid, it.id) }
        val covers = catalog.media(uid, items.values.mapNotNull { it.firstOrNull() }).associateBy { it.id }
        return cards.map { pl ->
            val plItems = items[pl.id].orEmpty()
            PlaylistView(pl.id.toString(), pl.code, pl.title, pl.description, plItems.size, catalog.coverUrl(plItems.firstOrNull()?.let { covers[it] }))
        }
    }

    private companion object {
        const val HOME_ROW = 20
        const val HOME_SHELVES = 20
    }
}
