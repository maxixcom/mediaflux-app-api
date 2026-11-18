package ru.mediaflux.app.api.config.observability

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration

/**
 * Знакомит Logback-аппендер (`logback-spring.xml`) с OpenTelemetry-SDK — как в console. Без `install` аппендер
 * тихо ничего не делает. Выключатель — общий с остальной доставкой логов.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "management.logging.export.otlp", name = ["enabled"], matchIfMissing = true)
class OpenTelemetryLogbackConfig(
    private val openTelemetry: OpenTelemetry,
) : InitializingBean {

    override fun afterPropertiesSet() {
        OpenTelemetryAppender.install(openTelemetry)
    }
}
