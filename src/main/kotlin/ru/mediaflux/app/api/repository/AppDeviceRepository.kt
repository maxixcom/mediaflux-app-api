package ru.mediaflux.app.api.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import ru.mediaflux.app.api.entity.AppDeviceEntity
import java.time.LocalDateTime
import java.util.UUID

interface AppDeviceRepository : JpaRepository<AppDeviceEntity, Long> {

    fun findByUserIdAndInstallationId(userId: Long, installationId: UUID): AppDeviceEntity?

    fun findByUserIdAndPublicId(userId: Long, publicId: UUID): AppDeviceEntity?

    /** Последняя запись с этой сессией: при повторной регистрации сессия переезжает, старая запись её теряет. */
    fun findFirstBySessionIdOrderByUpdatedAtDesc(sessionId: String): AppDeviceEntity?

    fun findByUserIdAndRevokedAtIsNullOrderByCreatedAtAsc(userId: Long): List<AppDeviceEntity>

    @Query(
        """
        select count(d) from AppDeviceEntity d
        where d.userId = :userId and d.revokedAt is null and d.installationId <> :installationId
        """,
    )
    fun countActiveExcept(@Param("userId") userId: Long, @Param("installationId") installationId: UUID): Long

    @Modifying
    @Query("update AppDeviceEntity d set d.lastSeenAt = :at where d.id = :id")
    fun touch(@Param("id") id: Long, @Param("at") at: LocalDateTime): Int

    /**
     * Блокировка по пользователю на время транзакции — закрывает гонку двух регистраций (APPS.md §6). Форма с двумя int
     * — своё пространство ключей: с однокомпонентными блокировками console (`pg_advisory_xact_lock(bigint)`) не
     * пересекается.
     */
    @Query(value = "select 1 from (select pg_advisory_xact_lock(:space, :key)) x", nativeQuery = true)
    fun lockUser(@Param("space") space: Int, @Param("key") key: Int): Int
}
