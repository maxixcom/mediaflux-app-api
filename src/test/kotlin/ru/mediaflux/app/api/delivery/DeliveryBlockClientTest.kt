package ru.mediaflux.app.api.delivery

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.graphql.client.HttpSyncGraphQlClient
import ru.mediaflux.app.api.support.cache.Caches
import kotlin.test.assertNull

class DeliveryBlockClientTest {

    @Test
    fun `сбой data-service — отдача открыта и сбой не кэшируется`() {
        val graphQl = mock<HttpSyncGraphQlClient>(defaultAnswer = Mockito.RETURNS_DEEP_STUBS)
        whenever(graphQl.document(any())).thenThrow(IllegalStateException("data-service недоступен"))
        val client = DeliveryBlockClient(graphQl, Caches(SimpleMeterRegistry()))

        assertNull(client.reason(1))
        assertNull(client.reason(1))
        // Оба раза пошли в data-service: ответ «не знаю» не запомнен на минуту.
        verify(graphQl, times(2)).document(any())
    }
}
