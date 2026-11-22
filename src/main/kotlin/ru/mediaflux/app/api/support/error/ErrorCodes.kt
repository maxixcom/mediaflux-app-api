package ru.mediaflux.app.api.support.error

/**
 * Коды ошибок app-api (`extensions.code`). Фразу на языке пользователя (RU/EN) собирает приложение по коду и
 * параметрам. Новый код — сюда, в реестр `mediaflux-docs/docs/ERROR_HANDLING.md` и в словари клиента.
 *
 * Отказы ВХОДА (до GraphQL) приходят не кодом, а телом 403 `{"error": "..."}`: `account_disabled`,
 * `account_not_ready`, `client_not_allowed` — см. AppAuthenticationEntryPoint.
 */
object ErrorCodes {
    /** Объекта нет — или он не свой, или скрыт (выключен, закрыт модерацией, не готов). */
    const val NOT_FOUND = "NOT_FOUND"

    /** Некорректный ввод. */
    const val BAD_INPUT = "BAD_INPUT"

    /** Устройство не зарегистрировано: сначала deviceRegister. */
    const val DEVICE_NOT_REGISTERED = "DEVICE_NOT_REGISTERED"

    /** Устройство отключено — нужен новый вход. */
    const val DEVICE_REVOKED = "DEVICE_REVOKED"

    /** Подключено максимум устройств. Параметр: `limit`. */
    const val DEVICE_LIMIT_REACHED = "DEVICE_LIMIT_REACHED"

    /** Отдача владельца закрыта. Параметр: `reason` — SUSPENDED | TRAFFIC_LIMIT. */
    const val DELIVERY_BLOCKED = "DELIVERY_BLOCKED"

    /** Канал сейчас не вещает. */
    const val CHANNEL_OFF_AIR = "CHANNEL_OFF_AIR"

    /** Каналы закрыты тарифом. */
    const val CHANNELS_NOT_IN_TARIFF = "CHANNELS_NOT_IN_TARIFF"

    /** Приложения закрыты тарифом (флаг `appsEnabled`, MF-91): работают только `me` и `deviceRevoke`. */
    const val APPS_NOT_IN_TARIFF = "APPS_NOT_IN_TARIFF"

    /** Слишком часто. Параметр: `retryAfterSeconds`. */
    const val RATE_LIMITED = "RATE_LIMITED"

    /** Операция не из списка зарегистрированных — версия приложения не подходит этому серверу. */
    const val OPERATION_NOT_REGISTERED = "OPERATION_NOT_REGISTERED"

    /** Сервер перегружен, повторить позже. Параметр: `retryAfterSeconds`. */
    const val SERVER_BUSY = "SERVER_BUSY"

    /** Платформа не поддерживается этим сервером (`/server-info`). */
    const val PLATFORM_NOT_SUPPORTED = "PLATFORM_NOT_SUPPORTED"
}
