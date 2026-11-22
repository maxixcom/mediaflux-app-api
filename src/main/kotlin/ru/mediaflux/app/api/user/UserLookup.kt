package ru.mediaflux.app.api.user

import org.springframework.stereotype.Component
import ru.mediaflux.app.api.repository.UserRepository
import ru.mediaflux.app.api.support.cache.Caches
import ru.mediaflux.app.api.support.cache.await
import ru.mediaflux.app.api.support.db.DbGate
import java.time.Duration
import java.util.Optional
import java.util.UUID

/** Пользователь, как его видит app-api: только то, что нужно для входа и каталога. */
data class AppUser(
    val id: Long,
    val externalId: UUID,
    val name: String?,
    val email: String?,
    val active: Boolean,
    /** Каналы открыты тарифом (`t_resource_group.channels_enabled`). */
    val channelsEnabled: Boolean,
    /** Приложения открыты тарифом (`t_resource_group.apps_enabled`, MF-91). */
    val appsEnabled: Boolean = false,
)

/**
 * Пользователь по `sub` токена (= `t_user.external_id`). Пользователей app-api НЕ создаёт: запись заводит console
 * при первом входе в кабинет (решение 17.11.2026). Нет записи — вход отклоняется с `account_not_ready`.
 *
 * Кэш: найденный — 60 с (статус и флаги тарифа, APPS.md §9: блокировка доходит за минуту), ненайденный — 10 с:
 * человек откроет кабинет и вернётся к телевизору, ждать минуту ему незачем.
 */
@Component
class UserLookup(
    private val users: UserRepository,
    private val dbGate: DbGate,
    caches: Caches,
) {

    private val bySub = caches.optionalLoading<UUID, AppUser>(
        "app_user_by_sub", maxSize = 50_000,
        found = Duration.ofSeconds(60), missing = Duration.ofSeconds(10),
    ) { sub -> Optional.ofNullable(load(sub)) }

    fun bySub(sub: UUID): AppUser? = bySub.get(sub).await().orElse(null)

    private fun load(sub: UUID): AppUser? = dbGate.run {
        users.findAppUser(sub)?.let {
            AppUser(
                it.id, it.externalId, it.name, it.email, active = it.status == "ACTIVE",
                channelsEnabled = it.channelsEnabled, appsEnabled = it.appsEnabled,
            )
        }
    }
}
