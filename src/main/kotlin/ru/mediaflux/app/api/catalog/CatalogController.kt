package ru.mediaflux.app.api.catalog

import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.stereotype.Controller
import ru.mediaflux.app.api.security.CurrentPrincipal

/**
 * Каталог (catalog.graphqls). До сюда запрос доходит только с зарегистрированным устройством — проверка в
 * OperationGuard. Всё читается через кэш [CatalogService], представления собирает [CatalogAssembler].
 */
@Controller
class CatalogController(
    private val catalog: CatalogService,
    private val views: CatalogAssembler,
) {

    @QueryMapping
    fun home(): HomeView = HomeView(CurrentPrincipal.get().user.id)

    @SchemaMapping(typeName = "Home", field = "recent")
    fun homeRecent(home: HomeView): List<MediaView> =
        views.mediaViews(home.userId, catalog.recentIds(home.userId).take(HOME_ROW))

    /** Полка на библиотеку: первые библиотеки и первые медиа в каждой — одна пачка карточек на все полки. */
    @SchemaMapping(typeName = "Home", field = "shelves")
    fun homeShelves(home: HomeView): List<ShelfView> {
        val uid = home.userId
        val libraries = views.libraryViews(uid, catalog.libraryIds(uid).take(HOME_SHELVES))
        val itemIds = libraries.associate { it.libraryId to catalog.libraryItemIds(uid, it.libraryId).take(HOME_ROW) }
        val cards = catalog.media(uid, itemIds.values.flatten()).associateBy { it.id }
        return libraries.map { lib ->
            ShelfView(lib, itemIds[lib.libraryId].orEmpty().mapNotNull { cards[it] }.map { views.mediaView(it) })
        }
    }

    @SchemaMapping(typeName = "Home", field = "channels")
    fun homeChannels(home: HomeView): List<ChannelView> = channels()

    @QueryMapping
    fun libraries(@Argument page: PageInput?): Connection<LibraryView> {
        val uid = CurrentPrincipal.get().user.id
        val ids = catalog.libraryIds(uid)
        return Connection(views.libraryViews(uid, ids.page(page)), ids.size)
    }

    @QueryMapping
    fun library(@Argument id: Long): LibraryView? {
        val uid = CurrentPrincipal.get().user.id
        // Библиотека без единого видимого медиа на экране не нужна — как и в списке.
        if (catalog.libraryItemIds(uid, id).isEmpty()) return null
        return views.libraryViews(uid, listOf(id)).firstOrNull()
    }

    @SchemaMapping(typeName = "Library", field = "items")
    fun libraryItems(library: LibraryView, @Argument page: PageInput?): Connection<MediaView> {
        val uid = CurrentPrincipal.get().user.id
        val ids = catalog.libraryItemIds(uid, library.libraryId)
        return Connection(views.mediaViews(uid, ids.page(page)), ids.size)
    }

    @QueryMapping
    fun media(@Argument id: Long): MediaView? {
        val uid = CurrentPrincipal.get().user.id
        return catalog.media(uid, listOf(id)).firstOrNull()?.let { views.mediaView(it) }
    }

    @QueryMapping
    fun playlists(@Argument page: PageInput?): Connection<PlaylistView> {
        val uid = CurrentPrincipal.get().user.id
        val ids = catalog.playlistIds(uid)
        return Connection(views.playlistViews(uid, ids.page(page)), ids.size)
    }

    @QueryMapping
    fun playlist(@Argument id: Long): PlaylistView? =
        views.playlistViews(CurrentPrincipal.get().user.id, listOf(id)).firstOrNull()

    @SchemaMapping(typeName = "Playlist", field = "items")
    fun playlistItems(playlist: PlaylistView): List<MediaView> {
        val uid = CurrentPrincipal.get().user.id
        return views.mediaViews(uid, catalog.playlistItemIds(uid, playlist.playlistId))
    }

    @QueryMapping
    fun channels(): List<ChannelView> = views.channelViews(CurrentPrincipal.get().user)

    @QueryMapping
    fun search(@Argument text: String, @Argument page: PageInput?): Connection<MediaView> {
        val uid = CurrentPrincipal.get().user.id
        val ids = catalog.searchIds(uid, text)
        return Connection(views.mediaViews(uid, ids.page(page)), ids.size)
    }

    private companion object {
        const val HOME_ROW = 20
        const val HOME_SHELVES = 20
    }
}
