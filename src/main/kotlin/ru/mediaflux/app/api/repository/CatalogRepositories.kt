package ru.mediaflux.app.api.repository

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import ru.mediaflux.app.api.entity.ChannelEntity
import ru.mediaflux.app.api.entity.ChannelItemEntity
import ru.mediaflux.app.api.entity.ChannelProgramVersionEntity
import ru.mediaflux.app.api.entity.MediaChapterEntity
import ru.mediaflux.app.api.entity.MediaEntity
import ru.mediaflux.app.api.entity.MediaFileEntity
import ru.mediaflux.app.api.entity.PlaylistEntity
import ru.mediaflux.app.api.entity.ProjectEntity
import ru.mediaflux.app.api.entity.SettingEntity
import ru.mediaflux.app.api.entity.UserEntity
import java.time.LocalDateTime
import java.util.UUID

/**
 * Правило «медиа можно показать» (APPS.md §3) — одно на все запросы каталога, повторяет кабинет и стример:
 * - медиа своё, `ACTIVE`, не удалено, не закрыто модерацией, видео не выключено владельцем;
 * - проект `ACTIVE` и не удалён (`MediaStreamSettingsServiceImpl`: active = media ACTIVE и project ACTIVE);
 * - есть готовое видео: дорожка VIDEO/ENCODED/READY, не удалена и включена (`MediaSpecification.findByPlayable`).
 *
 * Владелец медиа — владелец проекта, поэтому проекты, где пользователь участник, сюда не попадают сами.
 * Ожидает псевдоним `m` (MediaEntity) и параметр `:uid`.
 */
object CatalogJpql {
    const val PLAYABLE = """
        m.userId = :uid and m.status = 'ACTIVE' and m.deleted = false and m.moderationStatus = 'OK'
        and m.videoEnabled = true
        and m.project.userId = :uid and m.project.status = 'ACTIVE' and m.project.deleted = false
        and exists (
          select 1 from MediaTrackEntity t
          where t.mediaId = m.id and t.kind = 'VIDEO' and t.stage = 'ENCODED' and t.status = 'READY'
            and t.deleted = false and t.enabled = true)
    """

    /** Плейлист показывается: свой, `ACTIVE`, не закрыт модерацией. Псевдоним `pl`. */
    const val PLAYLIST_VISIBLE = "pl.userId = :uid and pl.status = 'ACTIVE' and pl.moderationStatus = 'OK'"
}

// ── Проекции ──────────────────────────────────────────────────────────────────────────────────────

interface UserRow {
    val id: Long
    val externalId: UUID
    val name: String?
    val email: String?
    val status: String
    val channelsEnabled: Boolean
}

interface MediaCardRow {
    val id: Long
    val code: String
    val title: String
    val description: String?
    val durationMs: Long?
    val createdAt: LocalDateTime
    val libraryId: Long
    val chaptersEnabled: Boolean
}

interface MediaFileFpRow {
    val mediaId: Long
    val role: String
    val checksum: String?
}

interface LibraryCardRow {
    val id: Long
    val title: String
    val description: String?
}

interface PlaylistCardRow {
    val id: Long
    val code: String
    val title: String
    val description: String?
}

interface ChannelRow {
    val id: Long
    val code: String
    val title: String
    val description: String?
    val startedAt: LocalDateTime?
    val stoppedAt: LocalDateTime?
}

interface ProgramVersionRow {
    val id: Long
    val channelId: Long
    val versionNo: Int
    val effectiveFrom: LocalDateTime?
}

interface VersionItemCountRow {
    val versionId: Long
    val itemCount: Long
}

interface ChapterRow {
    val startMs: Long
    val title: String
}

// ── Репозитории (только чтение: `Repository`, а не `JpaRepository` — методов записи у них нет) ─────

interface UserRepository : Repository<UserEntity, Long> {

    @Query(
        """
        select u.id as id, u.externalId as externalId, coalesce(u.displayName, u.username) as name, u.email as email,
               u.status as status, coalesce(rg.channelsEnabled, false) as channelsEnabled
        from UserEntity u left join u.resourceGroup rg
        where u.externalId = :sub
        """,
    )
    fun findAppUser(@Param("sub") sub: UUID): UserRow?
}

interface MediaRepository : Repository<MediaEntity, Long> {

    @Query("select m.id from MediaEntity m where ${CatalogJpql.PLAYABLE} order by m.createdAt desc, m.id desc")
    fun recentIds(@Param("uid") userId: Long, page: Pageable): List<Long>

    @Query(
        """
        select m.id from MediaEntity m
        where m.project.id = :pid and ${CatalogJpql.PLAYABLE}
        order by m.createdAt desc, m.id desc
        """,
    )
    fun libraryIds(@Param("uid") userId: Long, @Param("pid") libraryId: Long, page: Pageable): List<Long>

    @Query(
        """
        select m.id from MediaEntity m
        where ${CatalogJpql.PLAYABLE} and lower(m.title) like :q escape '\'
        order by m.createdAt desc, m.id desc
        """,
    )
    fun searchIds(@Param("uid") userId: Long, @Param("q") pattern: String, page: Pageable): List<Long>

