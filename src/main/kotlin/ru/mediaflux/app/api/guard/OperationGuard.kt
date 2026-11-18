package ru.mediaflux.app.api.guard

import com.github.benmanes.caffeine.cache.Caffeine
import graphql.ExecutionResult
import graphql.GraphqlErrorBuilder
import org.springframework.graphql.server.WebGraphQlInterceptor
import org.springframework.graphql.server.WebGraphQlRequest
import org.springframework.graphql.server.WebGraphQlResponse
import org.springframework.graphql.support.DefaultExecutionGraphQlResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import ru.mediaflux.app.api.config.AppApiProperties
import ru.mediaflux.app.api.device.DeviceService
import ru.mediaflux.app.api.security.AppAuthentication
import ru.mediaflux.app.api.support.error.AppErrorType
import ru.mediaflux.app.api.support.error.AppException
import ru.mediaflux.app.api.support.error.ErrorCodes
import ru.mediaflux.app.api.support.error.ErrorMessageExceptionResolver

/**
 * Привратник каждого GraphQL-запроса — до выполнения, одним местом (APPS.md §6, §10):
 *
 * 1. **Зарегистрированные операции** в режиме `registered`: неизвестный текст — `OPERATION_NOT_REGISTERED`.
 * 2. **Лимит частоты** по пользователю и классу операции: листание, «начать просмотр», регистрация устройства.
 * 3. **Устройство** по `sid` токена: без зарегистрированного разрешены только [DEVICELESS], отключённое получает
 *    `DEVICE_REVOKED`. Найденное устройство кладётся в контекст GraphQL ([DEVICE_KEY]) и отмечается заходом.
 *
 * Отказ отдаётся ответом GraphQL с кодом по общему контракту (`extensions.code`), у лимита — ещё и заголовок
 * `Retry-After`. Статус HTTP остаётся 200: так отвечает GraphQL по HTTP с `application/json`, клиент различает отказ
 * по коду.
 */
@Component
class OperationGuard(
    private val props: AppApiProperties,
    private val manifest: OperationManifest,
    private val devices: DeviceService,
    private val limiter: RateLimiter,
) : WebGraphQlInterceptor {

    /** Разбор документа ради корневых полей — кэшируем: зарегистрированных операций немного, они повторяются. */
    private val parsed = Caffeine.newBuilder().maximumSize(2_000).build<Pair<String, String?>, RootFields>()

    override fun intercept(request: WebGraphQlRequest, chain: WebGraphQlInterceptor.Chain): Mono<WebGraphQlResponse> {
        val document = request.document
        if (props.graphql.operations == AppApiProperties.OperationsMode.REGISTERED && !manifest.contains(document)) {
            return reject(request, AppException(AppErrorType.FORBIDDEN, ErrorCodes.OPERATION_NOT_REGISTERED, "Операция не из манифестов (${request.operationName})"))
        }
        val principal = (SecurityContextHolder.getContext().authentication as? AppAuthentication)?.principal
            ?: return chain.next(request) // до сюда без входа не доходят; на всякий случай решает GraphQL
        val key = document to request.operationName
        val roots = parsed.getIfPresent(key)
            ?: RootFields.of(document, request.operationName)?.also { parsed.put(key, it) }
            ?: return chain.next(request)

        limiter.tryConsume(kindOf(roots), principal.user.id.toString())?.let { retryAfter ->
            return reject(
                request,
                AppException(AppErrorType.RATE_LIMITED, ErrorCodes.RATE_LIMITED, "Лимит ${kindOf(roots)} для ${principal.user.id}", mapOf("retryAfterSeconds" to retryAfter)),
                retryAfter,
            )
        }

        val device = devices.current(principal)
        if (!DEVICELESS.containsAll(roots.fields)) {
            if (device == null) {
                return reject(request, AppException(AppErrorType.FORBIDDEN, ErrorCodes.DEVICE_NOT_REGISTERED, "Сессия ${principal.sid} без устройства"))
            }
            if (device.revoked) {
                return reject(request, AppException(AppErrorType.FORBIDDEN, ErrorCodes.DEVICE_REVOKED, "Устройство ${device.publicId} отключено"))
            }
        }
        if (device != null && !device.revoked) {
            devices.touch(device)
            request.configureExecutionInput { _, builder ->
                builder.graphQLContext { it.put(DEVICE_KEY, device) }.build()
            }
        }
        return chain.next(request)
    }

    private fun kindOf(roots: RootFields): RateLimiter.Kind = when {
        roots.mutation && "playbackStart" in roots.fields -> RateLimiter.Kind.PLAYBACK
        roots.mutation && "deviceRegister" in roots.fields -> RateLimiter.Kind.REGISTER
        else -> RateLimiter.Kind.BROWSE
    }

    private fun reject(request: WebGraphQlRequest, ex: AppException, retryAfter: Long? = null): Mono<WebGraphQlResponse> {
        val error = GraphqlErrorBuilder.newError()
            .errorType(ex.type)
            .message(ex.message ?: ex.code)
            .extensions(ErrorMessageExceptionResolver.extensionsOf(ex.code, ex.params))
            .build()
        val result = ExecutionResult.newExecutionResult().addError(error).build()
        val response = WebGraphQlResponse(DefaultExecutionGraphQlResponse(request.toExecutionInput(), result))
        retryAfter?.let { response.responseHeaders.set("Retry-After", it.toString()) }
        return Mono.just(response)
    }

    companion object {
        const val DEVICE_KEY = "appDevice"

        /** Что можно без зарегистрированного устройства: узнать себя, увидеть устройства, подключиться, отключить лишнее. */
        val DEVICELESS = setOf("me", "devices", "deviceRegister", "deviceRevoke")
    }
}
