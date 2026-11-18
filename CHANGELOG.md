# Changelog

Формат — [Keep a Changelog 1.1.0](https://keepachangelog.com/ru/1.1.0/).

## [Unreleased]

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
