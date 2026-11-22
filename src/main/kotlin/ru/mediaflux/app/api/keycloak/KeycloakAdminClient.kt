package ru.mediaflux.app.api.keycloak

import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import ru.mediaflux.app.api.config.KeycloakAdminProperties
import java.time.Instant

/**
 * Второй шаг отзыва устройства (APPS.md §6, решение 17.11.2026): удалить офлайн-сессию по `sid`, чтобы телевизор
 * больше не получил новых токенов. Первый шаг — наша пометка — держит устройство снаружи и без этого.
 *
 * Служебный клиент `mediaflux-app-api` (client_credentials, роль `realm-management: manage-users`). Ходим по
 * внутреннему адресу кластера: на стендах `/admin` публичного имени закрыт белым списком.
 */
@Component
class KeycloakAdminClient(
    private val props: KeycloakAdminProperties,
    restClientBuilder: RestClient.Builder,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = restClientBuilder.clone().baseUrl(props.baseUrl.trimEnd('/')).build()

    @Volatile
    private var token: CachedToken? = null

    /** Служебный клиент настроен: без секрета удалять сессии нечем (локально без Keycloak-клиента). */
    val configured: Boolean get() = props.clientSecret.isNotBlank()

    /** `true` — сессия удалена или её уже нет; `false` — не получилось (WARN в лог, отзыв всё равно действует). */
    fun deleteOfflineSession(sid: String): Boolean {
        if (props.clientSecret.isBlank()) {
            log.warn("Секрет служебного клиента Keycloak не задан — офлайн-сессия {} не удалена", sid)
            return false
        }
        return runCatching {
            http.delete()
                .uri("/admin/realms/{realm}/sessions/{sid}?isOffline=true", props.realm, sid)
                .header("Authorization", "Bearer ${accessToken()}")
                .retrieve()
                .onStatus({ it.value() == 404 }) { _, _ -> } // сессии уже нет — цель достигнута
                .toBodilessEntity()
            true
        }.onFailure {
            log.warn("Офлайн-сессию {} в Keycloak удалить не удалось: {}", sid, it.message)
        }.getOrDefault(false)
    }

    private fun accessToken(): String {
        token?.takeIf { it.validUntil.isAfter(Instant.now()) }?.let { return it.value }
        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "client_credentials")
            add("client_id", props.clientId)
            add("client_secret", props.clientSecret)
        }
        val response = http.post()
            .uri("/realms/{realm}/protocol/openid-connect/token", props.realm)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body<TokenResponse>()
            ?: error("Пустой ответ на client_credentials")
        // Запас 30 с: токен не должен истечь между проверкой и запросом.
        val cached = CachedToken(response.access_token, Instant.now().plusSeconds((response.expires_in - 30).coerceAtLeast(5)))
        token = cached
        return cached.value
    }

    @Suppress("PropertyName")
    private data class TokenResponse(val access_token: String, val expires_in: Long)

    private data class CachedToken(val value: String, val validUntil: Instant)
}
