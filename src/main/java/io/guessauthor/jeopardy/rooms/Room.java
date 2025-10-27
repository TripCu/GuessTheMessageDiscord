package io.guessauthor.jeopardy.rooms;

import io.guessauthor.jeopardy.GameStats.GameSnapshot;
import io.guessauthor.jeopardy.data.MessageRepository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class Room {

    private static final int MAX_USERNAME_LENGTH = 32;

    private final String id;
    private final String displayName;
    private final MessageRepository repository;
    private final List<String> messageIds;
    private final double basePoints;
    private final double decayPerSecond;
    private final double streakBonusStep;
    private final long contextCost;
    private final Duration questionExpiry;
    private final ConcurrentHashMap<String, PlayerSession> players = new ConcurrentHashMap<>();

    Room(
        String id,
        String displayName,
        MessageRepository repository,
        List<String> messageIds,
        double basePoints,
        double decayPerSecond,
        double streakBonusStep,
        long contextCost,
        Duration questionExpiry
    ) {
        this.id = id;
        this.displayName = displayName;
        this.repository = repository;
        this.messageIds = messageIds;
        this.basePoints = basePoints;
        this.decayPerSecond = decayPerSecond;
        this.streakBonusStep = streakBonusStep;
        this.contextCost = contextCost;
        this.questionExpiry = questionExpiry;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public PlayerSession getOrCreatePlayer(String username) {
        String normalized = normalizeUsername(username);
        return players.computeIfAbsent(
            normalized,
            key -> new PlayerSession(
                normalized,
                repository,
                messageIds,
                basePoints,
                decayPerSecond,
                streakBonusStep,
                contextCost,
                questionExpiry
            )
        );
    }

    public List<PlayerRecord> leaderboard() {
        List<PlayerRecord> records = new ArrayList<>();
        for (PlayerSession session : players.values()) {
            GameSnapshot snapshot = session.stats().snapshot();
            records.add(new PlayerRecord(session.username(), snapshot));
        }
        records.sort(Comparator.comparingLong((PlayerRecord r) -> r.snapshot().totalPoints()).reversed());
        return records;
    }

    private static String normalizeUsername(String username) {
        String cleaned = (username == null ? "" : username)
            .replaceAll("[^A-Za-z0-9 _-]", "")
            .trim()
            .replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) {
            cleaned = "Guest";
        }
        if (cleaned.length() > MAX_USERNAME_LENGTH) {
            cleaned = cleaned.substring(0, MAX_USERNAME_LENGTH).trim();
        }
        return cleaned.isEmpty() ? "Guest" : cleaned;
    }
}
