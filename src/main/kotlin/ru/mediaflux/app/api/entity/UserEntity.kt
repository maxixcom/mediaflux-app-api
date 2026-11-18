package ru.mediaflux.app.api.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.util.UUID
import org.hibernate.annotations.Immutable

/**
 * Пользователь: вход по `sub` (= `external_id`), статус, тариф.
 *
 * Частичная сущность ТОЛЬКО ДЛЯ ЧТЕНИЯ (`@Immutable`): только колонки, нужные app-api. Схемой владеют миграции и
 * console/data-service; `ddl-auto: validate` сверяет эти колонки на старте. Статусы — строками, а не enum: появится
 * новое значение в другом сервисе — чтение не упадёт на «No enum constant», а правило каталога его просто не пропустит.
 */
@Entity
@Immutable
@Table(name = "t_user")
class UserEntity(
    @Id
    @Column(name = "id")
    var id: Long = 0,

    @Column(name = "external_id")
    var externalId: UUID? = null,

    @Column(name = "username")
    var username: String? = null,

    @Column(name = "display_name")
    var displayName: String? = null,

    @Column(name = "email")
    var email: String? = null,

    @Column(name = "status")
    var status: String = "",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resource_group_id")
    var resourceGroup: ResourceGroupEntity? = null,
)
