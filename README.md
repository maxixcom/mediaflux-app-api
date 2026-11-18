# mediaflux-app-api

Бэкенд приложений MediaFlux (наружу — dodocast): каталог своих материалов, устройства, «начать просмотр».
Первый клиент — Apple TV (`mediaflux-app-tvos`). Порт 17091.

- `GET /server-info?platform=tvos` — анонимно: стенд, Keycloak и клиент платформы, адрес GraphQL, минимальная версия.
- `POST /graphql` — с токеном Keycloak клиента приложения. Схема — `src/main/resources/graphql/`, копия —
  `mediaflux-docs/docs/app-api/graphql/`.

Устройство и решения — `mediaflux-docs/docs/APPS.md`, правила работы с кодом — [CLAUDE.md](CLAUDE.md).

```bash
./gradlew test
./gradlew bootRun --args='--spring.profiles.active=dev'
```
