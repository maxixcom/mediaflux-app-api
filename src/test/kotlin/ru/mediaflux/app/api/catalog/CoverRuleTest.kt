package ru.mediaflux.app.api.catalog

import org.junit.jupiter.api.Test
import ru.mediaflux.app.api.repository.CoverCandidateRow
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Обложка канала и плейлиста (MF-115) — первый элемент с обложкой, как в кабинете. Строки кандидатов приходят из
 * запроса уже в порядке показа внутри владельца и только с обложкой; здесь — выбор первого на владельца.
 */
class CoverRuleTest {

    private fun row(owner: Long, media: Long) = object : CoverCandidateRow {
        override val ownerId = owner
        override val mediaId = media
    }

    @Test
    fun `на владельца — первый в порядке показа`() {
        val rows = listOf(row(10, 3), row(10, 1), row(20, 7), row(20, 3))
        assertEquals(mapOf(10L to 3L, 20L to 7L), CatalogQueries.firstPerKey(rows))
    }

    @Test
    fun `один ролик в нескольких местах программы и у нескольких владельцев`() {
        val rows = listOf(row(10, 5), row(10, 5), row(20, 5))
        assertEquals(mapOf(10L to 5L, 20L to 5L), CatalogQueries.firstPerKey(rows))
    }

    @Test
    fun `нет кандидатов — обложки нет`() {
        assertTrue(CatalogQueries.firstPerKey(emptyList()).isEmpty())
    }
}
