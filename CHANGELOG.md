# Changelog

Формат — [Keep a Changelog 1.1.0](https://keepachangelog.com/ru/1.1.0/).

## [Unreleased]

### MF-90: манифест операций клиента Apple TV 0.3.0 (2026-11-18)

#### Добавлено

- `operations/tvos-0.3.0.json` — 18 операций клиента DodocastTV (Apollo iOS 2.5, `apollo-persisted-query-manifest`).
  Проверено локально в режиме `registered`: операции клиента проходят, чужой запрос — `OPERATION_NOT_REGISTERED`;
  с `Accept: application/graphql-response+json` (так шлёт Apollo 2) отказ приходит HTTP 400 — по GraphQL over HTTP.
  На tst остаётся режим `open` до подготовки выпуска MVP.

### MF-89: состояние зрителя, Э2 (2026-11-18)

#### Добавлено

- Позиция просмотра: `progressReport` в буфер (сброс пачкой раз в 30 с, `final` — сразу, при остановке пода — всё),
  между копиями побеждает поздний отчёт по времени клиента; лимит на устройство — всплеск 3, дальше раз в 5 с.
- «Просмотрено» с 95 % серверной длительности, `markWatched` (снятие сбрасывает позицию).
- `continueWatching`, `playbackStart.resumePositionMs`, поля `Media.progress` и `favorite` у медиа, плейлистов и
  каналов — пачкой, без N+1.
- Избранное: медиа, плейлисты и каналы — `favorites`, `favoriteAdd`, `favoriteRemove`; ряды `Home.continueWatching` и
  `Home.favorites`.

#### Исправлено

- Проверка прав роли на старте: `has_table_privilege` с несколькими правами отвечает «да» на любое из них — теперь
  каждое право проверяется отдельно.

### MF-88: бэкенд приложений, Э1 (2026-11-18)

#### Добавлено

- Сервис `mediaflux-app-api` на порту 17091: Spring Boot 4.1, Kotlin, Java 25, виртуальные потоки с предохранителями
  (пул 10, `connection-timeout` 2 с, `DbGate` на 20 мест).
- Вход: JWT Keycloak, только клиенты приложений (`azp`), пользователь по `sub`; 403 `account_not_ready`,
  `account_disabled`, `client_not_allowed`.
- `GET /server-info?platform=` — сведения о сервере без входа, лимит по адресу клиента.
- Устройства (`t_app_device`): `deviceRegister` с лимитом под advisory-lock, `deviceRename`, `deviceRevoke` (пометка и
  удаление офлайн-сессии в Keycloak), проверка устройства по `sid` на каждом запросе.
- Каталог: `home`, `libraries`, `library`, `media`, `playlists`, `playlist`, `channels`, `search` — только своё и
  активное; кэш Caffeine (списки id, карточки по id, склейка промахов, фоновое обновление). Чтение — частичными
  сущностями `@Immutable` и проекциями Spring Data, пачками без N+1.
- `playbackStart` для медиа и канала: владельческий токен стрима, главы, проверка `ownerDeliveryBlock` в data-service.
- Защита GraphQL: глубина и сложность запроса, зарегистрированные операции по хешу текста (режим `registered`),
  лимиты частоты Bucket4j.
- Проверка прав роли БД на старте (`DbGrantsCheck`), `TimeZoneStartupCheck`.
