# syntax=docker/dockerfile:1
# Build 100% em container: não exige JDK 21 nem Gradle no host.

FROM gradle:9.8.2-jdk21 AS build
WORKDIR /app
COPY build.gradle settings.gradle ./
# camada de dependências em cache (só invalida se o build.gradle mudar)
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle dependencies --no-daemon -q > /dev/null 2>&1 || true
COPY src ./src
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle bootJar -x test --no-daemon -q \
    && cp build/libs/*.jar /app/app.jar

FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app app
WORKDIR /app
COPY --from=build --chown=app:app /app/app.jar ./app.jar
USER app:app
EXPOSE 8080 8081
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
    CMD wget -q -O - http://127.0.0.1:8081/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
