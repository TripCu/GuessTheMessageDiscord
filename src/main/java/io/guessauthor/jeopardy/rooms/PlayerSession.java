package io.guessauthor.jeopardy.rooms;

import io.guessauthor.jeopardy.GameEngine;
import io.guessauthor.jeopardy.GameStats;
import io.guessauthor.jeopardy.MessageDeck;
import io.guessauthor.jeopardy.data.MessageRepository;

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
        double contextCostPercentage,
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
            contextCostPercentage,
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
