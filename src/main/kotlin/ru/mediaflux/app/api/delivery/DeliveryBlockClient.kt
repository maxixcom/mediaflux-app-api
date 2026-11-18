package ru.mediaflux.app.api.delivery

import org.slf4j.LoggerFactory
import org.springframework.graphql.client.HttpSyncGraphQlClient
import org.springframework.stereotype.Component
import ru.mediaflux.app.api.support.cache.Caches
import java.time.Duration

enum class DeliveryBlockReason { SUSPENDED, TRAFFIC_LIMIT }

/**
 * Закрыта ли отдача владельца — спрашиваем data-service (APPS.md §14 п.8, решение 17.11.2026): правило живёт в
 * одном месте (`DeliveryBlockService`, MF-12) — минус на счёте или исчерпан жёсткий лимит трафика. Повторять его
 * здесь значило бы рано или поздно разойтись со стримером.
 *
 * Кэш 60 с (APPS.md §9, «баланс»). Сбой data-service — отдача считается открытой, с WARN, как у кабинета
 * (`DeliveryStateClient`): последнее слово всё равно за стримером, он закроет поток сам. Сбой не кэшируется.
 */
@Component
class DeliveryBlockClient(
    private val dataServiceGraphQlClient: HttpSyncGraphQlClient,
    caches: Caches,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val cache = caches.plain<Long, State>("delivery_block", maxSize = 50_000, ttl = Duration.ofSeconds(60))

    private data class State(val reason: DeliveryBlockReason?)

    private data class Block(val suspended: Boolean = false, val trafficLimitExceeded: Boolean = false)

    /** Почему закрыта отдача; null — открыта (или data-service не ответил). */
    fun reason(userId: Long): DeliveryBlockReason? {
        cache.getIfPresent(userId)?.let { return it.join().reason }
        val block = fetch(userId) ?: return null
        val state = State(
            when {
                block.suspended -> DeliveryBlockReason.SUSPENDED
                block.trafficLimitExceeded -> DeliveryBlockReason.TRAFFIC_LIMIT
                else -> null
            },
        )
        cache.put(userId, java.util.concurrent.CompletableFuture.completedFuture(state))
        return state.reason
    }

    private fun fetch(userId: Long): Block? =
        runCatching {
            dataServiceGraphQlClient.document(QUERY)
                .variable("userId", userId.toString())
                .retrieveSync("ownerDeliveryBlock")
                .toEntity(Block::class.java)
        }.onFailure {
            log.warn("Состояние отдачи пользователя {} не получено от data-service: {}", userId, it.message)
        }.getOrNull()

    private companion object {
        val QUERY = """
            query OwnerDeliveryBlock(${'$'}userId: ID!) {
              ownerDeliveryBlock(userId: ${'$'}userId) { suspended trafficLimitExceeded }
            }
        """.trimIndent()
    }
}
