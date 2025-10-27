package io.guessauthor.jeopardy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MessageDeck {

    private final List<String> allIds;
    private final ArrayDeque<String> queue = new ArrayDeque<>();

    public MessageDeck(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("Message deck requires at least one id.");
        }
        this.allIds = new ArrayList<>(ids);
        reshuffle();
    }

    synchronized String nextId() {
        if (queue.isEmpty()) {
            reshuffle();
        }
        return queue.isEmpty() ? null : queue.removeFirst();
    }

    synchronized int totalSize() {
        return allIds.size();
    }

    private void reshuffle() {
        List<String> copy = new ArrayList<>(allIds);
        Collections.shuffle(copy);
        queue.clear();
        queue.addAll(copy);
    }
}
