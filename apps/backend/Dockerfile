# syntax=docker/dockerfile:1
# Build 100% em container: não exige JDK 21 nem Gradle no host.

FROM gradle:8.14.3-jdk21 AS build
WORKDIR /app
COPY build.gradle settings.gradle ./
# camada de dependências em cache (só invalida se o build.gradle mudar)
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle dependencies --no-daemon -q > /dev/null 2>&1 || true
COPY src ./src
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle bootJar -x test --no-daemon -q \
    && cp build/libs/*.jar /app/app.jar

# AppCDS (ADR-021): uma subida de treino grava num arquivo as classes já carregadas. A JVM apenas o mapeia na memória,
# o que reduz a memória que não pode ser descartada e acelera a subida. O estágio usa a mesma imagem e o mesmo caminho
# do runtime, porque a JVM confere os dois. A subida não precisa de banco nem de Redis.
FROM eclipse-temurin:21-jre-alpine AS cds
RUN apk add --no-cache openssl
WORKDIR /app
COPY --from=build /app/app.jar /tmp/boot.jar
RUN java -Djarmode=tools -jar /tmp/boot.jar extract --destination /tmp/extracted \
    && cp -r /tmp/extracted/lib ./lib \
    && cp /tmp/extracted/boot.jar ./app.jar
# a chave é descartável: serve só para a aplicação subir no treino e não sai deste estágio
RUN JWT_PRIVATE_KEY_B64="$(openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 2>/dev/null | openssl pkcs8 -topk8 -nocrypt -outform DER | base64 -w0)" \
    java -XX:ArchiveClassesAtExit=app.jsa -XX:+UseSerialGC -Xlog:cds=off -Xlog:cds+dynamic=off \
      -Dspring.context.exit=onRefresh \
      -Dspring.flyway.enabled=false \
      -Dspring.jpa.hibernate.ddl-auto=none \
      -Dspring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect \
      -Dspring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
      -jar app.jar > /tmp/training.log 2>&1 \
    || { tail -c 4000 /tmp/training.log; exit 1; }
RUN test -s app.jsa

FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app app
WORKDIR /app
COPY --from=cds --chown=app:app /app ./
USER app:app
EXPOSE 8080 8081
# o compose define os limites de memória; isto vale para quem rodar a imagem sozinha
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=15s --timeout=3s --start-period=120s --retries=5 \
    CMD wget -q -O - http://127.0.0.1:8081/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-XX:SharedArchiveFile=/app/app.jsa", "-jar", "/app/app.jar"]
