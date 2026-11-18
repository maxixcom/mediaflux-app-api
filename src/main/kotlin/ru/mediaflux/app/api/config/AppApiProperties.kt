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
    val viewer: Viewer = Viewer(),
) {
    /** Состояние зрителя (MF-89, APPS.md §7). */
    data class Viewer(
        /** Как часто буфер позиций сбрасывается в базу. Пауза и выход из плеера пишутся сразу. */
        val flushInterval: Duration = Duration.ofSeconds(30),
        /** С какой доли длительности медиа считается просмотренным (решение 18.11.2026 — 95 %). */
        val watchedThreshold: Double = 0.95,
        /** С какой позиции медиа попадает в «Продолжить просмотр» и получает `resumePositionMs`. */
        val continueMinPosition: Duration = Duration.ofSeconds(30),
    )

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
        /** Позиция с устройства: не чаще раза в [progressPeriod], всплеск [progressBurst] (пауза, выход, перемотка). */
        val progressBurst: Long = 3,
        val progressPeriod: Duration = Duration.ofSeconds(5),
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
