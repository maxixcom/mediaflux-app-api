package ru.mediaflux.app.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import java.util.TimeZone

@SpringBootApplication
@ConfigurationPropertiesScan
class AppApiApplication

fun main(args: Array<String>) {
	// Подстраховка для чужого кода (драйверы, библиотеки). Наш код задаёт зону ЯВНО — LocalDateTime.now(ZoneOffset.UTC):
	// на одну эту строку опираться нельзя, ловили дважды (корневой CLAUDE.md, «Зона процесса — UTC»).
	TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
	runApplication<AppApiApplication>(*args)
}
