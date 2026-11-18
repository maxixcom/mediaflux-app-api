package ru.mediaflux.app.api.guard

import com.github.benmanes.caffeine.cache.Caffeine
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import org.springframework.stereotype.Component
import ru.mediaflux.app.api.config.AppApiProperties
import java.time.Duration
import kotlin.math.ceil

/**
 * Лимиты частоты (APPS.md §10). Корзины Bucket4j в памяти копии: при N копиях лимит фактически умножается на N —
 * принято так же, как у стримера (MF-40). Неактивные корзины уходят из памяти через два часа.
 */
@Component
class RateLimiter(private val props: AppApiProperties) {

    enum class Kind { BROWSE, PLAYBACK, REGISTER, SERVER_INFO, PROGRESS }

    private val buckets = Caffeine.newBuilder()
        .maximumSize(500_000)
        .expireAfterAccess(Duration.ofHours(2))
        .build<String, Bucket>()

    /** null — пропускаем; иначе через сколько секунд можно повторить. */
    fun tryConsume(kind: Kind, key: String): Long? {
        val bucket = buckets.get("$kind:$key") { newBucket(kind) }
        val probe = bucket.tryConsumeAndReturnRemaining(1)
        if (probe.isConsumed) return null
        return ceil(probe.nanosToWaitForRefill / 1e9).toLong().coerceAtLeast(1)
    }

    private fun newBucket(kind: Kind): Bucket {
        val r = props.rateLimits
        val limit = when (kind) {
            Kind.BROWSE -> bandwidth(r.browseBurst, r.browsePerSecond, Duration.ofSeconds(1))
            Kind.PLAYBACK -> bandwidth(r.playbackPerMinute, r.playbackPerMinute, Duration.ofMinutes(1))
            Kind.REGISTER -> bandwidth(r.registerPerHour, r.registerPerHour, Duration.ofHours(1))
            Kind.SERVER_INFO -> bandwidth(r.serverInfoPerMinute, r.serverInfoPerMinute, Duration.ofMinutes(1))
            Kind.PROGRESS -> bandwidth(r.progressBurst, 1, r.progressPeriod)
        }
        return Bucket.builder().addLimit(limit).build()
    }

    private fun bandwidth(capacity: Long, refill: Long, period: Duration): Bandwidth =
        Bandwidth.builder().capacity(capacity).refillGreedy(refill, period).build()
}
