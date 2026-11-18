package ru.mediaflux.app.api.security

import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.authentication.AccountStatusException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import ru.mediaflux.app.api.user.AppUser

/** Кто пришёл: пользователь, его сессия Keycloak (`sid` = устройство) и клиент (`azp` = платформа). */
data class AppPrincipal(
    val user: AppUser,
    /** Сессия Keycloak. По ней на каждом запросе находится устройство (APPS.md §6). */
    val sid: String?,
    val clientId: String,
)

class AppAuthentication(
    private val jwt: Jwt,
    private val appPrincipal: AppPrincipal,
) : AbstractAuthenticationToken(emptyList()) {

    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any = jwt.tokenValue

    override fun getPrincipal(): AppPrincipal = appPrincipal

    override fun getName(): String = appPrincipal.user.externalId.toString()
}

/** Причина отказа во входе — уходит телом 403 (`{"error": reason}`), см. AppAuthenticationEntryPoint. */
class AccessRefusedException(val reason: String, message: String) : AccountStatusException(message) {
    companion object {
        /** Аккаунт заблокирован (как в console). */
        const val ACCOUNT_DISABLED = "account_disabled"

        /** Записи пользователя ещё нет — он ни разу не входил в кабинет. */
        const val ACCOUNT_NOT_READY = "account_not_ready"

        /** Токен выдан не нашему клиенту приложения (например, кабинету). */
        const val CLIENT_NOT_ALLOWED = "client_not_allowed"
    }
}

/** Текущий пользователь из контекста безопасности. Spring GraphQL переносит контекст в резолверы. */
object CurrentPrincipal {
    fun get(): AppPrincipal =
        (SecurityContextHolder.getContext().authentication as? AppAuthentication)?.principal
            ?: error("Нет аутентификации в контексте запроса")
}
