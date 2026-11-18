FROM gradle:9.4.1-jdk25-alpine AS build
WORKDIR /workspace

COPY settings.gradle.kts build.gradle.kts gradle.properties* ./
COPY gradle ./gradle
RUN gradle --no-daemon dependencies > /dev/null 2>&1 || true

COPY src ./src
RUN gradle --no-daemon clean bootJar -x test

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

RUN addgroup -S app && adduser -S app -G app
USER app

COPY --from=build /workspace/build/libs/*.jar /app/app.jar

EXPOSE 17091
# Зона процесса — UTC (корневой CLAUDE.md). Куча задаётся в ConfigMap деплоя (MF-56), не здесь.
ENV TZ=UTC
ENV JAVA_OPTS="-Duser.timezone=UTC"
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]
