package ru.mediaflux.app.api.security

import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import ru.mediaflux.app.api.config.AppApiProperties
import ru.mediaflux.app.api.user.UserLookup
import java.util.UUID

/**
 * JWT → пользователь app-api (APPS.md §5).
 *
 * - **`azp`** — из списка наших клиентов приложений. `aud` тут ничего не говорит (у токенов Keycloak он `account`),
 *   а без проверки клиента в app-api ходили бы токены кабинета и любого другого клиента realm'а.
 * - **Пользователь по `sub`** (= `t_user.external_id`), а не по `preferred_username`, как в console: имя
 *   пользователь может сменить, идентификатор — нет. Нет записи — `account_not_ready`.
 * - **Заблокирован** — `account_disabled`, как в кабинете.
 * - **Ролей нет:** админ в приложении видит только своё.
 */
@Component
class AppAuthenticationConverter(
    private val props: AppApiProperties,
    private val userLookup: UserLookup,
) : Converter<Jwt, AbstractAuthenticationToken> {

    override fun convert(jwt: Jwt): AbstractAuthenticationToken {
        val azp = jwt.getClaimAsString("azp")
        if (azp == null || azp !in props.allowedClients()) {
            throw AccessRefusedException(AccessRefusedException.CLIENT_NOT_ALLOWED, "Клиент $azp не входит в ${props.allowedClients()}")
        }
        val sub = runCatching { UUID.fromString(jwt.subject) }.getOrNull()
            ?: throw AccessRefusedException(AccessRefusedException.ACCOUNT_NOT_READY, "sub не UUID: ${jwt.subject}")
        val user = userLookup.bySub(sub)
            ?: throw AccessRefusedException(AccessRefusedException.ACCOUNT_NOT_READY, "Пользователя с external_id=$sub нет")
        if (!user.active) {
            throw AccessRefusedException(AccessRefusedException.ACCOUNT_DISABLED, "Пользователь ${user.id} заблокирован")
        }
        return AppAuthentication(jwt, AppPrincipal(user = user, sid = jwt.getClaimAsString("sid"), clientId = azp))
    }
}
