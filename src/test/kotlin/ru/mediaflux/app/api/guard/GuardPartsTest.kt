package ru.mediaflux.app.api.guard

import org.junit.jupiter.api.Test
import ru.mediaflux.app.api.config.AppApiProperties
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuardPartsTest {

    @Test
    fun `корневые поля собираются и из фрагментов, служебные отбрасываются`() {
        val doc = """
            query Home { __typename ...Top me { id } }
            fragment Top on Query { home { recent { id } } devices { id } }
        """.trimIndent()
        val roots = assertNotNull(RootFields.of(doc, "Home"))
        assertFalse(roots.mutation)
        assertEquals(setOf("me", "home", "devices"), roots.fields)
    }

    @Test
    fun `мутация и выбор операции по имени`() {
        val doc = "query A { me { id } } mutation B { playbackStart(input: {mediaId: 1}) { url } }"
        val roots = assertNotNull(RootFields.of(doc, "B"))
        assertTrue(roots.mutation)
        assertEquals(setOf("playbackStart"), roots.fields)
        assertNull(RootFields.of(doc, null), "две операции без имени — решает GraphQL")
        assertNull(RootFields.of("{ broken", null))
    }

    @Test
    fun `без устройства можно только узнать себя и управлять устройствами`() {
        assertTrue(OperationGuard.DEVICELESS.containsAll(setOf("me", "deviceRegister")))
        assertFalse(OperationGuard.DEVICELESS.containsAll(setOf("me", "home")))
    }

    @Test
    fun `манифест принимает только операции, чей текст в нём`() {
        val manifest = OperationManifest(JsonMapper.builder().addModule(KotlinModule.Builder().build()).build())
        // src/test/resources/operations/test-manifest.json
        assertEquals(1, manifest.size)
        assertTrue(manifest.contains("query Me { me { id } }"))
        assertFalse(manifest.contains("query Me { me { id email } }"))
    }

    @Test
    fun `лимит листания — всплеск, потом отказ со сроком повтора`() {
        val limiter = RateLimiter(AppApiProperties(rateLimits = AppApiProperties.RateLimits(browsePerSecond = 1, browseBurst = 3)))
        repeat(3) { assertNull(limiter.tryConsume(RateLimiter.Kind.BROWSE, "u1")) }
        val retry = assertNotNull(limiter.tryConsume(RateLimiter.Kind.BROWSE, "u1"))
        assertTrue(retry >= 1)
        assertNull(limiter.tryConsume(RateLimiter.Kind.BROWSE, "u2"), "корзины у пользователей свои")
    }
}
