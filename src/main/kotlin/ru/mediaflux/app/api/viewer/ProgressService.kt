package ru.mediaflux.app.api.viewer

import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import ru.mediaflux.app.api.catalog.CatalogService
import ru.mediaflux.app.api.config.AppApiProperties
import ru.mediaflux.app.api.repository.AppWatchProgressRepository
import ru.mediaflux.app.api.support.db.DbGate
import ru.mediaflux.app.api.support.error.AppException
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Позиция, как её видит клиент: база с наложенным буфером своей копии. */
data class Progress(
    val positionMs: Long,
    val durationMs: Long?,
    val watched: Boolean,
    val updatedAt: LocalDateTime,
)

/**
 * Позиция просмотра и «просмотрено» (MF-89, APPS.md §7).
 *
 * - **Отчёт** проверяет медиа (своё и видимое — через кэш карточек каталога), урезает время клиента до серверного
 *   «сейчас», считает «просмотрено» по СЕРВЕРНОЙ длительности (≥ 95 %) и кладёт в буфер; пауза и выход — сразу.
 * - **Чтение** — строки базы плюс более свежие отчёты буфера своей копии: свой телевизор видит своё сразу, соседняя
 *   копия — после сброса буфера.
 */
@Service
class ProgressService(
    private val buffer: ProgressBuffer,
    private val repo: AppWatchProgressRepository,
    private val catalog: CatalogService,
    private val tx: TransactionTemplate,
    private val dbGate: DbGate,
    private val props: AppApiProperties,
) {
    private val minPositionMs get() = props.viewer.continueMinPosition.toMillis()

    fun report(userId: Long, deviceId: Long?, mediaId: Long, positionMs: Long, clientDurationMs: Long?, reportedAt: OffsetDateTime, final: Boolean) {
        val card = catalog.media(userId, listOf(mediaId)).firstOrNull() ?: throw AppException.notFound("Медиа $mediaId")
        val duration = card.durationMs ?: clientDurationMs?.takeIf { it > 0 }
        val position = positionMs.coerceAtLeast(0).let { p -> duration?.let { p.coerceAtMost(it) } ?: p }
        val now = nowUtc()
        // Время клиента: по нему порядок между устройствами. Будущее (сбитые часы) — до серверного «сейчас».
        val at = reportedAt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime().coerceAtMost(now)
        buffer.offer(ProgressReport(userId, mediaId, position, duration, isWatched(position, duration), at, deviceId))
        if (final) buffer.flushNow(userId, mediaId)
    }

    /** Ручная отметка — сразу в базу и побеждает любые отчёты буфера. Снятие сбрасывает позицию. */
    fun markWatched(userId: Long, mediaId: Long, watched: Boolean): Progress {
        val card = catalog.media(userId, listOf(mediaId)).firstOrNull() ?: throw AppException.notFound("Медиа $mediaId")
        buffer.drop(userId, mediaId)
        val now = nowUtc()
        dbGate.run { tx.execute { repo.markWatched(userId, mediaId, card.durationMs, watched, if (watched) now else null, now) } }
        return progress(userId, listOf(mediaId))[mediaId] ?: Progress(0, card.durationMs, watched, now)
    }

    /** Позиции пачкой (одним запросом) с наложением буфера. */
    fun progress(userId: Long, mediaIds: Collection<Long>): Map<Long, Progress> {
        if (mediaIds.isEmpty()) return emptyMap()
        val rows = dbGate.run { repo.findByUserIdAndMediaIdIn(userId, mediaIds) }
            .associate { it.mediaId to (Progress(it.positionMs, it.durationMs, it.watched, it.updatedAt) to it.reportedAt) }
        val pending = buffer.pendingFor(userId)
        return mediaIds.mapNotNull { id ->
            val db = rows[id]
            val buf = pending[id]?.takeIf { db == null || it.reportedAt > db.second }
            val merged = when {
                buf != null -> Progress(buf.positionMs, buf.durationMs ?: db?.first?.durationMs,
                    // «Просмотрено» залипает, как в базе: снимается только вручную.
                    buf.watched || db?.first?.watched == true, buf.reportedAt)
                db != null -> db.first
                else -> null
            }
            merged?.let { id to it }
        }.toMap()
    }

    /**
     * «Продолжить просмотр»: свежие отчёты буфера сверху, дальше база (новые сверху), без просмотренного и без
     * позиций короче порога. Видимость базы проверяет запрос; отчёты буфера проверил приём отчёта, а скрытое после
     * этого выпадет при сборке карточек.
     */
    fun continueIds(userId: Long): List<Long> {
        val pending = buffer.pendingFor(userId).values.sortedByDescending { it.reportedAt }
        val fromDb = dbGate.run { repo.continueIds(userId, minPositionMs, PageRequest.of(0, CONTINUE_CAP)) }
        val pendingIds = pending.map { it.mediaId }.toSet()
        val candidates = pending.filter { !it.watched && it.positionMs >= minPositionMs }.map { it.mediaId } +
            fromDb.filter { it !in pendingIds }
        // Просмотренное в базе (залипшая отметка) из буфера не возвращаем: сверяем пачкой.
        val state = progress(userId, candidates)
        return candidates.filter { id -> state[id]?.let { !it.watched && it.positionMs >= minPositionMs } ?: false }
    }

    /** С какой позиции продолжить; null — смотреть с начала (не начинал, просмотрено или почти в самом начале). */
    fun resumePosition(userId: Long, mediaId: Long): Long? =
        progress(userId, listOf(mediaId))[mediaId]?.takeIf { !it.watched && it.positionMs >= minPositionMs }?.positionMs

    fun isWatched(positionMs: Long, durationMs: Long?): Boolean =
        durationMs != null && durationMs > 0 && positionMs >= durationMs * props.viewer.watchedThreshold

    private fun nowUtc(): LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)

    private companion object {
        const val CONTINUE_CAP = 200
    }
}
