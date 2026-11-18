package ru.mediaflux.app.api.config

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Проверка зоны процесса на старте — есть в каждом сервисе (корневой CLAUDE.md).
 *
 * Беззонные `timestamp`-колонки читаются как UTC, наш код задаёт зону явно. Чужой код (Hibernate, драйверы) берёт
 * зону ПРОЦЕССА, и при не-UTC молча уезжает на офсет. `make verify` ищет в логе строку «Зона процесса: UTC».
 */
@Component
class TimeZoneStartupCheck {

    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun check() {
        val zone = ZoneId.systemDefault()
        if (zone.normalized() == ZoneOffset.UTC) {
            log.info("Зона процесса: {} — время пишется в UTC, как и ожидает платформа", zone)
            return
        }
        log.warn(
            "ЗОНА ПРОЦЕССА НЕ UTC: {} (сейчас {} против {} UTC). " +
                "Всё, что берёт зону процесса, уедет на офсет этой зоны, а платформа читает эти колонки как UTC. " +
                "Запускайте с -Duser.timezone=UTC (VM options в IDE, JAVA_OPTS в контейнере).",
            zone, LocalDateTime.now(), LocalDateTime.now(ZoneOffset.UTC),
        )
    }
}
