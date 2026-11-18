package ru.mediaflux.app.api.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable

/**
 * Настройка платформы: `stream/public_base_url` — публичный адрес стримера.
 *
 * Частичная сущность ТОЛЬКО ДЛЯ ЧТЕНИЯ (`@Immutable`): только колонки, нужные app-api. Схемой владеют миграции и
 * console/data-service; `ddl-auto: validate` сверяет эти колонки на старте. Статусы — строками, а не enum: появится
 * новое значение в другом сервисе — чтение не упадёт на «No enum constant», а правило каталога его просто не пропустит.
 */
@Entity
@Immutable
@Table(name = "t_setting")
class SettingEntity(
    @Id
    @Column(name = "id")
    var id: Long = 0,

    @Column(name = "namespace")
    var namespace: String = "",

    @Column(name = "code")
    var code: String = "",

    @Column(name = "value")
    var value: String? = null,
)
