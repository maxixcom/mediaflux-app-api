package ru.mediaflux.app.api.catalog

import com.github.benmanes.caffeine.cache.AsyncLoadingCache
import com.github.benmanes.caffeine.cache.CacheLoader
import org.springframework.stereotype.Service
import ru.mediaflux.app.api.config.AppApiProperties
import ru.mediaflux.app.api.support.cache.Caches
import ru.mediaflux.app.api.support.cache.await
import java.time.Duration
import java.util.Optional

/** Ключи кэшей каталога. Владелец — ВСЕГДА часть ключа: карточка одного пользователя не достаётся другому. */
data class UserKey(val userId: Long, val id: Long)

data class SearchKey(val userId: Long, val pattern: String)

/**
 * Каталог через кэш (APPS.md §9).
 *
 * - **Списки хранят только id**, карточки — отдельно по id. Экран из сотни плиток — это один список и одна пачка
 *   карточек, а в БД уходят только промахи одним `WHERE id IN (…)`.
 * - **Сроки:** библиотеки — 60 с, составы и списки — 30 с (с фоновым обновлением раньше срока), карточка — 3 мин,
 *   «не найдено» — 30 с.
 * - При N копиях промахи умножаются в N раз — на старте это терпимо; инвалидация через Kafka — позже.
 */
@Service
class CatalogService(
    private val queries: CatalogQueries,
    private val props: AppApiProperties,
    private val caches: Caches,
) {
    private val recent = caches.loading<Long, List<Long>>(
        "catalog_recent", 50_000, Duration.ofSeconds(30), Duration.ofSeconds(15),
    ) { uid -> queries.recentMediaIds(uid) }

    private val libraries = caches.loading<Long, List<Long>>(
        "catalog_libraries", 50_000, Duration.ofSeconds(60), Duration.ofSeconds(30),
    ) { uid -> queries.libraryIds(uid) }

    private val libraryItems = caches.loading<UserKey, List<Long>>(
        "catalog_library_items", 200_000, Duration.ofSeconds(30), Duration.ofSeconds(15),
    ) { key -> queries.libraryMediaIds(key.userId, key.id) }

    private val playlists = caches.loading<Long, List<Long>>(
        "catalog_playlists", 50_000, Duration.ofSeconds(30), Duration.ofSeconds(15),
    ) { uid -> queries.playlistIds(uid) }

    private val playlistItems = caches.loading<UserKey, List<Long>>(
        "catalog_playlist_items", 200_000, Duration.ofSeconds(30), Duration.ofSeconds(15),
    ) { key -> queries.playlistMediaIds(key.userId, key.id) }

    private val channels = caches.loading<Long, List<ChannelCard>>(
        "catalog_channels", 50_000, Duration.ofSeconds(30), Duration.ofSeconds(15),
    ) { uid -> queries.channels(uid) }

    private val search = caches.loading<SearchKey, List<Long>>(
        "catalog_search", 20_000, Duration.ofSeconds(30),
    ) { key -> queries.searchMediaIds(key.userId, key.pattern) }

    private val mediaCards = cardCache("catalog_media_card", 500_000, Duration.ofMinutes(3)) { uid, ids -> queries.mediaCards(uid, ids) }
    private val libraryCards = cardCache("catalog_library_card", 100_000, Duration.ofSeconds(60)) { uid, ids -> queries.libraryCards(uid, ids) }
    private val playlistCards = cardCache("catalog_playlist_card", 100_000, Duration.ofSeconds(60)) { uid, ids -> queries.playlistCards(uid, ids) }

    private val streamerBase = caches.loading<Unit, String>("streamer_base", 1, Duration.ofSeconds(60)) {
        (queries.streamerPublicBaseUrl()?.trim()?.takeIf { it.isNotEmpty() } ?: props.streamerPublicUrl).trimEnd('/')
    }

    fun recentIds(userId: Long): List<Long> = recent.get(userId).await()
    fun libraryIds(userId: Long): List<Long> = libraries.get(userId).await()
    fun libraryItemIds(userId: Long, libraryId: Long): List<Long> = libraryItems.get(UserKey(userId, libraryId)).await()
    fun playlistIds(userId: Long): List<Long> = playlists.get(userId).await()
    fun playlistItemIds(userId: Long, playlistId: Long): List<Long> = playlistItems.get(UserKey(userId, playlistId)).await()

    fun channels(userId: Long, channelsEnabled: Boolean): List<ChannelCard> =
        if (channelsEnabled) channels.get(userId).await() else emptyList()

    fun searchIds(userId: Long, text: String): List<Long> = search.get(SearchKey(userId, likePattern(text))).await()

    /** Карточки в порядке [ids]; невидимые (чужие, скрытые, не готовые) выпадают. */
    fun media(userId: Long, ids: List<Long>): List<MediaCard> = ordered(mediaCards, userId, ids)
    fun libraries(userId: Long, ids: List<Long>): List<LibraryCard> = ordered(libraryCards, userId, ids)
    fun playlists(userId: Long, ids: List<Long>): List<PlaylistCard> = ordered(playlistCards, userId, ids)

    fun streamerBase(): String = streamerBase.get(Unit).await()

    fun coverUrl(card: MediaCard?): String? = card?.coverFp?.let { fp ->
        "${streamerBase()}/preview/${card.code}/cover.jpg" + if (fp.isNotEmpty()) "?v=$fp" else ""
    }

    fun previewManifestUrl(card: MediaCard?): String? = card?.previewFp?.let { fp ->
        "${streamerBase()}/preview/${card.code}/manifest.json" + if (fp.isNotEmpty()) "?v=$fp" else ""
    }

    private fun <V : Any> ordered(cache: AsyncLoadingCache<UserKey, Optional<V>>, userId: Long, ids: List<Long>): List<V> {
        if (ids.isEmpty()) return emptyList()
        val keys = ids.map { UserKey(userId, it) }
        val found = cache.getAll(keys).await()
        return keys.mapNotNull { found[it]?.orElse(null) }
    }

    /**
     * Кэш карточек по [UserKey] с пачечной загрузкой: промахи одного запроса уходят в базу одним запросом на
     * пользователя. Отсутствующие тоже запоминаются (коротко), чтобы перебор чужих id не долбил базу.
     */
    private fun <V : Any> cardCache(
        name: String,
        maxSize: Long,
        found: Duration,
        fetch: (Long, Collection<Long>) -> Map<Long, V>,
    ): AsyncLoadingCache<UserKey, Optional<V>> =
        caches.optionalLoading(name, maxSize, found, Duration.ofSeconds(30), object : CacheLoader<UserKey, Optional<V>> {
            override fun load(key: UserKey): Optional<V> = Optional.ofNullable(fetch(key.userId, listOf(key.id))[key.id])

            override fun loadAll(keys: Set<UserKey>): Map<UserKey, Optional<V>> =
                keys.groupBy { it.userId }.flatMap { (uid, group) ->
                    val rows = fetch(uid, group.map { it.id })
                    group.map { it to Optional.ofNullable(rows[it.id]) }
                }.toMap()
        })

    companion object {
        /** Текст поиска → шаблон ILIKE: регистр и пробелы по краям не важны, `%` и `_` ищутся буквально. */
        fun likePattern(text: String): String {
            val escaped = text.trim().lowercase()
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            return "%$escaped%"
        }
    }
}
