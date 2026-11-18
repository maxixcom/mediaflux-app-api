package ru.mediaflux.app.api.support.error

import graphql.GraphQLError
import graphql.GraphqlErrorBuilder
import graphql.schema.DataFetchingEnvironment
import org.slf4j.LoggerFactory
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.stereotype.Component

/**
 * [AppException] → ошибка GraphQL с `extensions.code` и `extensions.params`, как в console. Остальные исключения
 * отдаёт Spring GraphQL: `INTERNAL_ERROR` без текста, стек — в лог.
 */
@Component
class ErrorMessageExceptionResolver : DataFetcherExceptionResolverAdapter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun resolveToSingleError(ex: Throwable, env: DataFetchingEnvironment): GraphQLError? {
        val app = ex as? AppException ?: return null
        log.info("GraphQL {} [{}] at {}: {}", app.type, app.code, env.executionStepInfo.path, app.message)
        return GraphqlErrorBuilder.newError(env)
            .errorType(app.type)
            .message(app.message ?: app.code)
            .extensions(extensionsOf(app.code, app.params))
            .build()
    }

    companion object {
        /** `extensions` по контракту: код и параметры строками (null выкидываем). */
        fun extensionsOf(code: String, params: Map<String, Any?>): Map<String, Any> = buildMap {
            put("code", code)
            params.filterValues { it != null }
                .takeIf { it.isNotEmpty() }
                ?.let { put("params", it.mapValues { (_, v) -> v.toString() }) }
        }
    }
}
