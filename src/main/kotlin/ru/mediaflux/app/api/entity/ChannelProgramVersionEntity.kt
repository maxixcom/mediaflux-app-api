package ru.mediaflux.app.api.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime
import org.hibernate.annotations.Immutable

/**
 * Версия программы канала: текущая — по `effective_from` (как `ChannelProgramVersionServiceImpl.current`).
 *
 * Частичная сущность ТОЛЬКО ДЛЯ ЧТЕНИЯ (`@Immutable`): только колонки, нужные app-api. Схемой владеют миграции и
 * console/data-service; `ddl-auto: validate` сверяет эти колонки на старте. Статусы — строками, а не enum: появится
 * новое значение в другом сервисе — чтение не упадёт на «No enum constant», а правило каталога его просто не пропустит.
 */
@Entity
@Immutable
@Table(name = "t_channel_program_version")
class ChannelProgramVersionEntity(
    @Id
    @Column(name = "id")
    var id: Long = 0,

    @Column(name = "channel_id")
    var channelId: Long = 0,

    @Column(name = "version_no")
    var versionNo: Int = 0,

    @Column(name = "effective_from")
    var effectiveFrom: LocalDateTime? = null,
)
