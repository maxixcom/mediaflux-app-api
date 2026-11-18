package ru.mediaflux.app.api.support.cache

import com.github.benmanes.caffeine.cache.AsyncCache
import com.github.benmanes.caffeine.cache.AsyncLoadingCache
import com.github.benmanes.caffeine.cache.CacheLoader
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Expiry
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Фабрика кэшей app-api (APPS.md §9). Все кэши — в памяти копии.
 *
 * - **Асинхронные (`AsyncCache`)**: одновременные промахи по одному ключу ждут ОДНОЙ загрузки (склейка), а
 *   загрузка идёт на своём виртуальном потоке и не держит блокировку корзины `ConcurrentHashMap`, как держал бы
 *   синхронный `get(key, loader)` на долгом запросе.
 * - **`refreshAfterWrite`** у горячих списков: запись старше порога отдаётся как есть, а свежая подгружается в
 *   фоне — зритель не ждёт базу на каждом истечении TTL.
 * - **`maximumSize` и `recordStats` у каждого**: метрики `cache_*` с тегом имени — по ним видно, сколько промахов
 *   доходит до БД.
 *
 * Ключи кэшей с данными пользователя ВСЕГДА содержат его id (тест `CacheOwnerKeyTest`).
 */
@Component
class Caches(private val meterRegistry: MeterRegistry) {

    /** Загрузки кэшей — на виртуальных потоках: блокирующий JDBC здесь нормален. */
    val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    fun <K : Any, V : Any> loading(
        name: String,
        maxSize: Long,
        ttl: Duration,
        refreshAfter: Duration? = null,
        loader: CacheLoader<K, V>,
    ): AsyncLoadingCache<K, V> {
        val builder = Caffeine.newBuilder()
            .executor(executor)
            .maximumSize(maxSize)
            .expireAfterWrite(ttl)
            .recordStats()
        refreshAfter?.let { builder.refreshAfterWrite(it) }
        return builder.buildAsync(loader).also { register(name, it) }
    }

    /**
     * Кэш «значение или его отсутствие» с разными сроками: найденное живёт [found], ненайденное — [missing]
     * («не найдено» кэшируем коротко, APPS.md §9).
     */
    fun <K : Any, V : Any> optionalLoading(
        name: String,
        maxSize: Long,
        found: Duration,
        missing: Duration,
        loader: CacheLoader<K, Optional<V>>,
    ): AsyncLoadingCache<K, Optional<V>> =
        Caffeine.newBuilder()
            .executor(executor)
            .maximumSize(maxSize)
            .expireAfter(OptionalExpiry<K, V>(found, missing))
            .recordStats()
            .buildAsync(loader)
            .also { register(name, it) }

    fun <K : Any, V : Any> plain(name: String, maxSize: Long, ttl: Duration): AsyncCache<K, V> =
        Caffeine.newBuilder()
            .executor(executor)
            .maximumSize(maxSize)
            .expireAfterWrite(ttl)
            .recordStats()
            .buildAsync<K, V>()
            .also { register(name, it) }

    private fun register(name: String, cache: AsyncCache<*, *>) {
        CaffeineCacheMetrics.monitor(meterRegistry, cache.synchronous(), name)
    }

    private class OptionalExpiry<K : Any, V : Any>(found: Duration, missing: Duration) : Expiry<K, Optional<V>> {
        private val foundNanos = found.toNanos()
        private val missingNanos = missing.toNanos()

        override fun expireAfterCreate(key: K, value: Optional<V>, currentTime: Long): Long =
            if (value.isPresent) foundNanos else missingNanos

        override fun expireAfterUpdate(key: K, value: Optional<V>, currentTime: Long, currentDuration: Long): Long =
            expireAfterCreate(key, value, currentTime)

        override fun expireAfterRead(key: K, value: Optional<V>, currentTime: Long, currentDuration: Long): Long =
            currentDuration
    }
}

/** Дождаться значения кэша на текущем (виртуальном) потоке; ошибку загрузки — исходным исключением. */
fun <V> java.util.concurrent.CompletableFuture<V>.await(): V =
    try {
        join()
    } catch (e: CompletionException) {
        throw e.cause ?: e
    }
