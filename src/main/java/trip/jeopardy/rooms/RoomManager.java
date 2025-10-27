package com.trip.jeopardy.rooms;

import com.trip.jeopardy.data.MessageRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class RoomManager {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ROOM_ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int ROOM_ID_LENGTH = 10;
    private static final String ROOM_ID_PATTERN = "^[a-z0-9]{10}$";
    private static final int MAX_DB_BYTES = 25 * 1024 * 1024; // 25 MB

    private final Path storageDir;
    private final double basePoints;
    private final double decayPerSecond;
    private final double streakBonusStep;
    private final long contextCost;
    private final Duration questionExpiry;
    private final ConcurrentHashMap<String, Room> rooms = new ConcurrentHashMap<>();

    public RoomManager(
        Path storageDir,
        double basePoints,
        double decayPerSecond,
        double streakBonusStep,
        long contextCost,
        Duration questionExpiry
    ) throws IOException {
        this.storageDir = storageDir;
        this.basePoints = basePoints;
        this.decayPerSecond = decayPerSecond;
        this.streakBonusStep = streakBonusStep;
        this.contextCost = contextCost;
        this.questionExpiry = questionExpiry;
        Files.createDirectories(storageDir);
    }

    public RoomCreationResult createRoom(String displayName, byte[] databaseContents) throws IOException, SQLException {
        if (databaseContents.length > MAX_DB_BYTES) {
            throw new IOException("Database file exceeds size limit.");
        }
        String roomId = generateRoomId();
        Path dbPath = storageDir.resolve(roomId + ".db");
        Files.write(dbPath, databaseContents);
        return createRoomFromExistingPath(roomId, sanitizeRoomName(displayName), dbPath);
    }

    public RoomCreationResult createRoomFromPath(String displayName, Path databasePath) throws IOException, SQLException {
        String roomId = generateRoomId();
        Path target = storageDir.resolve(roomId + ".db");
        Files.copy(databasePath, target, StandardCopyOption.REPLACE_EXISTING);
        return createRoomFromExistingPath(roomId, sanitizeRoomName(displayName), target);
    }

    public Optional<Room> room(String roomId) {
        if (roomId == null) {
            return Optional.empty();
        }
        String normalized = roomId.toLowerCase(Locale.US);
        if (!normalized.matches(ROOM_ID_PATTERN)) {
            return Optional.empty();
        }
        return Optional.ofNullable(rooms.get(normalized));
    }

    public List<PlayerRecord> leaderboard(String roomId) {
        Room room = room(roomId).orElse(null);
        if (room == null) {
            return List.of();
        }
        return room.leaderboard();
    }

    private RoomCreationResult createRoomFromExistingPath(String roomId, String displayName, Path databasePath)
        throws SQLException {
        MessageRepository repository = new MessageRepository(databasePath);
        List<String> messageIds = repository.fetchEligibleMessageIds();
        if (messageIds.isEmpty()) {
            throw new SQLException("Database has no eligible messages: " + databasePath);
        }
        String resolvedName = (displayName == null || displayName.isBlank())
            ? "Room " + roomId
            : displayName;
        Room room = new Room(
            roomId.toLowerCase(Locale.US),
            resolvedName,
            repository,
            messageIds,
            basePoints,
            decayPerSecond,
            streakBonusStep,
            contextCost,
            questionExpiry
        );
        rooms.put(room.id(), room);
        return new RoomCreationResult(room.id(), room.displayName());
    }

    private static String generateRoomId() {
        StringBuilder builder = new StringBuilder(ROOM_ID_LENGTH);
        for (int i = 0; i < ROOM_ID_LENGTH; i++) {
            int index = RANDOM.nextInt(ROOM_ID_ALPHABET.length());
            builder.append(ROOM_ID_ALPHABET.charAt(index));
        }
        return builder.toString();
    }

    private static String sanitizeRoomName(String name) {
        if (name == null) {
            return null;
        }
        String cleaned = name.replaceAll("[^A-Za-z0-9 _-]", "").trim().replaceAll("\\s+", " ");
        if (cleaned.length() > MAX_ROOM_NAME_LENGTH) {
            cleaned = cleaned.substring(0, MAX_ROOM_NAME_LENGTH).trim();
        }
        return cleaned.isEmpty() ? null : cleaned;
    }

    public record RoomCreationResult(String roomId, String displayName) {}
}
