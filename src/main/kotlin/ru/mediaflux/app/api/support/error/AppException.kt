package ru.mediaflux.app.api.support.error

import graphql.ErrorClassification

/** Грубая классификация ошибки (`errorType` в ответе GraphQL). */
enum class AppErrorType : ErrorClassification {
    NOT_FOUND,
    BAD_INPUT,
    FORBIDDEN,
    LIMIT,
    RATE_LIMITED,
    UNAVAILABLE,
}

/**
 * Отказ по правилам: стабильный [code] и [params] для фразы в приложении (ERROR_HANDLING.md). Текст исключения —
 * технический, для лога и devtools; пользователю фразу собирает приложение по коду.
 */
class AppException(
    val type: AppErrorType,
    val code: String,
    message: String,
    val params: Map<String, Any?> = emptyMap(),
) : RuntimeException(message) {

    companion object {
        fun notFound(what: String) = AppException(AppErrorType.NOT_FOUND, ErrorCodes.NOT_FOUND, "$what не найден")
    }
}
