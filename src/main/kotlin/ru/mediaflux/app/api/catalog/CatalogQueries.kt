package ru.mediaflux.app.api.catalog

import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import ru.mediaflux.app.api.repository.ChannelItemRepository
import ru.mediaflux.app.api.repository.ChannelProgramVersionRepository
import ru.mediaflux.app.api.repository.ChannelRepository
import ru.mediaflux.app.api.repository.ChannelRow
import ru.mediaflux.app.api.repository.MediaChapterRepository
import ru.mediaflux.app.api.repository.MediaFileRepository
import ru.mediaflux.app.api.repository.MediaRepository
import ru.mediaflux.app.api.repository.PlaylistRepository
import ru.mediaflux.app.api.repository.ProgramVersionRow
import ru.mediaflux.app.api.repository.ProjectRepository
import ru.mediaflux.app.api.repository.SettingRepository
import ru.mediaflux.app.api.support.db.DbGate
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Карточка медиа. Адреса картинок собираются позже — от адреса стримера. */
data class MediaCard(
    val id: Long,
    val code: String,
    val title: String,
    val description: String?,
    val durationMs: Long?,
    val createdAt: LocalDateTime,
    val libraryId: Long,
    val chaptersEnabled: Boolean,
    /** Отпечаток обложки: null — обложки нет, "" — есть, но без отпечатка. */
    val coverFp: String?,
    val previewFp: String?,
)

data class LibraryCard(val id: Long, val title: String, val description: String?)

data class PlaylistCard(val id: Long, val code: String, val title: String, val description: String?)

data class ChannelCard(val id: Long, val code: String, val title: String, val description: String?, val onAir: Boolean)

data class Chapter(val startMs: Long, val title: String)

/**
 * Чтение каталога (APPS.md §3) поверх репозиториев: правило видимости — `CatalogJpql.PLAYABLE`. Все обращения к БД —
 * через [DbGate].
 *
 * N+1 нет нигде: пачка карточек — один запрос на карточки и один на отпечатки картинок; каналы — один запрос на
 * каналы, один на версии программ всех каналов и один на число элементов во всех текущих версиях.
 */
