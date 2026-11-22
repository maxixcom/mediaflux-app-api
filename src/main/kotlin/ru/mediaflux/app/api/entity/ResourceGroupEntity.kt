package ru.mediaflux.app.api.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable

/**
 * Тариф: только флаги, которые решают, что показывать в приложении.
 *
 * Частичная сущность ТОЛЬКО ДЛЯ ЧТЕНИЯ (`@Immutable`): только колонки, нужные app-api. Схемой владеют миграции и
 * console/data-service; `ddl-auto: validate` сверяет эти колонки на старте. Статусы — строками, а не enum: появится
 * новое значение в другом сервисе — чтение не упадёт на «No enum constant», а правило каталога его просто не пропустит.
 */
@Entity
@Immutable
@Table(name = "t_resource_group")
class ResourceGroupEntity(
    @Id
    @Column(name = "id")
    var id: Long = 0,

    @Column(name = "channels_enabled")
    var channelsEnabled: Boolean = false,

    /** Приложения открыты тарифом (APPS.md §6, MF-91): снят — всё, кроме `me` и `deviceRevoke`, отказывает. */
    @Column(name = "apps_enabled")
    var appsEnabled: Boolean = false,
)
