package com.trip.jeopardy.rooms;

import com.trip.jeopardy.GameStats.GameSnapshot;

public record PlayerRecord(String username, GameSnapshot snapshot) {}
