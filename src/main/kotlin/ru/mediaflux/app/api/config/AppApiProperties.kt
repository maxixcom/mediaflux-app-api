package ru.mediaflux.app.api.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** Настройки app-api (`mediaflux.app-api`, application.yaml). */
@ConfigurationProperties(prefix = "mediaflux.app-api")
data class AppApiProperties(
    val stand: String = "local",
    val production: Boolean = false,
    val publicUrl: String = "http://localhost:17091",
    val issuer: String = "",
    /** Клиент Keycloak на платформу: `tvos` → `mediaflux-app-tvos`. Токен другого клиента не принимается. */
    val clients: Map<String, String> = emptyMap(),
    val minAppVersion: Map<String, String> = emptyMap(),
    val streamerPublicUrl: String = "http://localhost:17086",
    val devices: Devices = Devices(),
    val dbGate: DbGateProps = DbGateProps(),
    val graphql: GraphQl = GraphQl(),
    val rateLimits: RateLimits = RateLimits(),
) {
    data class Devices(
        val limit: Int = 3,
        val lastSeenWriteInterval: Duration = Duration.ofMinutes(5),
    )

    data class DbGateProps(
        val permits: Int = 20,
        val wait: Duration = Duration.ofSeconds(1),
    )

    data class GraphQl(
        val operations: OperationsMode = OperationsMode.OPEN,
        val maxDepth: Int = 10,
        val maxComplexity: Int = 500,
    )

    enum class OperationsMode { OPEN, REGISTERED }

    data class RateLimits(
        val browsePerSecond: Long = 10,
        val browseBurst: Long = 30,
        val playbackPerMinute: Long = 30,
        val registerPerHour: Long = 5,
        val serverInfoPerMinute: Long = 10,
    )

    /** Все наши клиенты Keycloak — сверка `azp`. */
    fun allowedClients(): Set<String> = clients.values.toSet()
}

/** Служебный клиент Keycloak для удаления офлайн-сессии при отзыве устройства (APPS.md §6). */
@ConfigurationProperties(prefix = "mediaflux.keycloak-admin")
data class KeycloakAdminProperties(
    val baseUrl: String = "http://localhost:29080",
    val realm: String = "mediaflux",
    val clientId: String = "mediaflux-app-api",
    val clientSecret: String = "",
)

/** Адрес GraphQL-API data-service (внутренний кластерный API без OAuth2). */
@ConfigurationProperties(prefix = "mediaflux.data-service")
data class DataServiceProperties(
    val url: String = "http://localhost:17083/graphql",
)

/** Владельческий токен стрима: секрет ОБЩИЙ со стримером и console (STREAM_AUTHORIZATION.md §3.1). */
@ConfigurationProperties(prefix = "mediaflux.stream-token")
data class StreamTokenProperties(
    val secret: String = "",
    val ownerTtlSeconds: Long = 43200,
)
