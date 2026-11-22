package ru.mediaflux.app.api.device

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import ru.mediaflux.app.api.config.AppApiProperties

/**
 * Досылка закрытия офлайн-сессий отключённых устройств (APPS.md §6, MF-91).
 *
 * Отзыв из кабинета ставит только `revoked_at`: служебный клиент Keycloak есть только у app-api. Сюда же попадают
 * отзывы с телевизора, когда Keycloak не ответил. Блокировки между копиями нет: удаление сессии идемпотентно.
 */
@Component
class DeviceSessionCloser(
    private val devices: DeviceService,
    private val props: AppApiProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        initialDelayString = "\${mediaflux.app-api.devices.session-close-interval:30s}",
        fixedDelayString = "\${mediaflux.app-api.devices.session-close-interval:30s}",
    )
    fun run() {
        runCatching { devices.closePendingSessions(props.devices.sessionCloseBatch) }
            .onFailure { log.warn("Досылка закрытия сессий отключённых устройств не прошла: {}", it.message) }
    }
}
