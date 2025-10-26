FROM eclipse-temurin:21-jdk-jammy

ARG APP_USER=app
RUN useradd -m -U -s /usr/sbin/nologin ${APP_USER}

WORKDIR /app

# App sources & static files
COPY ./src ./src
COPY ./public ./public
COPY ./build_and_run.sh ./build_and_run.sh

# SQLite JDBC jar (rename here if yours differs)
COPY ./sqlite-jdbc-3.50.3.0.jar /app/sqlite-jdbc.jar

# Compile inside the image to /app/output (script auto-detects sources)
RUN chmod +x /app/build_and_run.sh \
 && /app/build_and_run.sh --port 8080 || true

# Runtime configuration
ENV SERVER_PORT=8080 \
    ROOMS_DIR=/rooms \
    MAX_UPLOAD_SIZE=26214400 \
    JAVA_OPTS= \
    TZ=UTC

RUN mkdir -p ${ROOMS_DIR} && chown -R ${APP_USER}:${APP_USER} ${ROOMS_DIR}
VOLUME ["/rooms"]

USER ${APP_USER}
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
  CMD bash -lc 'exec 3<>/dev/tcp/127.0.0.1/8080 && echo -e "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n" >&3 && timeout 2s cat <&3 | grep -q "200" || exit 1'

CMD ["bash", "-lc", "java $JAVA_OPTS -cp /app/sqlite-jdbc.jar:/app/output com.trip.jeopardy.ServerLauncher --port ${SERVER_PORT} --rooms-dir ${ROOMS_DIR}"]