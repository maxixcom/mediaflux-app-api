# CLAUDE.md

Бэкенд наших приложений — первым идёт Apple TV (`mediaflux-app-tvos`). GraphQL для каталога своих материалов,
устройств и «начать просмотр», плюс REST `GET /server-info`. Порт **17091**.

Устройство и решения — `mediaflux-docs/docs/APPS.md` (ветка `feature/mf-86-apps`): §5 вход, §5.1 сведения о сервере,
§6 устройства, §8 воспроизведение, §9 кэш и защита БД, §10 ограничения GraphQL. Задачи — MF-86, этап Э1 — MF-88.

## Что делает

- **Вход** — JWT Keycloak как resource server. Принимаются только токены наших клиентов приложений (`azp` из
  `mediaflux.app-api.clients`). Пользователь — по `sub` = `t_user.external_id`; app-api пользователей НЕ создаёт:
  нет записи — 403 `account_not_ready`, заблокирован — 403 `account_disabled`.
- **Устройство = установка** (`t_app_device`). На каждом запросе оно находится по `sid` токена. Без
  зарегистрированного устройства доступны только `me`, `devices`, `deviceRegister`, `deviceRevoke`. Лимит — настройка
  сервиса (Э1), тариф — в Э4. Отзыв в два шага: наша пометка и удаление офлайн-сессии в Keycloak.
- **Каталог** — только своё и только активное: правило `CatalogQueries.PLAYABLE` повторяет кабинет и стример.
- **«Начать просмотр»** — владельческий токен стрима (общий секрет со стримером), блокировку отдачи спрашивает у
  data-service (`ownerDeliveryBlock`), правило у себя не повторяет.

## Как устроено

```
устройство ─→ ingress ─→ app-api ─┬─→ PostgreSQL (своя роль app_api: чтение каталога, запись своих t_app_*)
                                  ├─→ data-service (ownerDeliveryBlock)
                                  └─→ Keycloak admin (удаление сессии при отзыве)
адреса потоков и картинок в ответах ведут на стример — мимо app-api
```

- `guard/OperationGuard` — привратник КАЖДОГО GraphQL-запроса до выполнения: зарегистрированные операции, лимит
  частоты (Bucket4j), устройство. Новое корневое поле, которому не нужно устройство, — в `DEVICELESS`.
- `entity/` — частичные сущности `@Immutable` (только нужные колонки; `ddl-auto: validate` сверяет их на старте) и
  свои `App*Entity` (устройства, позиция, избранное, недавние поиски) — только в них пишем. Статусы — строками:
  новое значение в чужом сервисе не роняет чтение.
- `repository/` — Spring Data с интерфейсными проекциями и `@Query`; правило видимости — `CatalogJpql.PLAYABLE`.
  **N+1 не допускаем:** пачка карточек, отпечатки обложек, версии программ каналов — одним запросом на весь набор id.
- `catalog/` — `CatalogQueries` (чтение поверх репозиториев через `DbGate`), `CatalogService` (кэш), `CatalogController`.
- `device/` — сущность и сервис устройств, `me`.
- `support/cache/Caches` — фабрика кэшей Caffeine (асинхронные, со склейкой промахов, метрики `cache_*`).
- `support/db/DbGate` — семафор обращений к БД; `DbGrantsCheck` — проверка прав роли на старте.

## Правила, которые легко нарушить

- **Виртуальные потоки только с предохранителями** (`RUNTIME_CONCURRENCY.md`): пул 10, `connection-timeout` 2 с,
  `DbGate` на 20 мест. Всё обращение к БД — через `DbGate`. Флаг `spring.threads.virtual.enabled` задан в yaml, через
  env он не переопределяется.
- **Ключ кэша с данными пользователя всегда содержит его id** (`UserKey`, тест `CacheOwnerKeyTest`).
- **Новая таблица — в двух местах:** в `DbGrantsCheck` и в `mediaflux-deploy/schema/postgres-init/003-app-api-db.sh`.
  Права роли выдаёт скрипт деплоя, не Liquibase (решение 17.11.2026). Забытое ловится на старте.
- **Адреса внутри кластера — в base деплоя**, в оверлее стенда только его публичные имена.
- Время — `LocalDateTime.now(ZoneOffset.UTC)`, наружу — `OffsetDateTime` в UTC. `TimeZoneStartupCheck` обязателен.
- Ошибки — `AppException(code, params)` по контракту `ERROR_HANDLING.md`; новый код — в `ErrorCodes` и в реестр.

## Запуск

- Локально нужна роль БД: `make app-api-db` в `mediaflux-env` (после миграций), клиенты Keycloak —
  `mediaflux-deploy/keycloak/app-clients.sh` в локальный контейнер.
- IDEA: VM options `-Duser.timezone=UTC`, Active profiles `dev`.
- Тесты — юнитовые, без поднятия контекста: `./gradlew test`.
- Образ — `make image` / `make push` (общий шаблон), раскатка — `mediaflux-deploy`, `make update SERVICES=app-api`.
