package io.guessauthor.jeopardy.rooms;

import io.guessauthor.jeopardy.GameStats.GameSnapshot;

public record PlayerRecord(String username, GameSnapshot snapshot) {}
