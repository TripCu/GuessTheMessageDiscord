# Dockerfile
FROM eclipse-temurin:21-jdk-jammy

# Run as non-root for security
ARG APP_USER=app
RUN useradd -m -U -s /usr/sbin/nologin ${APP_USER}

WORKDIR /app

# App sources & static files
COPY ./src ./src
COPY ./public ./public
COPY ./build_and_run.sh ./build_and_run.sh

# SQLite JDBC driver (rename here if your file name differs)
COPY ./sqlite-jdbc-3.50.3.0.jar /app/sqlite-jdbc.jar

# ---- Build: compile Java classes into /app/output (no server start here) ----
RUN bash -lc '\
  mkdir -p /app/output && \
  mapfile -t SRC < <(find /app/src -type f -name "*.java" | sort) && \
  javac -encoding UTF-8 -g -cp /app/sqlite-jdbc.jar -d /app/output "${SRC[@]}" \
'

# Runtime env
ENV SERVER_PORT=8080 \
    MAX_UPLOAD_SIZE=26214400 \
    JAVA_OPTS= \
    TZ=UTC

# Create runtime data dir the app writes to and give ownership to app user
RUN mkdir -p /app/rooms && chown -R ${APP_USER}:${APP_USER} /app

USER ${APP_USER}
EXPOSE 8080

# Basic healthcheck (optional)
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
  CMD bash -lc 'exec 3<>/dev/tcp/127.0.0.1/8080 && echo -e "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n" >&3 && timeout 2s cat <&3 | grep -q "200" || exit 1'

# Start the server; note we do NOT pass --rooms-dir (your app doesn’t accept it)
CMD ["bash", "-lc", "java $JAVA_OPTS -cp /app/sqlite-jdbc.jar:/app/output com.trip.jeopardy.ServerLauncher --port ${SERVER_PORT}"]