    /** Карточки пачкой: один запрос на все промахи кэша. */
    @Query(
        """
        select m.id as id, m.code as code, m.title as title, m.description as description, m.durationMs as durationMs,
               m.createdAt as createdAt, m.project.id as libraryId, m.chaptersEnabled as chaptersEnabled
        from MediaEntity m
        where m.id in :ids and ${CatalogJpql.PLAYABLE}
        """,
    )
    fun cards(@Param("uid") userId: Long, @Param("ids") ids: Collection<Long>): List<MediaCardRow>

    /** Элементы плейлиста в его порядке — только видимые. */
    @Query(
        """
        select m.id from PlaylistItemEntity i
        join PlaylistEntity pl on pl.id = i.playlistId
        join MediaEntity m on m.id = i.mediaId
        where i.playlistId = :plid and ${CatalogJpql.PLAYLIST_VISIBLE} and ${CatalogJpql.PLAYABLE}
        order by i.sortOrder, i.id
        """,
    )
    fun playlistItemIds(@Param("uid") userId: Long, @Param("plid") playlistId: Long): List<Long>
}

interface MediaFileRepository : Repository<MediaFileEntity, Long> {

    /** Отпечатки обложки и манифеста превью пачкой — одним запросом на карточки. */
    @Query(
        """
        select f.mediaId as mediaId, f.role as role, f.checksum as checksum from MediaFileEntity f
        where f.mediaId in :ids and f.role in ('COVER', 'PREVIEW_MANIFEST') and f.status = 'READY' and f.deleted = false
        order by f.id
        """,
    )
    fun fingerprints(@Param("ids") mediaIds: Collection<Long>): List<MediaFileFpRow>
}

interface MediaChapterRepository : Repository<MediaChapterEntity, Long> {

    @Query("select c.startMs as startMs, c.title as title from MediaChapterEntity c where c.mediaId = :mid order by c.startMs, c.id")
    fun chapters(@Param("mid") mediaId: Long): List<ChapterRow>
}

interface ProjectRepository : Repository<ProjectEntity, Long> {

    /** Библиотеки — проекты, где есть что смотреть. По алфавиту: так их ищут глазами на экране. */
    @Query(
        """
        select p.id from ProjectEntity p
        where p.userId = :uid and p.status = 'ACTIVE' and p.deleted = false
          and exists (select 1 from MediaEntity m where m.project = p and ${CatalogJpql.PLAYABLE})
        order by lower(p.title), p.id
        """,
    )
    fun libraryIds(@Param("uid") userId: Long, page: Pageable): List<Long>

    @Query(
        """
        select p.id as id, p.title as title, p.description as description from ProjectEntity p
        where p.id in :ids and p.userId = :uid and p.status = 'ACTIVE' and p.deleted = false
        """,
    )
    fun cards(@Param("uid") userId: Long, @Param("ids") ids: Collection<Long>): List<LibraryCardRow>
}

interface PlaylistRepository : Repository<PlaylistEntity, Long> {

    @Query(
        """
        select pl.id from PlaylistEntity pl
        where ${CatalogJpql.PLAYLIST_VISIBLE}
          and exists (
            select 1 from PlaylistItemEntity i join MediaEntity m on m.id = i.mediaId
            where i.playlistId = pl.id and ${CatalogJpql.PLAYABLE})
        order by pl.createdAt desc, pl.id desc
        """,
    )
    fun playlistIds(@Param("uid") userId: Long, page: Pageable): List<Long>

    @Query(
        """
        select pl.id as id, pl.code as code, pl.title as title, pl.description as description from PlaylistEntity pl
        where pl.id in :ids and ${CatalogJpql.PLAYLIST_VISIBLE}
        """,
    )
    fun cards(@Param("uid") userId: Long, @Param("ids") ids: Collection<Long>): List<PlaylistCardRow>
}

interface ChannelRepository : Repository<ChannelEntity, Long> {

    @Query(
        """
        select c.id as id, c.code as code, c.title as title, c.description as description,
               c.startedAt as startedAt, c.stoppedAt as stoppedAt
        from ChannelEntity c
        where c.userId = :uid and c.status = 'ACTIVE' and c.moderationStatus = 'OK'
        order by lower(c.title), c.id
        """,
    )
    fun channels(@Param("uid") userId: Long): List<ChannelRow>
}

interface ChannelProgramVersionRepository : Repository<ChannelProgramVersionEntity, Long> {

    /** Версии программ всех каналов списка — одним запросом. Выбор текущей — в коде, как у кабинета. */
    @Query(
        """
        select v.id as id, v.channelId as channelId, v.versionNo as versionNo, v.effectiveFrom as effectiveFrom
        from ChannelProgramVersionEntity v where v.channelId in :ids
        """,
    )
    fun versions(@Param("ids") channelIds: Collection<Long>): List<ProgramVersionRow>
}

interface ChannelItemRepository : Repository<ChannelItemEntity, Long> {

    /** Сколько элементов в каждой из версий — одним запросом. */
    @Query(
        """
        select ci.versionId as versionId, count(ci) as itemCount from ChannelItemEntity ci
        where ci.versionId in :vids group by ci.versionId
        """,
    )
    fun counts(@Param("vids") versionIds: Collection<Long>): List<VersionItemCountRow>
}

interface SettingRepository : Repository<SettingEntity, Long> {

    @Query("select s.value from SettingEntity s where s.namespace = :ns and s.code = :code")
    fun value(@Param("ns") namespace: String, @Param("code") code: String): String?
}
