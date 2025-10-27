FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
COPY public ./public

RUN mvn -B package

FROM eclipse-temurin:21-jre

ENV SERVER_PORT=8080 \
    JAVA_OPTS="" \
    ROOMS_DIR=/app/rooms \
    WEB_ROOT=/app/public \
    TZ=UTC

WORKDIR /app

COPY --from=build /workspace/target/jeopardy-server-1.0.0.jar /app/app.jar
COPY public ./public

RUN mkdir -p "${ROOMS_DIR}"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar --port ${SERVER_PORT} --rooms-dir ${ROOMS_DIR} --web-root ${WEB_ROOT}"]
