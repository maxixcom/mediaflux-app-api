package ru.mediaflux.app.api.repository

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import ru.mediaflux.app.api.entity.AppFavoriteEntity
import ru.mediaflux.app.api.entity.AppWatchProgressEntity
import java.time.LocalDateTime

interface AppWatchProgressRepository : JpaRepository<AppWatchProgressEntity, Long> {

    /**
     * Запись отчёта из буфера. Между копиями app-api побеждает поздний отчёт по времени клиента — условие в
     * `DO UPDATE … WHERE`, без блокировок. «Просмотрено» залипает (`or`): снимается только вручную ([markWatched]).
     * Касты у параметров — чтобы null не терял тип в нативном запросе.
     */
    @Modifying
    @Query(
        nativeQuery = true,
        value = """
        insert into t_app_watch_progress
            (user_id, media_id, position_ms, duration_ms, watched, watched_at, reported_at, device_id, created_at, updated_at)
        values (:userId, :mediaId, :positionMs, cast(:durationMs as bigint), :watched, cast(:watchedAt as timestamp),
                cast(:reportedAt as timestamp), cast(:deviceId as bigint), cast(:now as timestamp), cast(:now as timestamp))
        on conflict (user_id, media_id) do update set
            position_ms = excluded.position_ms,
            duration_ms = coalesce(excluded.duration_ms, t_app_watch_progress.duration_ms),
            watched = t_app_watch_progress.watched or excluded.watched,
            watched_at = coalesce(t_app_watch_progress.watched_at, excluded.watched_at),
            reported_at = excluded.reported_at,
            device_id = coalesce(excluded.device_id, t_app_watch_progress.device_id),
            updated_at = excluded.updated_at
        where t_app_watch_progress.reported_at < excluded.reported_at
        """,
    )
    fun upsert(
        @Param("userId") userId: Long,
        @Param("mediaId") mediaId: Long,
        @Param("positionMs") positionMs: Long,
        @Param("durationMs") durationMs: Long?,
        @Param("watched") watched: Boolean,
        @Param("watchedAt") watchedAt: LocalDateTime?,
        @Param("reportedAt") reportedAt: LocalDateTime,
        @Param("deviceId") deviceId: Long?,
        @Param("now") now: LocalDateTime,
    ): Int

    /**
     * Отметка «просмотрено» вручную — побеждает всегда (без условия по времени отчёта). Снятие отметки сбрасывает
     * позицию: «посмотреть заново» начинается с начала. `reported_at` поднимается до [now], чтобы запоздалый отчёт из
     * буфера соседней копии не вернул прежнее.
     */
    @Modifying
    @Query(
        nativeQuery = true,
        value = """
        insert into t_app_watch_progress
            (user_id, media_id, position_ms, duration_ms, watched, watched_at, reported_at, created_at, updated_at)
        values (:userId, :mediaId, 0, cast(:durationMs as bigint), :watched, cast(:watchedAt as timestamp),
                cast(:now as timestamp), cast(:now as timestamp), cast(:now as timestamp))
        on conflict (user_id, media_id) do update set
            watched = excluded.watched,
            watched_at = excluded.watched_at,
            position_ms = case when excluded.watched then t_app_watch_progress.position_ms else 0 end,
            duration_ms = coalesce(excluded.duration_ms, t_app_watch_progress.duration_ms),
            reported_at = greatest(t_app_watch_progress.reported_at, excluded.reported_at),
            updated_at = excluded.updated_at
        """,
    )
    fun markWatched(
        @Param("userId") userId: Long,
        @Param("mediaId") mediaId: Long,
        @Param("durationMs") durationMs: Long?,
        @Param("watched") watched: Boolean,
        @Param("watchedAt") watchedAt: LocalDateTime?,
        @Param("now") now: LocalDateTime,
    ): Int

    /** Позиции пачкой — для `Media.progress` и `resumePositionMs`. */
    fun findByUserIdAndMediaIdIn(userId: Long, mediaIds: Collection<Long>): List<AppWatchProgressEntity>

    /** «Продолжить просмотр»: не просмотрено, позиция не меньше порога, медиа видимо. Новые сверху. */
    @Query(
        """
        select p.mediaId from AppWatchProgressEntity p join MediaEntity m on m.id = p.mediaId
        where p.userId = :uid and p.watched = false and p.positionMs >= :minPosition and ${CatalogJpql.PLAYABLE}
        order by p.updatedAt desc, p.id desc
        """,
    )
    fun continueIds(@Param("uid") userId: Long, @Param("minPosition") minPositionMs: Long, page: Pageable): List<Long>
}

interface AppFavoriteRepository : JpaRepository<AppFavoriteEntity, Long> {

    /** Добавить идемпотентно: повтор ничего не меняет. */
    @Modifying
    @Query(
        nativeQuery = true,
        value = """
        insert into t_app_favorite (user_id, target_type, target_id, created_at)
        values (:userId, :type, :targetId, cast(:now as timestamp))
        on conflict (user_id, target_type, target_id) do nothing
        """,
    )
    fun add(@Param("userId") userId: Long, @Param("type") type: String, @Param("targetId") targetId: Long, @Param("now") now: LocalDateTime): Int

    @Modifying
    @Query("delete from AppFavoriteEntity f where f.userId = :userId and f.targetType = :type and f.targetId = :targetId")
    fun remove(@Param("userId") userId: Long, @Param("type") type: String, @Param("targetId") targetId: Long): Int

    /** Всё избранное пользователя — порядок добавления, новые сверху. Строк у человека немного. */
    fun findByUserIdOrderByCreatedAtDescIdDesc(userId: Long): List<AppFavoriteEntity>

    /** Что из [targetIds] в избранном — для поля `favorite` пачкой. */
    @Query("select f.targetId from AppFavoriteEntity f where f.userId = :userId and f.targetType = :type and f.targetId in :ids")
    fun favoriteIds(@Param("userId") userId: Long, @Param("type") type: String, @Param("ids") targetIds: Collection<Long>): List<Long>
}
