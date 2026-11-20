package ru.mediaflux.app.api.catalog

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Пагинация как в кабинете. Сортировку выбирает сервер. */
data class PageInput(val page: Int? = 0, val size: Int? = 20)

data class Connection<T>(val nodes: List<T>, val totalCount: Int)

/** Страница из списка id: срез по номеру и размеру. */
fun <T> List<T>.page(page: PageInput?): List<T> {
    val size = (page?.size ?: 20).coerceIn(1, 100)
    val from = (page?.page ?: 0).coerceAtLeast(0).toLong() * size
    if (from >= this.size) return emptyList()
    return subList(from.toInt(), minOf(this.size, from.toInt() + size))
}

data class MediaView(
    val id: String,
    val code: String,
    val title: String,
    val description: String?,
    val durationMs: Long?,
    val createdAt: OffsetDateTime,
    val libraryId: String,
    val coverUrl: String?,
    val previewManifestUrl: String?,
)

data class LibraryView(
    val id: String,
    val title: String,
    val description: String?,
    val itemCount: Int,
    val coverUrl: String?,
) {
    val libraryId: Long get() = id.toLong()
}

data class PlaylistView(
    val id: String,
    val code: String,
    val title: String,
    val description: String?,
    val itemCount: Int,
    val coverUrl: String?,
) {
    val playlistId: Long get() = id.toLong()
}

data class ChannelView(
    val id: String,
    val code: String,
    val title: String,
    val description: String?,
    val onAir: Boolean,
    val coverUrl: String?,
)

/** Главный экран; поля собираются по запросу (`@SchemaMapping`), чтобы не грузить то, что клиент не спросил. */
data class HomeView(val userId: Long)

data class ShelfView(val library: LibraryView, val items: List<MediaView>)

fun LocalDateTime.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)
