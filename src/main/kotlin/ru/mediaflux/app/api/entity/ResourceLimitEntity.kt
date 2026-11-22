package ru.mediaflux.app.api.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable

/**
 * Лимит тарифа — app-api читает только `DEVICE_COUNT` (APPS.md §6, MF-91).
 *
 * Частичная сущность ТОЛЬКО ДЛЯ ЧТЕНИЯ: цель и режим — строками, а не enum. Новый показатель в console или
 * data-service не должен ронять чтение здесь на «No enum constant» — у тех сервисов это уже случалось.
 */
@Entity
@Immutable
@Table(name = "t_resource_limit")
class ResourceLimitEntity(
    @Id
    @Column(name = "id")
    var id: Long = 0,

    @Column(name = "resource_group_id")
    var resourceGroupId: Long = 0,

    @Column(name = "target")
    var target: String = "",

    /** NULL — без ограничения. */
    @Column(name = "value")
    var value: Long? = null,

    @Column(name = "enforcement")
    var enforcement: String = "",
)
