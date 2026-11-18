package ru.mediaflux.app.api.support.db

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

/**
 * Проверка прав роли на старте.
 *
 * Роль `app_api` и её права заводит скрипт деплоя (`mediaflux-deploy/schema/postgres-init/003-app-api-db.sh`),
 * а не миграции. Значит, новая таблица в коде без строки в скрипте — это отказ «permission denied» на первом же
 * запросе к ней, причём только у пользователя, которому повезло на него попасть. Здесь он громкий и сразу:
 * сервис не стартует и говорит, какую таблицу и чем исправлять.
 *
 * Список — ровно тот же, что в скрипте. Меняете один — меняйте второй.
 */
@Component
class DbGrantsCheck(private val jdbc: JdbcClient) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val missing = buildList {
            READ_TABLES.forEach { if (!has("has_table_privilege(current_user, :t, 'SELECT')", it)) add("SELECT $it") }
            WRITE_TABLES.forEach { (table, privileges) ->
                if (!has("has_table_privilege(current_user, :t, '$privileges')", table)) add("$privileges $table")
            }
            SEQUENCES.forEach { if (!has("has_sequence_privilege(current_user, :t, 'USAGE')", it)) add("USAGE $it") }
        }
        if (missing.isNotEmpty()) {
            error(
                "У роли БД нет прав: ${missing.joinToString()}. Выдать: локально `make app-api-db` в mediaflux-env, " +
                    "на стенде `make stand-app-api-db` в mediaflux-deploy; новую таблицу — сначала в " +
                    "schema/postgres-init/003-app-api-db.sh",
            )
        }
        log.info("Права роли БД на месте: {} таблиц на чтение, {} на запись", READ_TABLES.size, WRITE_TABLES.map { it.first }.distinct().size)
    }

    private fun has(expr: String, obj: String): Boolean =
        jdbc.sql("select $expr").param("t", obj).query(Boolean::class.java).single()

    companion object {
        val READ_TABLES = listOf(
            "t_user", "t_resource_group", "t_project", "t_media", "t_media_track", "t_media_file", "t_media_chapter",
            "t_playlist", "t_playlist_item", "t_channel", "t_channel_program_version", "t_channel_item", "t_setting",
        )
        /** Свои таблицы и нужные на них права. `has_table_privilege` с несколькими правами — «хотя бы одно», поэтому
         *  проверяем каждое отдельно (`privileges` разворачивается ниже). */
        val WRITE_TABLES = listOf(
            "t_app_device" to "SELECT, INSERT, UPDATE",
            "t_app_watch_progress" to "SELECT, INSERT, UPDATE",
            "t_app_favorite" to "SELECT, INSERT, DELETE",
        ).flatMap { (t, p) -> p.split(", ").map { t to it } }
        val SEQUENCES = listOf("seq_app_device_id", "seq_app_watch_progress_id", "seq_app_favorite_id")
    }
}
