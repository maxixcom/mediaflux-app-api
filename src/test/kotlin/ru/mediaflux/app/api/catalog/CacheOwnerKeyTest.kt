package ru.mediaflux.app.api.catalog

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import ru.mediaflux.app.api.config.AppApiProperties
import ru.mediaflux.app.api.support.cache.Caches
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ключ кэша карточек всегда содержит владельца (APPS.md §9): карточка, уже лежащая в кэше у A, не достаётся B —
 * для B идёт свой запрос с его id, и база (правило «только своё») ничего не отдаёт.
 */
class CacheOwnerKeyTest {

    private val queries = mock<CatalogQueries>()
    private val catalog = CatalogService(queries, AppApiProperties(), Caches(SimpleMeterRegistry()))

    private val card = MediaCard(42, "code42", "Фильм", null, 1000, LocalDateTime.now(), 1, false, null, null)

    @Test
    fun `карточка владельца не достаётся другому пользователю`() {
        whenever(queries.mediaCards(eq(1L), any())).thenReturn(mapOf(42L to card))
        whenever(queries.mediaCards(eq(2L), any())).thenReturn(emptyMap())

        assertEquals(listOf(card), catalog.media(1, listOf(42)))
        assertTrue(catalog.media(2, listOf(42)).isEmpty())
        // Повтор у A — из кэша, у B — тоже кэш «не найдено», в базу никто не идёт второй раз.
        assertEquals(listOf(card), catalog.media(1, listOf(42)))
        assertTrue(catalog.media(2, listOf(42)).isEmpty())
        verify(queries, times(1)).mediaCards(eq(1L), any())
        verify(queries, times(1)).mediaCards(eq(2L), any())
    }

    @Test
    fun `промахи одной пачки уходят в базу одним запросом и в порядке запроса`() {
        val c1 = card.copy(id = 1, code = "c1")
        val c2 = card.copy(id = 2, code = "c2")
        whenever(queries.mediaCards(eq(5L), any())).thenReturn(mapOf(1L to c1, 2L to c2))
        assertEquals(listOf(c2, c1), catalog.media(5, listOf(2, 3, 1)))
        verify(queries, times(1)).mediaCards(eq(5L), any())
    }

    @Test
    fun `шаблон поиска экранирует спецсимволы ILIKE`() {
        assertEquals("%50\\% скидка\\_1%", CatalogService.likePattern("  50% Скидка_1 "))
    }
}
