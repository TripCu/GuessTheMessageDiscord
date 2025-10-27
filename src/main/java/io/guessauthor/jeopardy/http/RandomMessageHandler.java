package io.guessauthor.jeopardy.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.guessauthor.jeopardy.GameEngine;
import io.guessauthor.jeopardy.rooms.PlayerSession;
import io.guessauthor.jeopardy.rooms.Room;
import io.guessauthor.jeopardy.rooms.RoomManager;
import io.guessauthor.jeopardy.util.HttpUtil;
import io.guessauthor.jeopardy.util.JsonResponses;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class RandomMessageHandler implements HttpHandler {

    private final RoomManager roomManager;

    public RandomMessageHandler(RoomManager roomManager) {
        this.roomManager = roomManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            HttpUtil.respondWithStatus(exchange, 405, "Method Not Allowed");
            return;
        }

        Map<String, String> params = HttpUtil.parseQueryParameters(exchange.getRequestURI().getRawQuery());
        String roomId = sanitizeRoomId(params.get("roomId"));
        String username = sanitizeUsername(params.get("username"));
        if (roomId == null || username == null) {
            HttpUtil.respondWithStatus(exchange, 400, "Missing roomId or username");
            return;
        }

        Optional<Room> roomOptional = roomManager.room(roomId);
        if (roomOptional.isEmpty()) {
            HttpUtil.respondWithStatus(exchange, 404, "Room not found");
            return;
        }

        Room room = roomOptional.get();
        PlayerSession session = room.getOrCreatePlayer(username);

        try {
            session.engine().pruneExpiredQuestions();
            Optional<GameEngine.QuestionResponse> response = session.engine().prepareQuestion();
            if (response.isEmpty()) {
                HttpUtil.respondWithStatus(exchange, 503, "No messages available");
                return;
            }
            String json = JsonResponses.question(response.get());
            HttpUtil.respondJson(exchange, 200, json);
        } catch (IllegalStateException ex) {
            HttpUtil.respondWithStatus(exchange, 500, "Database error while loading message");
        }
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
