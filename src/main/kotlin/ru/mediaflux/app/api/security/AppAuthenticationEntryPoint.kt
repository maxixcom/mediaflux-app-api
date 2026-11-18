package ru.mediaflux.app.api.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint
import org.springframework.security.web.AuthenticationEntryPoint

/**
 * Разводит причины провала входа, чтобы приложение не путало их (как `BlockedAwareAuthenticationEntryPoint`
 * в console):
 * - отказ по правилам ([AccessRefusedException]) → **403** и `{"error": "<причина>"}`: `account_disabled`,
 *   `account_not_ready`, `client_not_allowed`. Обновлять токен тут бесполезно — нужен другой экран;
 * - протухший или битый токен → **401** (дефолт), приложение обновляет токен.
 */
class AppAuthenticationEntryPoint : AuthenticationEntryPoint {

    private val bearer = BearerTokenAuthenticationEntryPoint()

    override fun commence(request: HttpServletRequest, response: HttpServletResponse, authException: AuthenticationException) {
        val refused = authException as? AccessRefusedException ?: authException.cause as? AccessRefusedException
        if (refused == null) {
            bearer.commence(request, response, authException)
            return
        }
        response.status = HttpStatus.FORBIDDEN.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write("""{"error":"${refused.reason}"}""")
    }
}
