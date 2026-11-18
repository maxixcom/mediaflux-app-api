package ru.mediaflux.app.api.catalog

import org.junit.jupiter.api.Test
import ru.mediaflux.app.api.repository.ProgramVersionRow
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Выбор текущей версии программы канала — копия правила кабинета (`ChannelProgramVersionServiceImpl.current`). */
class ChannelVersionRuleTest {

    private val now = LocalDateTime.of(2026, 11, 18, 12, 0)

    private fun v(id: Long, no: Int, from: LocalDateTime?) = object : ProgramVersionRow {
        override val id = id
        override val channelId = 1L
        override val versionNo = no
        override val effectiveFrom = from
    }

    @Test
    fun `берётся последняя вступившая в силу версия`() {
        val list = listOf(v(1, 1, now.minusDays(2)), v(2, 2, now.minusHours(1)), v(3, 3, now.plusHours(1)))
        assertEquals(2, CatalogQueries.currentVersion(list, now)?.id)
    }

    @Test
    fun `если ни одна не вступила — самая ранняя`() {
        val list = listOf(v(5, 2, now.plusDays(1)), v(4, 1, now.plusHours(2)))
        assertEquals(4, CatalogQueries.currentVersion(list, now)?.id)
    }

    @Test
    fun `при одинаковой дате — старший номер, версия без даты не вступила`() {
        val list = listOf(v(7, 1, now.minusHours(3)), v(8, 2, now.minusHours(3)), v(9, 3, null))
        assertEquals(8, CatalogQueries.currentVersion(list, now)?.id)
        assertNull(CatalogQueries.currentVersion(emptyList(), now))
    }
}
