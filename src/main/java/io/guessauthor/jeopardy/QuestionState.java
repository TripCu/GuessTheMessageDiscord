package io.guessauthor.jeopardy;

import io.guessauthor.jeopardy.data.MessageRepository;

import java.util.Optional;

final class QuestionState {

    private final MessageRepository.Message message;
    private final long issuedAtNanos;
    private boolean contextUnlocked;
    private MessageRepository.MessageContext context;
    private long contextCost;

    QuestionState(MessageRepository.Message message, long issuedAtNanos) {
        this.message = message;
        this.issuedAtNanos = issuedAtNanos;
    }

    MessageRepository.Message message() {
        return message;
    }

    long issuedAtNanos() {
        return issuedAtNanos;
    }

    synchronized boolean isContextUnlocked() {
        return contextUnlocked;
    }

    synchronized Optional<MessageRepository.MessageContext> context() {
        return Optional.ofNullable(context);
    }

    synchronized long contextCost() {
        return contextCost;
    }

    synchronized void unlockContext(MessageRepository.MessageContext context, long cost) {
        this.context = context;
        this.contextUnlocked = true;
        this.contextCost = cost;
    }
}
