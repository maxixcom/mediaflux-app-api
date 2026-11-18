package ru.mediaflux.app.api

import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeDefinitionRegistry
import org.junit.jupiter.api.Test
import ru.mediaflux.app.api.config.GraphQlConfig
import org.junit.jupiter.api.assertDoesNotThrow
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * SDL разбирается и собирается в схему. Схема не компилируется вместе с кодом, и опечатка в ней доживает до старта
 * сервиса (в console это однажды уронило старт при зелёных тестах). Здесь сверх разбора проверяется и связность
 * типов — сборкой исполняемой схемы с теми же скалярами и директивами, что в GraphQlConfig.
 */
class GraphQlSchemaTest {

    @Test
    fun `SDL разбирается и собирается в схему`() {
        val dir = Path.of("src/main/resources/graphql")
        val files = Files.list(dir).use { s -> s.filter { it.toString().endsWith(".graphqls") }.toList().sorted() }
        assertTrue(files.isNotEmpty(), "в $dir нет .graphqls")

        val registry = TypeDefinitionRegistry()
        files.forEach { file ->
            registry.merge(assertDoesNotThrow({ "не разобрался ${file.fileName}" }) { SchemaParser().parse(file.toFile()) })
        }
        // Та же проводка, что в сервисе (скаляры, директивы, распознавание типов объединения).
        val builder = RuntimeWiring.newRuntimeWiring()
        GraphQlConfig().runtimeWiringConfigurer().configure(builder)
        val wiring = builder.build()
        val schema = SchemaGenerator().makeExecutableSchema(registry, wiring)
        listOf("me", "devices", "home", "libraries", "library", "media", "playlists", "playlist", "channels", "search",
            "continueWatching", "favorites")
            .forEach { assertTrue(schema.queryType.getFieldDefinition(it) != null, "нет Query.$it") }
        listOf("deviceRegister", "deviceRename", "deviceRevoke", "playbackStart",
            "progressReport", "markWatched", "favoriteAdd", "favoriteRemove")
            .forEach { assertTrue(schema.mutationType.getFieldDefinition(it) != null, "нет Mutation.$it") }
    }
}
