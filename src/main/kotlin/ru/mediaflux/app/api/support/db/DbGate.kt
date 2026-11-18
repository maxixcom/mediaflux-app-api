package ru.mediaflux.app.api.support.db

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import ru.mediaflux.app.api.config.AppApiProperties
import ru.mediaflux.app.api.support.error.AppErrorType
import ru.mediaflux.app.api.support.error.AppException
import ru.mediaflux.app.api.support.error.ErrorCodes
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Явный ограничитель одновременных обращений к БД (RUNTIME_CONCURRENCY.md, предохранитель виртуальных потоков).
 *
 * Виртуальных потоков может быть тысячи, а соединений в пуле — десять. Без ограничителя все они встанут в
 * очередь Hikari и будут ждать до `connection-timeout`, держа запросы клиентов; с ним лишние получают быстрый
 * отказ `SERVER_BUSY`, а приложение повторяет запрос позже. Кэш снимает с БД основную массу чтений — сюда
 * доходят только промахи и записи.
 */
@Component
class DbGate(props: AppApiProperties, meterRegistry: MeterRegistry) {

    private val permits = props.dbGate.permits
    private val waitMs = props.dbGate.wait.toMillis()
    private val semaphore = Semaphore(permits, true)

    init {
        Gauge.builder("app_api.db_gate.available") { semaphore.availablePermits().toDouble() }
            .description("Свободные места в ограничителе обращений к БД")
            .register(meterRegistry)
    }

    fun <T> run(block: () -> T): T {
        if (!semaphore.tryAcquire(waitMs, TimeUnit.MILLISECONDS)) {
            throw AppException(
                AppErrorType.UNAVAILABLE, ErrorCodes.SERVER_BUSY,
                "Все $permits мест обращения к БД заняты дольше ${waitMs} мс",
                mapOf("retryAfterSeconds" to 1),
            )
        }
        try {
            return block()
        } finally {
            semaphore.release()
        }
    }
}
