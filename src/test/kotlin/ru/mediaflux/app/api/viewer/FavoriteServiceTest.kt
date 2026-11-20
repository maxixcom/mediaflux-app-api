package ru.mediaflux.app.api.viewer

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import ru.mediaflux.app.api.catalog.CatalogAssembler
import ru.mediaflux.app.api.catalog.CatalogService
import ru.mediaflux.app.api.catalog.ChannelView
import ru.mediaflux.app.api.catalog.MediaView
import ru.mediaflux.app.api.catalog.PlaylistView
import ru.mediaflux.app.api.config.AppApiProperties
import ru.mediaflux.app.api.entity.AppFavoriteEntity
import ru.mediaflux.app.api.entity.CatalogTargetType
import ru.mediaflux.app.api.repository.AppFavoriteRepository
import ru.mediaflux.app.api.support.db.DbGate
import ru.mediaflux.app.api.support.error.AppException
import ru.mediaflux.app.api.user.AppUser
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FavoriteServiceTest {

    private val repo = mock<AppFavoriteRepository>()
    private val catalog = mock<CatalogService>()
    private val views = mock<CatalogAssembler>()
    private val tx = TransactionTemplate(mock<PlatformTransactionManager>().also {
        whenever(it.getTransaction(any())).thenReturn(SimpleTransactionStatus())
    })
    private val service = FavoriteService(repo, catalog, views, tx, DbGate(AppApiProperties(), SimpleMeterRegistry()))
    private val user = AppUser(1, UUID.randomUUID(), "Имя", null, true, channelsEnabled = true)

    private fun media(id: Long) = MediaView(id.toString(), "c$id", "М$id", null, null, OffsetDateTime.now(), "1", null, null)
    private fun playlist(id: Long) = PlaylistView(id.toString(), "p$id", "П$id", null, 1, null)
    private fun channel(id: Long) = ChannelView(id.toString(), "k$id", "К$id", null, false, null)
    private fun fav(type: CatalogTargetType, id: Long, minutesAgo: Long) =
        AppFavoriteEntity(userId = 1, targetType = type.name, targetId = id, createdAt = LocalDateTime.now().minusMinutes(minutesAgo))

    @Test
    fun `в порядке добавления, скрытое выпадает, по пачке на вид`() {
        whenever(repo.findByUserIdOrderByCreatedAtDescIdDesc(1)).thenReturn(listOf(
            fav(CatalogTargetType.CHANNEL, 7, 1), fav(CatalogTargetType.MEDIA, 3, 2),
            fav(CatalogTargetType.PLAYLIST, 5, 3), fav(CatalogTargetType.MEDIA, 4, 4),
        ))
        whenever(views.mediaViews(eq(1L), eq(listOf(3L, 4L)))).thenReturn(listOf(media(3))) // 4 скрыто
        whenever(views.playlistViews(eq(1L), eq(listOf(5L)))).thenReturn(listOf(playlist(5)))
        whenever(views.channelViews(eq(user), eq(listOf(7L)))).thenReturn(listOf(channel(7)))

        val items = service.items(user)
        assertEquals(listOf("К7", "М3", "П5"), items.map {
            when (it) { is MediaView -> it.title; is PlaylistView -> it.title; is ChannelView -> it.title; else -> "?" }
        })
    }

    @Test
    fun `добавить можно только своё и видимое, убрать — всегда`() {
        whenever(catalog.media(eq(1L), eq(listOf(9L)))).thenReturn(emptyList())
        val ex = runCatching { service.add(user, FavoriteTarget(CatalogTargetType.MEDIA, 9)) }.exceptionOrNull()
        assertTrue(ex is AppException && ex.code == "NOT_FOUND")
        verify(repo, never()).add(any(), any(), any(), any())

        service.remove(user, FavoriteTarget(CatalogTargetType.MEDIA, 9))
        verify(repo).remove(1, "MEDIA", 9)
    }
}