@Component
class CatalogQueries(
    private val media: MediaRepository,
    private val files: MediaFileRepository,
    private val chapters: MediaChapterRepository,
    private val projects: ProjectRepository,
    private val playlists: PlaylistRepository,
    private val channels: ChannelRepository,
    private val versions: ChannelProgramVersionRepository,
    private val channelItems: ChannelItemRepository,
    private val settings: SettingRepository,
    private val dbGate: DbGate,
) {

    fun recentMediaIds(userId: Long): List<Long> = dbGate.run { media.recentIds(userId, CAP) }

    fun libraryIds(userId: Long): List<Long> = dbGate.run { projects.libraryIds(userId, CAP) }

    fun libraryMediaIds(userId: Long, libraryId: Long): List<Long> = dbGate.run { media.libraryIds(userId, libraryId, CAP) }

    fun playlistIds(userId: Long): List<Long> = dbGate.run { playlists.playlistIds(userId, CAP) }

    fun playlistMediaIds(userId: Long, playlistId: Long): List<Long> = dbGate.run { media.playlistItemIds(userId, playlistId) }

    fun searchMediaIds(userId: Long, pattern: String): List<Long> = dbGate.run { media.searchIds(userId, pattern, SEARCH_CAP) }

    fun mediaCards(userId: Long, ids: Collection<Long>): Map<Long, MediaCard> {
        if (ids.isEmpty()) return emptyMap()
        return dbGate.run {
            val rows = media.cards(userId, ids)
            if (rows.isEmpty()) return@run emptyMap()
            // Последний файл роли побеждает (по возрастанию id) — как «свежий» в кабинете.
            val fps = files.fingerprints(rows.map { it.id }).associateBy({ it.mediaId to it.role }, { it.checksum ?: "" })
            rows.associate { r ->
                r.id to MediaCard(
                    id = r.id, code = r.code, title = r.title, description = r.description, durationMs = r.durationMs,
                    createdAt = r.createdAt, libraryId = r.libraryId, chaptersEnabled = r.chaptersEnabled,
                    coverFp = fps[r.id to ROLE_COVER], previewFp = fps[r.id to ROLE_PREVIEW_MANIFEST],
                )
            }
        }
    }

    fun libraryCards(userId: Long, ids: Collection<Long>): Map<Long, LibraryCard> {
        if (ids.isEmpty()) return emptyMap()
        return dbGate.run { projects.cards(userId, ids).associate { it.id to LibraryCard(it.id, it.title, it.description) } }
    }

    fun playlistCards(userId: Long, ids: Collection<Long>): Map<Long, PlaylistCard> {
        if (ids.isEmpty()) return emptyMap()
        return dbGate.run { playlists.cards(userId, ids).associate { it.id to PlaylistCard(it.id, it.code, it.title, it.description) } }
    }

    /**
     * Каналы владельца с признаком «в эфире» — правило кабинета `ChannelServiceImpl.isOn`: запущен и не остановлен на
     * [now], а в текущей версии программы есть элементы. [channelId] — только этот канал (для `playbackStart`).
     */
    fun channels(userId: Long, now: LocalDateTime = nowUtc(), channelId: Long? = null): List<ChannelCard> = dbGate.run {
        val rows = channels.channels(userId).filter { channelId == null || it.id == channelId }
        if (rows.isEmpty()) return@run emptyList()
        val current = versions.versions(rows.map { it.id }).groupBy { it.channelId }
            .mapValues { (_, list) -> currentVersion(list, now) }
        val counts = channelItems.counts(current.values.mapNotNull { it?.id }).associate { it.versionId to it.itemCount }
        rows.map { c ->
            val items = current[c.id]?.let { counts[it.id] } ?: 0L
            ChannelCard(c.id, c.code, c.title, c.description, onAir = started(c, now) && items > 0)
        }
    }

    fun chapters(mediaId: Long): List<Chapter> = dbGate.run { chapters.chapters(mediaId).map { Chapter(it.startMs, it.title) } }

    /** Настройка `stream/public_base_url` — публичный адрес стримера (её правит админка CDN). */
    fun streamerPublicBaseUrl(): String? = dbGate.run { settings.value("stream", "public_base_url") }

    companion object {
        /** Потолок длины списка id в кэше: дальше листать на телевизоре всё равно никто не будет. */
        private val CAP = PageRequest.of(0, 5000)
        private val SEARCH_CAP = PageRequest.of(0, 200)
        private const val ROLE_COVER = "COVER"
        private const val ROLE_PREVIEW_MANIFEST = "PREVIEW_MANIFEST"

        fun nowUtc(): LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)

        private fun started(c: ChannelRow, now: LocalDateTime): Boolean {
            val startedAt = c.startedAt ?: return false
            if (now.isBefore(startedAt)) return false
            return c.stoppedAt?.let { now.isBefore(it) } ?: true
        }

        /**
         * Текущая версия программы — как `ChannelProgramVersionServiceImpl.current` кабинета: от свежих к старым
         * (`effective_from` по убыванию, без даты — впереди, как в PostgreSQL; затем номер версии), первая вступившая в
         * силу; если ни одна не вступила — последняя в этом порядке.
         */
        fun currentVersion(list: List<ProgramVersionRow>, now: LocalDateTime): ProgramVersionRow? {
            val ordered = list.sortedWith(
                compareByDescending<ProgramVersionRow> { it.effectiveFrom == null }
                    .thenByDescending { it.effectiveFrom }
                    .thenByDescending { it.versionNo },
            )
            return ordered.firstOrNull { (it.effectiveFrom ?: LocalDateTime.MAX) <= now } ?: ordered.lastOrNull()
        }
    }
}
