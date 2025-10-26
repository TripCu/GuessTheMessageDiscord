package com.trip.jeopardy.rooms;

import com.trip.jeopardy.GameEngine;
import com.trip.jeopardy.GameStats;
import com.trip.jeopardy.MessageDeck;
import com.trip.jeopardy.data.MessageRepository;

import java.time.Duration;
import java.util.List;

public final class PlayerSession {

    private final String username;
    private final GameStats stats;
    private final GameEngine engine;

    PlayerSession(
        String username,
        MessageRepository repository,
        List<String> messageIds,
        double basePoints,
        double decayPerSecond,
        double streakBonusStep,
        long contextCost,
        Duration questionExpiry
    ) {
        this.username = username;
        this.stats = new GameStats();
        MessageDeck deck = new MessageDeck(messageIds);
        this.engine = new GameEngine(
            repository,
            deck,
            stats,
            basePoints,
            decayPerSecond,
            streakBonusStep,
            contextCost,
            questionExpiry
        );
    }

    public String username() {
        return username;
    }

    public GameStats stats() {
        return stats;
    }

    public GameEngine engine() {
        return engine;
    }
}
