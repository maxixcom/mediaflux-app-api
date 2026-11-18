package ru.mediaflux.app.api.viewer

import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.BatchMapping
import org.springframework.graphql.data.method.annotation.ContextValue
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.stereotype.Controller
import ru.mediaflux.app.api.catalog.CatalogAssembler
import ru.mediaflux.app.api.catalog.ChannelView
import ru.mediaflux.app.api.catalog.Connection
import ru.mediaflux.app.api.catalog.HomeView
import ru.mediaflux.app.api.catalog.MediaView
import ru.mediaflux.app.api.catalog.PageInput
import ru.mediaflux.app.api.catalog.PlaylistView
import ru.mediaflux.app.api.catalog.page
import ru.mediaflux.app.api.catalog.utc
import ru.mediaflux.app.api.device.DeviceState
import ru.mediaflux.app.api.entity.FavoriteTargetType
import ru.mediaflux.app.api.guard.OperationGuard
import ru.mediaflux.app.api.security.CurrentPrincipal
import ru.mediaflux.app.api.support.error.AppErrorType
import ru.mediaflux.app.api.support.error.AppException
import ru.mediaflux.app.api.support.error.ErrorCodes
import java.time.OffsetDateTime
import java.util.Optional

data class ProgressReportInput(
    val mediaId: Long,
    val positionMs: Long,
    val durationMs: Long? = null,
    val reportedAt: OffsetDateTime,
    val final: Boolean = false,
)

data class FavoriteTargetInput(val mediaId: Long? = null, val playlistId: Long? = null, val channelId: Long? = null)

data class MediaProgressView(val positionMs: Long, val durationMs: Long?, val watched: Boolean, val updatedAt: OffsetDateTime)

/**
 * Состояние зрителя (viewer.graphqls, MF-89): позиция, «просмотрено», «Продолжить просмотр», избранное. Поля
 * `progress` и `favorite` у карточек — `@BatchMapping`: одна выборка на весь список, без N+1.
 */
@Controller
class ViewerController(
    private val progress: ProgressService,
    private val favorites: FavoriteService,
    private val views: CatalogAssembler,
) {

    @MutationMapping
    fun progressReport(
        @Argument input: ProgressReportInput,
        @ContextValue(name = OperationGuard.DEVICE_KEY) device: Optional<DeviceState>,
    ): Boolean {
        val user = CurrentPrincipal.get().user
        progress.report(user.id, device.orElse(null)?.id, input.mediaId, input.positionMs, input.durationMs, input.reportedAt, input.final)
        return true
    }

    @MutationMapping
    fun markWatched(@Argument mediaId: Long, @Argument watched: Boolean?): MediaProgressView =
        progress.markWatched(CurrentPrincipal.get().user.id, mediaId, watched ?: true).view()

    @QueryMapping
    fun continueWatching(@Argument page: PageInput?): Connection<MediaView> {
        val uid = CurrentPrincipal.get().user.id
        val ids = progress.continueIds(uid)
        return Connection(views.mediaViews(uid, ids.page(page)), ids.size)
    }

    @QueryMapping
    fun favorites(@Argument page: PageInput?): Connection<Any> {
        val items = favorites.items(CurrentPrincipal.get().user)
        return Connection(items.page(page), items.size)
    }

    @MutationMapping
    fun favoriteAdd(@Argument target: FavoriteTargetInput): Boolean {
        favorites.add(CurrentPrincipal.get().user, target.toTarget())
        return true
    }

    @MutationMapping
    fun favoriteRemove(@Argument target: FavoriteTargetInput): Boolean {
        favorites.remove(CurrentPrincipal.get().user, target.toTarget())
        return true
    }

    // ── Ряды главного экрана ─────────────────────────────────────────────────────────────────────

    @SchemaMapping(typeName = "Home", field = "continueWatching")
    fun homeContinue(home: HomeView): List<MediaView> =
        views.mediaViews(home.userId, progress.continueIds(home.userId).take(HOME_ROW))

    @SchemaMapping(typeName = "Home", field = "favorites")
    fun homeFavorites(home: HomeView): List<Any> =
        favorites.items(CurrentPrincipal.get().user).take(HOME_ROW)

    // ── Поля карточек — пачкой ───────────────────────────────────────────────────────────────────

    @BatchMapping(typeName = "Media", field = "progress")
    fun mediaProgress(media: List<MediaView>): Map<MediaView, MediaProgressView?> {
        val state = progress.progress(CurrentPrincipal.get().user.id, media.map { it.id.toLong() })
        return media.associateWith { state[it.id.toLong()]?.view() }
    }

    @BatchMapping(typeName = "Media", field = "favorite")
    fun mediaFavorite(media: List<MediaView>): Map<MediaView, Boolean> =
        flags(FavoriteTargetType.MEDIA, media) { it.id }

    @BatchMapping(typeName = "Playlist", field = "favorite")
    fun playlistFavorite(playlists: List<PlaylistView>): Map<PlaylistView, Boolean> =
        flags(FavoriteTargetType.PLAYLIST, playlists) { it.id }

    @BatchMapping(typeName = "Channel", field = "favorite")
    fun channelFavorite(channels: List<ChannelView>): Map<ChannelView, Boolean> =
        flags(FavoriteTargetType.CHANNEL, channels) { it.id }

    private fun <T> flags(type: FavoriteTargetType, items: List<T>, id: (T) -> String): Map<T, Boolean> {
        val fav = favorites.favoriteIds(CurrentPrincipal.get().user.id, type, items.map { id(it).toLong() })
        return items.associateWith { id(it).toLong() in fav }
    }

    private fun Progress.view() = MediaProgressView(positionMs, durationMs, watched, updatedAt.utc())

    private fun FavoriteTargetInput.toTarget(): FavoriteTarget {
        val set = listOfNotNull(
            mediaId?.let { FavoriteTarget(FavoriteTargetType.MEDIA, it) },
            playlistId?.let { FavoriteTarget(FavoriteTargetType.PLAYLIST, it) },
            channelId?.let { FavoriteTarget(FavoriteTargetType.CHANNEL, it) },
        )
        return set.singleOrNull()
            ?: throw AppException(AppErrorType.BAD_INPUT, ErrorCodes.BAD_INPUT, "Нужно ровно одно из mediaId, playlistId, channelId")
    }

    private companion object {
        const val HOME_ROW = 20
    }
}
