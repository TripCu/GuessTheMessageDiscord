package io.guessauthor.jeopardy.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.guessauthor.jeopardy.rooms.PlayerRecord;
import io.guessauthor.jeopardy.rooms.Room;
import io.guessauthor.jeopardy.rooms.RoomManager;
import io.guessauthor.jeopardy.rooms.RoomManager.RoomCreationResult;
import io.guessauthor.jeopardy.util.HttpUtil;
import io.guessauthor.jeopardy.util.JsonUtil;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public final class RoomsHandler implements HttpHandler {

    private static final int MAX_DB_BYTES = 25 * 1024 * 1024; // 25 MB
    private static final int MAX_ROOM_NAME_LENGTH = 40;

    private final RoomManager roomManager;

    public RoomsHandler(RoomManager roomManager) {
        this.roomManager = roomManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        switch (exchange.getRequestMethod().toUpperCase()) {
            case "POST" -> handleCreate(exchange);
            case "GET" -> handleInfo(exchange);
            default -> HttpUtil.respondWithStatus(exchange, 405, "Method Not Allowed");
        }
    }

    private void handleCreate(HttpExchange exchange) throws IOException {
        String body = readBody(exchange.getRequestBody());
        Map<String, String> params = HttpUtil.parseFormUrlEncoded(body);
        String roomName = sanitizeRoomName(params.get("roomName"));
        String dbBase64 = params.get("dbBase64");

        if (dbBase64 == null || dbBase64.isBlank()) {
            HttpUtil.respondWithStatus(exchange, 400, "Missing dbBase64");
            return;
        }

        byte[] databaseBytes;
        try {
            databaseBytes = Base64.getDecoder().decode(dbBase64);
        } catch (IllegalArgumentException ex) {
            HttpUtil.respondWithStatus(exchange, 400, "Invalid database encoding");
            return;
        }

        if (databaseBytes.length > MAX_DB_BYTES) {
            HttpUtil.respondWithStatus(exchange, 413, "Database file exceeds size limit (25 MB)");
            return;
        }

        try {
            RoomCreationResult result = roomManager.createRoom(roomName, databaseBytes);
            String json = """
                {
                  "roomId": %s,
                  "displayName": %s
                }
                """.formatted(
                JsonUtil.toJsonValue(result.roomId()),
                JsonUtil.toJsonValue(result.displayName())
            );
            HttpUtil.respondJson(exchange, 201, json);
        } catch (SQLException ex) {
            HttpUtil.respondWithStatus(exchange, 400, "Database has no eligible messages");
        } catch (IOException ex) {
            HttpUtil.respondWithStatus(exchange, 500, "Failed to store uploaded database");
        }
    }

    private void handleInfo(HttpExchange exchange) throws IOException {
        Map<String, String> params = HttpUtil.parseQueryParameters(exchange.getRequestURI().getRawQuery());
        String roomId = params.get("roomId");
        if (roomId == null || roomId.isBlank()) {
            HttpUtil.respondWithStatus(exchange, 400, "Missing roomId");
            return;
        }

        Optional<Room> roomOptional = roomManager.room(roomId);
        if (roomOptional.isEmpty()) {
            HttpUtil.respondWithStatus(exchange, 404, "Room not found");
            return;
        }

        Room room = roomOptional.get();
        List<PlayerRecord> leaderboard = room.leaderboard();
        String playersJson = leaderboard.stream()
            .map(record -> """
                {
                  "username": %s,
                  "totalPoints": %s,
                  "currentStreak": %s,
                  "bestStreak": %s
                }
                """.formatted(
                JsonUtil.toJsonValue(record.username()),
                Long.toString(record.snapshot().totalPoints()),
                Integer.toString(record.snapshot().currentStreak()),
                Integer.toString(record.snapshot().bestStreak())
            ))
            .collect(Collectors.joining(","));

        String json = """
            {
              "roomId": %s,
              "displayName": %s,
              "leaderboard": [%s]
            }
            """.formatted(
            JsonUtil.toJsonValue(room.id()),
            JsonUtil.toJsonValue(room.displayName()),
            playersJson
        );

        HttpUtil.respondJson(exchange, 200, json);
    }

    private static String readBody(InputStream inputStream) throws IOException {
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String sanitizeRoomName(String roomName) {
        if (roomName == null) {
            return null;
        }
        String cleaned = roomName.replaceAll("[^A-Za-z0-9 _-]", "").trim().replaceAll("\\s+", " ");
        if (cleaned.length() > MAX_ROOM_NAME_LENGTH) {
            cleaned = cleaned.substring(0, MAX_ROOM_NAME_LENGTH).trim();
        }
        return cleaned.isEmpty() ? null : cleaned;
    }
}
