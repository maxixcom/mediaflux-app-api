package ru.mediaflux.app.api.playback

import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.stereotype.Controller
import ru.mediaflux.app.api.catalog.CatalogQueries
import ru.mediaflux.app.api.catalog.CatalogService
import ru.mediaflux.app.api.delivery.DeliveryBlockClient
import ru.mediaflux.app.api.security.CurrentPrincipal
import ru.mediaflux.app.api.support.error.AppErrorType
import ru.mediaflux.app.api.support.error.AppException
import ru.mediaflux.app.api.support.error.ErrorCodes
import ru.mediaflux.app.api.viewer.ProgressService
import java.time.OffsetDateTime
import java.time.ZoneOffset

data class PlaybackStartInput(val mediaId: Long? = null, val channelId: Long? = null)

data class ChapterView(val startMs: Long, val title: String)

data class PlaybackView(
    val url: String,
    val expiresAt: OffsetDateTime,
    val title: String,
    val durationMs: Long?,
    val chapters: List<ChapterView>,
    val coverUrl: String?,
    val previewManifestUrl: String?,
    /** С какой позиции продолжить (MF-89); null — с начала. */
    val resumePositionMs: Long? = null,
)

/**
 * «Начать просмотр» (APPS.md §8). Токен выдаётся один раз на сеанс — отдельной операцией, а не полем карточки.
 *
 * Здесь только то, что нужно сказать ДО старта понятными словами: своё ли и видно ли, вещает ли канал, открыт ли
 * тариф на каналы, не закрыта ли отдача. Остальное (тумблеры дорожек, потолок качеств, минус на счёте во время
 * просмотра) стример проверяет сам на каждом запросе.
 */
@Controller
class PlaybackController(
    private val catalog: CatalogService,
    private val queries: CatalogQueries,
    private val minter: StreamTokenMinter,
    private val delivery: DeliveryBlockClient,
    private val progress: ProgressService,
) {

    @MutationMapping
    fun playbackStart(@Argument input: PlaybackStartInput): PlaybackView {
        val user = CurrentPrincipal.get().user
        if ((input.mediaId == null) == (input.channelId == null)) {
            throw AppException(AppErrorType.BAD_INPUT, ErrorCodes.BAD_INPUT, "Нужно ровно одно из mediaId и channelId")
        }
        val view = input.mediaId?.let { mediaPlayback(user.id, it) } ?: channelPlayback(user.id, user.channelsEnabled, input.channelId!!)
        delivery.reason(user.id)?.let {
            throw AppException(AppErrorType.FORBIDDEN, ErrorCodes.DELIVERY_BLOCKED, "Отдача владельца ${user.id} закрыта: $it", mapOf("reason" to it))
        }
        return view
    }

    private fun mediaPlayback(userId: Long, mediaId: Long): PlaybackView {
        val card = catalog.media(userId, listOf(mediaId)).firstOrNull() ?: throw AppException.notFound("Медиа $mediaId")
        val token = minter.mint(card.code)
        val chapters = if (card.chaptersEnabled) queries.chapters(card.id).map { ChapterView(it.startMs, it.title) } else emptyList()
        return PlaybackView(
            // Телевизор берёт fMP4 (APPS.md §8): SAMPLE-AES в fMP4 AVPlayer играет нативно.
            url = "${catalog.streamerBase()}/s/${token.value}/hls/mp4/${card.code}/master.m3u8",
            expiresAt = token.expiresAt.atOffset(ZoneOffset.UTC),
            title = card.title,
            durationMs = card.durationMs,
            chapters = chapters,
            coverUrl = catalog.coverUrl(card),
            previewManifestUrl = catalog.previewManifestUrl(card),
            resumePositionMs = progress.resumePosition(userId, card.id),
        )
    }

    private fun channelPlayback(userId: Long, channelsEnabled: Boolean, channelId: Long): PlaybackView {
        if (!channelsEnabled) {
            throw AppException(AppErrorType.FORBIDDEN, ErrorCodes.CHANNELS_NOT_IN_TARIFF, "Каналы закрыты тарифом пользователя $userId")
        }
        // Свежий расчёт, мимо кэша списка: «в эфире» меняется по времени.
        val channel = queries.channels(userId, channelId = channelId).firstOrNull() ?: throw AppException.notFound("Канал $channelId")
        if (!channel.onAir) {
            throw AppException(AppErrorType.FORBIDDEN, ErrorCodes.CHANNEL_OFF_AIR, "Канал $channelId не в эфире")
        }
        val token = minter.mint(channel.code)
        return PlaybackView(
            url = "${catalog.streamerBase()}/s/${token.value}/ch/mp4/${channel.code}/master.m3u8",
            expiresAt = token.expiresAt.atOffset(ZoneOffset.UTC),
            title = channel.title,
            durationMs = null,
            chapters = emptyList(),
            coverUrl = null,
            previewManifestUrl = null,
        )
    }
}
