package ru.mediaflux.app.api.guard

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.slf4j.LoggerFactory
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest

/**
 * Зарегистрированные операции (APPS.md §10). Релизный клиент собирается с манифестом операций Apollo
 * (`operationManifest`), копия кладётся в `src/main/resources/operations/<платформа>-<версия>.json`. Сервер в режиме
 * `registered` принимает только запросы, чей ТЕКСТ даёт sha256 из манифестов: клиент шлёт запрос как обычно,
 * сверка — по хешу, поэтому особый формат запроса не нужен.
 *
 * Манифестов несколько: старые версии приложения в магазине обновляются не сразу, их операции должны работать.
 */
@Component
class OperationManifest(mapper: JsonMapper) {

    private val log = LoggerFactory.getLogger(javaClass)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ManifestFile(val operations: List<Operation> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Operation(val name: String? = null, val body: String = "")

    private val hashes: Set<String> = PathMatchingResourcePatternResolver()
        .getResources("classpath*:operations/*.json")
        .flatMap { res ->
            val file = res.inputStream.use { mapper.readValue(it, ManifestFile::class.java) }
            log.info("Манифест операций {}: {} операций", res.filename, file.operations.size)
            file.operations.map { sha256(it.body) }
        }
        .toSet()

    fun contains(document: String): Boolean = sha256(document) in hashes

    val size: Int get() = hashes.size

    companion object {
        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
