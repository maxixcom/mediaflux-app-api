package ru.mediaflux.app.api.serverinfo

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.mediaflux.app.api.config.AppApiProperties
import ru.mediaflux.app.api.guard.RateLimiter
import ru.mediaflux.app.api.support.error.ErrorCodes

data class ServerInfo(
    val stand: String,
    val production: Boolean,
    /** Issuer Keycloak: остальное приложение находит в `.well-known/openid-configuration`. */
    val issuer: String,
    val clientId: String,
    val graphqlUrl: String,
    val minAppVersion: String?,
)

data class ErrorBody(val code: String, val params: Map<String, String> = emptyMap())

/**
 * Сведения о сервере (APPS.md §5.1) — единственная анонимная операция. Приложение знает только адрес сервера,
 * отсюда — всё остальное: Keycloak и клиент своей платформы, адрес GraphQL, стенд и минимальная версия.
 *
 * Это REST, а не GraphQL, намеренно: в режиме зарегистрированных операций сервер отсёк бы запрос приложения
 * неподходящей версии, а оно именно отсюда должно узнать, что версия не подходит.
 */
@RestController
class ServerInfoController(
    private val props: AppApiProperties,
    private val limiter: RateLimiter,
) {

    @GetMapping("/server-info")
    fun serverInfo(@RequestParam platform: String, request: HttpServletRequest): ResponseEntity<Any> {
        // Адрес клиента — после RemoteIpValve: из X-Forwarded-For, если запрос пришёл от ingress.
        limiter.tryConsume(RateLimiter.Kind.SERVER_INFO, request.remoteAddr)?.let { retryAfter ->
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", retryAfter.toString())
                .body(ErrorBody(ErrorCodes.RATE_LIMITED, mapOf("retryAfterSeconds" to retryAfter.toString())))
        }
        val key = platform.lowercase()
        val clientId = props.clients[key]
            ?: return ResponseEntity.badRequest().body(ErrorBody(ErrorCodes.PLATFORM_NOT_SUPPORTED, mapOf("platform" to platform)))
        return ResponseEntity.ok(
            ServerInfo(
                stand = props.stand,
                production = props.production,
                issuer = props.issuer,
                clientId = clientId,
                graphqlUrl = props.publicUrl.trimEnd('/') + "/graphql",
                minAppVersion = props.minAppVersion[key],
            ),
        )
    }
}
