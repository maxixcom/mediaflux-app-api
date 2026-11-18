package ru.mediaflux.app.api.viewer

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import ru.mediaflux.app.api.catalog.CatalogService
import ru.mediaflux.app.api.catalog.MediaCard
import ru.mediaflux.app.api.config.AppApiProperties
import ru.mediaflux.app.api.entity.AppWatchProgressEntity
import ru.mediaflux.app.api.repository.AppWatchProgressRepository
import ru.mediaflux.app.api.support.db.DbGate
import ru.mediaflux.app.api.support.error.AppException
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressTest {

    private val props = AppApiProperties()
    private val meters = SimpleMeterRegistry()
    private val repo = mock<AppWatchProgressRepository>()
    private val tx = TransactionTemplate(mock<PlatformTransactionManager>().also {
        whenever(it.getTransaction(any())).thenReturn(SimpleTransactionStatus())
    })
    private val dbGate = DbGate(props, meters)
    private val buffer = ProgressBuffer(repo, tx, dbGate, meters)
    private val catalog = mock<CatalogService>()
    private val service = ProgressService(buffer, repo, catalog, tx, dbGate, props)

    private val hour = 3_600_000L
    private fun card(id: Long, duration: Long? = hour) =
        MediaCard(id, "c$id", "Фильм $id", null, duration, LocalDateTime.now(), 1, false, null, null)

    private fun at(minutesAgo: Long) = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(minutesAgo)

    init {
        whenever(catalog.media(eq(1L), any())).thenAnswer { inv -> (inv.arguments[1] as List<*>).map { card(it as Long) } }
        whenever(repo.findByUserIdAndMediaIdIn(any(), any())).thenReturn(emptyList())
        whenever(repo.upsert(any(), any(), any(), anyOrNull(), any(), anyOrNull(), any(), anyOrNull(), any())).thenReturn(1)
    }

    @Test
    fun `в буфере остаётся поздний отчёт, даже если он пришёл раньше`() {
        service.report(1, null, 10, 600_000, null, at(1), final = false)
        service.report(1, null, 10, 300_000, null, at(5), final = false) // досланный старый
        assertEquals(600_000, service.progress(1, listOf(10L))[10]?.positionMs)
        verify(repo, never()).upsert(any(), any(), any(), anyOrNull(), any(), anyOrNull(), any(), anyOrNull(), any())
    }

    @Test
    fun `пауза пишется сразу, остальное — сбросом пачкой`() {
        service.report(1, null, 10, 60_000, null, at(1), final = false)
        service.report(1, null, 11, 70_000, null, at(1), final = true)
        verify(repo, times(1)).upsert(eq(1L), eq(11L), eq(70_000L), anyOrNull(), any(), anyOrNull(), any(), anyOrNull(), any())
        buffer.flush()
        verify(repo, times(1)).upsert(eq(1L), eq(10L), eq(60_000L), anyOrNull(), any(), anyOrNull(), any(), anyOrNull(), any())
        assertTrue(buffer.pendingFor(1).isEmpty(), "записанное снято из буфера")
    }

    @Test
    fun `сбой базы — отчёты остаются в буфере`() {
        whenever(repo.upsert(any(), any(), any(), anyOrNull(), any(), anyOrNull(), any(), anyOrNull(), any())).thenThrow(IllegalStateException("db"))
        service.report(1, null, 10, 60_000, null, at(1), final = false)
        buffer.flush()
        assertEquals(60_000, buffer.pendingFor(1)[10]?.positionMs)
    }

    @Test
    fun `остановка пода сбрасывает буфер`() {
        service.report(1, null, 10, 60_000, null, at(1), final = false)
        buffer.stop()
        verify(repo).upsert(eq(1L), eq(10L), eq(60_000L), anyOrNull(), any(), anyOrNull(), any(), anyOrNull(), any())
    }

    @Test
    fun `просмотрено — с 95 процентов серверной длительности`() {
        assertFalse(service.isWatched((hour * 0.94).toLong(), hour))
        assertTrue(service.isWatched((hour * 0.95).toLong(), hour))
        assertFalse(service.isWatched(10_000_000, null), "без длительности не решаем")
        // Длительность отчёта не перебивает серверную: клиент прислал 1 мин, сервер знает час.
        service.report(1, null, 10, 600_000, 60_000, at(1), final = false)
        assertFalse(service.progress(1, listOf(10L))[10]!!.watched)
    }

    @Test
    fun `время из будущего урезается до серверного сейчас`() {
        service.report(1, null, 10, 60_000, null, OffsetDateTime.now(ZoneOffset.UTC).plusHours(3), final = true)
        val captor = argumentCaptor<LocalDateTime>()
        verify(repo).upsert(any(), any(), any(), anyOrNull(), any(), anyOrNull(), captor.capture(), anyOrNull(), any())
        assertTrue(captor.firstValue <= LocalDateTime.now(ZoneOffset.UTC))
    }

    @Test
    fun `чужое или скрытое медиа — NOT_FOUND`() {
        whenever(catalog.media(eq(2L), any())).thenReturn(emptyList())
        val ex = runCatching { service.report(2, null, 10, 60_000, null, at(1), final = false) }.exceptionOrNull()
        assertTrue(ex is AppException && ex.code == "NOT_FOUND")
    }

    @Test
    fun `буфер накладывается на базу, просмотренное в базе залипает`() {
        val db = AppWatchProgressEntity(userId = 1, mediaId = 10, positionMs = 3_500_000, durationMs = hour, watched = true,
            reportedAt = LocalDateTime.now(ZoneOffset.UTC).minusHours(1), updatedAt = LocalDateTime.now(ZoneOffset.UTC).minusHours(1))
        whenever(repo.findByUserIdAndMediaIdIn(eq(1L), any())).thenReturn(listOf(db))
        service.report(1, null, 10, 120_000, null, at(1), final = false) // пересматривает с начала
        val p = service.progress(1, listOf(10L))[10]!!
        assertEquals(120_000, p.positionMs)
        assertTrue(p.watched, "отметку снимает только markWatched(false)")
        assertNull(service.resumePosition(1, 10), "просмотренное не продолжают")
    }

    @Test
    fun `продолжить — свежий буфер сверху, без коротких и просмотренных`() {
        whenever(repo.continueIds(eq(1L), any(), any())).thenReturn(listOf(20L, 21L))
        whenever(repo.findByUserIdAndMediaIdIn(eq(1L), any())).thenReturn(listOf(
            AppWatchProgressEntity(userId = 1, mediaId = 20, positionMs = 600_000, durationMs = hour, reportedAt = LocalDateTime.now(ZoneOffset.UTC).minusDays(1)),
            AppWatchProgressEntity(userId = 1, mediaId = 21, positionMs = 900_000, durationMs = hour, reportedAt = LocalDateTime.now(ZoneOffset.UTC).minusDays(2)),
        ))
        service.report(1, null, 30, 400_000, null, at(1), final = false) // новое — сверху
        service.report(1, null, 31, 10_000, null, at(1), final = false)  // меньше 30 с — нет
        service.report(1, null, 21, 3_500_000, null, at(1), final = false) // досмотрел — уходит
        assertEquals(listOf(30L, 20L), service.continueIds(1))
        assertEquals(400_000, service.resumePosition(1, 30))
    }
}
