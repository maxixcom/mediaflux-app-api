package ru.mediaflux.app.api.viewer

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import ru.mediaflux.app.api.repository.AppWatchProgressRepository
import ru.mediaflux.app.api.support.db.DbGate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

/** Отчёт о позиции, как он лежит в буфере. [reportedAt] — время клиента (урезанное до серверного «сейчас»). */
data class ProgressReport(
    val userId: Long,
    val mediaId: Long,
    val positionMs: Long,
    val durationMs: Long?,
    val watched: Boolean,
    val reportedAt: LocalDateTime,
    val deviceId: Long?,
)

/**
 * Буфер позиций (MF-89, APPS.md §7): позиция — самая частая запись в системе, поэтому её копим.
 *
 * - На пару «пользователь + медиа» держим последний отчёт (по времени клиента).
 * - Раз в `viewer.flush-interval` (30 с) сбрасываем всё пачкой одной транзакцией; пауза и выход из плеера пишутся
 *   сразу ([flushNow]); при штатной остановке пода — всё накопленное ([stop], после того как веб-сервер дождался
 *   запросов в работе).
 * - Запись — upsert с условием `reported_at < EXCLUDED.reported_at`: между копиями побеждает поздний отчёт.
 * - Запись снимается из буфера только после коммита и только если её не заменил более свежий отчёт: в окне сброса
 *   чтение по-прежнему видит буфер.
 * - Сбой базы — отчёты остаются в буфере до следующего сброса (WARN). При падении пода теряется не больше интервала.
 */
@Component
class ProgressBuffer(
    private val repo: AppWatchProgressRepository,
    private val tx: TransactionTemplate,
    private val dbGate: DbGate,
    meterRegistry: MeterRegistry,
) : SmartLifecycle {

    private val log = LoggerFactory.getLogger(javaClass)

    /** userId → (mediaId → последний отчёт). По пользователю — чтобы чтение не перебирало весь буфер. */
    private val pending = ConcurrentHashMap<Long, ConcurrentHashMap<Long, ProgressReport>>()

    private val flushed = Counter.builder("app_api.progress.flushed").description("Отчётов записано в базу").register(meterRegistry)
    private val stale = Counter.builder("app_api.progress.stale")
        .description("Отчётов отброшено базой как более старые, чем записанный (поздний отчёт другой копии или устройства)")
        .register(meterRegistry)

    @Volatile
    private var running = false

    init {
        Gauge.builder("app_api.progress.pending") { pending.values.sumOf { it.size }.toDouble() }
            .description("Отчётов позиции в буфере, ещё не записанных")
            .register(meterRegistry)
    }

    /** Положить отчёт; из двух отчётов по одному медиа остаётся более поздний. */
    fun offer(report: ProgressReport) {
        pending.computeIfAbsent(report.userId) { ConcurrentHashMap() }
            .merge(report.mediaId, report) { old, new -> if (new.reportedAt > old.reportedAt) new else old }
    }

    /** Отчёты пользователя, ещё не записанные в базу, — для наложения на чтение. */
    fun pendingFor(userId: Long): Map<Long, ProgressReport> = pending[userId]?.toMap() ?: emptyMap()

    /** Записать сразу отчёт по этому медиа (пауза, выход из плеера). */
    fun flushNow(userId: Long, mediaId: Long) {
        val report = pending[userId]?.get(mediaId) ?: return
        write(listOf(report))
    }

    /** Выбросить ждущий отчёт — после ручной отметки «просмотрено» он устарел. */
    fun drop(userId: Long, mediaId: Long) {
        pending[userId]?.remove(mediaId)
    }

    @Scheduled(fixedDelayString = "\${mediaflux.app-api.viewer.flush-interval:30s}")
    fun flush() {
        val snapshot = pending.values.flatMap { it.values }
        if (snapshot.isNotEmpty()) write(snapshot)
    }

    private fun write(reports: List<ProgressReport>) {
        val now = LocalDateTime.now(ZoneOffset.UTC)
        val written = runCatching {
            dbGate.run {
                tx.execute {
                    reports.sumOf { r ->
                        repo.upsert(r.userId, r.mediaId, r.positionMs, r.durationMs, r.watched,
                            if (r.watched) r.reportedAt else null, r.reportedAt, r.deviceId, now)
                    }
                }!!
            }
        }.onFailure {
            log.warn("Позиции не записаны ({} отчётов), остаются в буфере до следующего сброса: {}", reports.size, it.message)
        }.getOrNull() ?: return
        // Снимаем только то, что записали и что не успели заменить более свежим отчётом.
        reports.forEach { r ->
            pending[r.userId]?.let { byMedia ->
                byMedia.remove(r.mediaId, r)
                if (byMedia.isEmpty()) pending.remove(r.userId, byMedia)
            }
        }
        flushed.increment(written.toDouble())
        stale.increment((reports.size - written).toDouble())
    }

    // ── Остановка пода: сбросить всё, что накопилось ──────────────────────────────────────────────

    override fun start() {
        running = true
    }

    override fun stop() {
        val left = pending.values.sumOf { it.size }
        if (left > 0) log.info("Остановка: записываю {} отчётов позиции из буфера", left)
        flush()
        running = false
    }

    override fun isRunning(): Boolean = running

    /**
     * Останавливаемся ПОСЛЕ веб-сервера: его плавная остановка (фаза `DEFAULT_PHASE - 1024`) дожидается запросов в
     * работе, и их отчёты должны успеть в буфер. Больший номер фазы останавливается раньше, поэтому наш — меньше.
     * База закрывается позже — при уничтожении бинов, после всех фаз.
     */
    override fun getPhase(): Int = SmartLifecycle.DEFAULT_PHASE - 4096
}
