package ru.mediaflux.app.api.viewer

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import ru.mediaflux.app.api.catalog.CatalogAssembler
import ru.mediaflux.app.api.catalog.CatalogService
import ru.mediaflux.app.api.catalog.MediaCard
import ru.mediaflux.app.api.catalog.MediaView
import ru.mediaflux.app.api.config.AppApiProperties
import ru.mediaflux.app.api.entity.AppSearchRecentEntity
import ru.mediaflux.app.api.entity.CatalogTargetType
import ru.mediaflux.app.api.repository.AppSearchRecentRepository
import ru.mediaflux.app.api.support.db.DbGate
import ru.mediaflux.app.api.support.error.AppException
import ru.mediaflux.app.api.user.AppUser
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Недавние поиски (MF-114): записать только своё и видимое, обрезать до 20, показать видимое в порядке выбора. */
class SearchRecentServiceTest {

    private val repo = mock<AppSearchRecentRepository>()
    private val catalog = mock<CatalogService>()
    private val views = mock<CatalogAssembler>()
    private val tx = TransactionTemplate(mock<PlatformTransactionManager>().also {
        whenever(it.getTransaction(any())).thenReturn(SimpleTransactionStatus())
    })
    private val service = SearchRecentService(repo, catalog, views, tx, DbGate(AppApiProperties(), SimpleMeterRegistry()))
    private val user = AppUser(1, UUID.randomUUID(), "Имя", null, true, channelsEnabled = true)

    private fun card(id: Long) = MediaCard(id, "c$id", "М$id", null, null, LocalDateTime.now(), 1, false, null, null)
    private fun view(id: Long) = MediaView(id.toString(), "c$id", "М$id", null, null, OffsetDateTime.now(), "1", null, null)
    private fun row(type: CatalogTargetType, id: Long) =
        AppSearchRecentEntity(userId = 1, targetType = type.name, targetId = id, selectedAt = LocalDateTime.now())

    @Test
    fun `выбор видимого — подъём наверх и обрезка до 20`() {
        whenever(catalog.media(eq(1L), eq(listOf(5L)))).thenReturn(listOf(card(5)))
        service.add(user, 5)
        inOrder(repo) {
            verify(repo).touch(eq(1L), eq("MEDIA"), eq(5L), any())
            verify(repo).trim(1L, SearchRecentService.LIMIT)
        }
    }

    @Test
    fun `скрытое и чужое не записывается`() {
        whenever(catalog.media(eq(1L), eq(listOf(9L)))).thenReturn(emptyList())
        val e = assertFailsWith<AppException> { service.add(user, 9) }
        assertEquals("NOT_FOUND", e.code)
        verify(repo, never()).touch(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `в порядке выбора, только медиа, скрытое отсеивает каталог`() {
        whenever(repo.findByUserIdOrderBySelectedAtDescIdDesc(1)).thenReturn(listOf(
            row(CatalogTargetType.MEDIA, 3), row(CatalogTargetType.PLAYLIST, 8), row(CatalogTargetType.MEDIA, 4),
        ))
        whenever(views.mediaViews(eq(1L), eq(listOf(3L, 4L)))).thenReturn(listOf(view(3))) // 4 скрыто
        assertEquals(listOf("М3"), service.items(user).map { it.title })
    }
}
