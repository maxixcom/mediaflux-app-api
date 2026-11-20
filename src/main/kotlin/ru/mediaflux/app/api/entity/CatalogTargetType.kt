package ru.mediaflux.app.api.entity

/**
 * Цель в состоянии зрителя — избранное (MF-89) и недавние поиски (MF-114): вид сущности каталога. Хранится строкой
 * (`target_type`): новые виды целей не ломают чтение.
 */
enum class CatalogTargetType { MEDIA, PLAYLIST, CHANNEL }
