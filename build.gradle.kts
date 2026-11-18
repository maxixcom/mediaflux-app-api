plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	kotlin("plugin.jpa") version "2.3.21"
	id("org.springframework.boot") version "4.1.2"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "ru.mediaflux"
version = "0.0.1-SNAPSHOT"
description = "Backend for MediaFlux apps (TV first): catalog, devices, playback start"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	// OpenTelemetry через Micrometer, как во всех сервисах (без java-агента).
	implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
	// Спаны JDBC, включая ожидание соединения в пуле: при виртуальных потоках это первое, что надо видеть
	// (RUNTIME_CONCURRENCY.md).
	implementation("net.ttddyy.observation:datasource-micrometer-spring-boot:2.2.1")
	// Мост Logback → OTel SDK. Версия подобрана под opentelemetry-api из BOM Boot (как в console):
	// свежая тянет более новый api и падает в экспортёре на NoClassDefFoundError.
	implementation("io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.28.0-alpha")
	// RestClient.Builder как бин — на нём наблюдаемость (`traceparent`) для data-service и Keycloak.
	implementation("org.springframework.boot:spring-boot-starter-restclient")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-graphql")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")

	implementation(libs.graphql.java.extended.scalars)
	implementation(libs.graphql.java.extended.validation)

	// Кэш в памяти копии (APPS.md §9): списки id и карточки, склейка одновременных промахов.
	implementation("com.github.ben-manes.caffeine:caffeine")
	// Лимиты частоты запросов (APPS.md §10). Только ядро, корзины в памяти копии.
	implementation(libs.bucket4j.core)

	// Владельческий токен стрима (STREAM_AUTHORIZATION.md §3.1), как в console. Сериализатор gson —
	// не тянем Jackson 2 рядом с Jackson 3.
	implementation("io.jsonwebtoken:jjwt-api:0.12.6")
	runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
	runtimeOnly("io.jsonwebtoken:jjwt-gson:0.12.6")

	runtimeOnly("io.micrometer:micrometer-registry-prometheus")
	runtimeOnly("org.postgresql:postgresql")

	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-graphql-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// Зона процесса — UTC (корневой CLAUDE.md): беззонные timestamp-колонки читаются как UTC. В IDE —
// VM options -Duser.timezone=UTC, в контейнере — JAVA_OPTS. Проверка на старте — TimeZoneStartupCheck.
tasks.withType<org.springframework.boot.gradle.tasks.run.BootRun> {
	systemProperty("user.timezone", "UTC")
}

tasks.withType<Test> {
	systemProperty("user.timezone", "UTC")
}
