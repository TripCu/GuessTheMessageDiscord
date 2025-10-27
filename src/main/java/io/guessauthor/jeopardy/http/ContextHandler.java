package io.guessauthor.jeopardy.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.guessauthor.jeopardy.GameEngine.ContextResponse;
import io.guessauthor.jeopardy.GameEngine.ContextStatus;
import io.guessauthor.jeopardy.GameEngine.ContextUnlockResult;
import io.guessauthor.jeopardy.rooms.PlayerSession;
import io.guessauthor.jeopardy.rooms.Room;
import io.guessauthor.jeopardy.rooms.RoomManager;
import io.guessauthor.jeopardy.util.HttpUtil;
import io.guessauthor.jeopardy.util.JsonResponses;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class ContextHandler implements HttpHandler {

    private final RoomManager roomManager;

    public ContextHandler(RoomManager roomManager) {
        this.roomManager = roomManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            HttpUtil.respondWithStatus(exchange, 405, "Method Not Allowed");
            return;
        }

        String body = readBody(exchange.getRequestBody());
        Map<String, String> params = HttpUtil.parseFormUrlEncoded(body);
        String roomId = sanitizeRoomId(params.get("roomId"));
        String username = sanitizeUsername(params.get("username"));
        String questionId = params.get("questionId");

        if (roomId == null || username == null || questionId == null || questionId.isBlank()) {
            HttpUtil.respondWithStatus(exchange, 400, "Missing roomId, username, or questionId");
            return;
        }

        Optional<Room> roomOptional = roomManager.room(roomId);
        if (roomOptional.isEmpty()) {
            HttpUtil.respondWithStatus(exchange, 404, "Room not found");
            return;
        }

        Room room = roomOptional.get();
        PlayerSession session = room.getOrCreatePlayer(username);
        ContextUnlockResult result = session.engine().unlockContext(questionId);
        ContextStatus status = result.status();

        if (status == ContextStatus.NOT_FOUND) {
            HttpUtil.respondWithStatus(exchange, 404, "Question not found or expired");
            return;
        }
        if (status == ContextStatus.INSUFFICIENT_FUNDS) {
            HttpUtil.respondWithStatus(exchange, 400, "Not enough points to buy context");
            return;
        }
        if (status == ContextStatus.ERROR) {
            HttpUtil.respondWithStatus(exchange, 500, "Database error while fetching context");
            return;
        }

        ContextResponse response = result.response();
        String json = JsonResponses.context(response);
        HttpUtil.respondJson(exchange, 200, json);
    }

    private static String readBody(InputStream inputStream) throws IOException {
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String sanitizeRoomId(String roomId) {
        if (roomId == null) {
            return null;
        }
        String normalized = roomId.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.matches("[a-z0-9]{10}") ? normalized : null;
    }

    private static String sanitizeUsername(String username) {
        if (username == null) {
            return null;
        }
        String cleaned = username.replaceAll("[^A-Za-z0-9 _-]", "").trim().replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) {
            return null;
        }
        if (cleaned.length() > 32) {
            cleaned = cleaned.substring(0, 32).trim();
        }
        return cleaned.isEmpty() ? null : cleaned;
    }
}
