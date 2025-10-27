package io.guessauthor.jeopardy;

import io.guessauthor.jeopardy.GameStats.GameSnapshot;
import io.guessauthor.jeopardy.GameStats.ScoreChange;
import io.guessauthor.jeopardy.data.MessageRepository;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.sql.SQLException;

public final class GameEngine {

    private final MessageRepository repository;
    private final MessageDeck deck;
    private final GameStats stats;
    private final double basePoints;
    private final double decayPerSecond;
    private final double streakBonusStep;
    private final double contextCostPercentage;
    private final long questionExpiryNanos;
    private final ConcurrentHashMap<String, QuestionState> activeQuestions = new ConcurrentHashMap<>();

    public GameEngine(
        MessageRepository repository,
        MessageDeck deck,
        GameStats stats,
        double basePoints,
        double decayPerSecond,
        double streakBonusStep,
        double contextCostPercentage,
        Duration questionExpiry
    ) {
        this.repository = repository;
        this.deck = deck;
        this.stats = stats;
        this.basePoints = basePoints;
        this.decayPerSecond = decayPerSecond;
        this.streakBonusStep = streakBonusStep;
        this.contextCostPercentage = contextCostPercentage;
        this.questionExpiryNanos = questionExpiry.toNanos();
    }

    public Optional<QuestionResponse> prepareQuestion() {
        pruneExpiredQuestions();

        MessageRepository.Message message = null;
        int attempts = deck.totalSize();
        for (int attempt = 0; attempt < attempts; attempt++) {
            String messageId = deck.nextId();
            if (messageId == null) {
                break;
            }
            Optional<MessageRepository.Message> candidate;
            try {
                candidate = repository.fetchMessageById(messageId);
            } catch (SQLException ex) {
                throw new IllegalStateException("Failed to load message with id " + messageId, ex);
            }
            if (candidate.isEmpty()) {
                continue;
            }
            MessageRepository.Message resolved = candidate.get();
            if (resolved.authorId() == null || resolved.choices().isEmpty()) {
                continue;
            }
            boolean hasCorrectChoice = resolved.choices().stream()
                .anyMatch(choice -> resolved.authorId().equals(choice.participantId()));
            if (!hasCorrectChoice) {
                continue;
            }
            message = resolved;
            break;
        }

        if (message == null) {
            return Optional.empty();
        }

        String questionId = UUID.randomUUID().toString();
        QuestionState state = new QuestionState(message, System.nanoTime());
        activeQuestions.put(questionId, state);

        return Optional.of(new QuestionResponse(questionId, message, stats.snapshot()));
    }

    public GuessEvaluationResult evaluateGuess(String questionId, String choiceId) {
        return resolveGuess(questionId, choiceId, false);
    }

    public GuessEvaluationResult forfeitQuestion(String questionId) {
        return resolveGuess(questionId, null, true);
    }

    public void forfeitOutstandingQuestions() {
        for (String questionId : activeQuestions.keySet()) {
            resolveGuess(questionId, null, true);
        }
    }

    private GuessEvaluationResult resolveGuess(String questionId, String choiceId, boolean forceIncorrect) {
        if (!forceIncorrect && (choiceId == null || choiceId.isBlank())) {
            return GuessEvaluationResult.invalid();
        }

        QuestionState state = activeQuestions.remove(questionId);
        if (state == null) {
            return GuessEvaluationResult.notFound();
        }

        MessageRepository.Message message = state.message();
        String correctChoiceId = message.authorId();
        boolean correct = !forceIncorrect
            && correctChoiceId != null
            && correctChoiceId.equals(choiceId);

        double elapsedSeconds = Math.max(
            0.0,
            (System.nanoTime() - state.issuedAtNanos()) / 1_000_000_000.0
        );
        double effectiveBase = Math.max(basePoints - decayPerSecond * elapsedSeconds, 0.0);

        ScoreChange change = correct
            ? stats.applyCorrect(effectiveBase, streakBonusStep)
            : stats.applyIncorrect(effectiveBase);

        MessageRepository.MessageContext contextSnapshot = null;
        if (!forceIncorrect) {
            try {
                contextSnapshot = repository.fetchContext(message.id());
                if (contextSnapshot == null) {
                    contextSnapshot = new MessageRepository.MessageContext(null, null);
                }
            } catch (SQLException ex) {
                contextSnapshot = new MessageRepository.MessageContext(null, null);
            }
        }

        GuessResponse response = new GuessResponse(
            correct,
            message.displayName(),
            message.fullName(),
            correctChoiceId,
            change.awardedPoints(),
            change.basePoints(),
            change.streakMultiplier(),
            elapsedSeconds,
            change.snapshot(),
            contextSnapshot
        );

        return GuessEvaluationResult.success(response);
    }

    public ContextUnlockResult unlockContext(String questionId) {
        QuestionState state = activeQuestions.get(questionId);
        if (state == null) {
            return ContextUnlockResult.notFound();
        }

        MessageRepository.Message message = state.message();
        MessageRepository.MessageContext contextData;
        boolean unlocked;
        long storedCost;

        synchronized (state) {
            unlocked = state.isContextUnlocked();
            contextData = state.context().orElse(null);
            storedCost = state.contextCost();
        }

        if (unlocked && contextData != null) {
            return ContextUnlockResult.success(new ContextResponse(storedCost, contextData, stats.snapshot()));
        }

        if (unlocked) {
            try {
                MessageRepository.MessageContext fetched = repository.fetchContext(message.id());
                synchronized (state) {
                    state.unlockContext(fetched, storedCost);
                }
                return ContextUnlockResult.success(new ContextResponse(storedCost, fetched, stats.snapshot()));
            } catch (SQLException ex) {
                return ContextUnlockResult.error();
            }
        }

        long currentPoints = stats.snapshot().totalPoints();
        long dynamicCost = calculateContextCost(currentPoints);
        GameSnapshot snapshotAfterSpend = stats.spendPoints(dynamicCost);
        if (snapshotAfterSpend == null) {
            return ContextUnlockResult.insufficientFunds();
        }

        try {
            MessageRepository.MessageContext fetched = repository.fetchContext(message.id());
            if (fetched == null) {
                fetched = new MessageRepository.MessageContext(null, null);
            }
            synchronized (state) {
                state.unlockContext(fetched, dynamicCost);
            }
            return ContextUnlockResult.success(new ContextResponse(dynamicCost, fetched, snapshotAfterSpend));
        } catch (SQLException ex) {
            stats.refundPoints(dynamicCost);
            return ContextUnlockResult.error();
        }
    }

    public void pruneExpiredQuestions() {
        long cutoff = System.nanoTime() - questionExpiryNanos;
        activeQuestions.entrySet().removeIf(entry -> entry.getValue().issuedAtNanos() < cutoff);
    }

    public ConcurrentHashMap<String, QuestionState> activeQuestions() {
        return activeQuestions;
    }

    public double basePoints() {
        return basePoints;
    }

    public double decayPerSecond() {
        return decayPerSecond;
    }

    public double streakBonusStep() {
        return streakBonusStep;
    }

    public double contextCostPercentage() {
        return contextCostPercentage;
    }

    public GameStats stats() {
        return stats;
    }

    public MessageRepository repository() {
        return repository;
    }

    private long calculateContextCost(long totalPoints) {
        if (totalPoints <= 0) {
            return 0;
        }
        long cost = (long) Math.ceil(totalPoints * contextCostPercentage);
        if (cost <= 0) {
            cost = 1;
        }
        if (cost > totalPoints) {
            cost = totalPoints;
        }
        return cost;
    }

    public record QuestionResponse(String questionId, MessageRepository.Message message, GameSnapshot score) {}

    public record GuessResponse(
        boolean correct,
        String displayName,
        String fullName,
        String correctChoiceId,
        long awardedPoints,
        double basePoints,
        double streakMultiplier,
        double elapsedSeconds,
        GameSnapshot score,
        MessageRepository.MessageContext context
    ) {}

    public record GuessEvaluationResult(GuessStatus status, GuessResponse response) {
        static GuessEvaluationResult success(GuessResponse response) {
            return new GuessEvaluationResult(GuessStatus.SUCCESS, response);
        }

        static GuessEvaluationResult notFound() {
            return new GuessEvaluationResult(GuessStatus.NOT_FOUND, null);
        }

        static GuessEvaluationResult invalid() {
            return new GuessEvaluationResult(GuessStatus.INVALID_REQUEST, null);
        }
    }

    public enum GuessStatus {
        SUCCESS,
        NOT_FOUND,
        INVALID_REQUEST
    }

    public record ContextResponse(long cost, MessageRepository.MessageContext context, GameSnapshot score) {}

    public record ContextUnlockResult(ContextStatus status, ContextResponse response) {
        static ContextUnlockResult success(ContextResponse response) {
            return new ContextUnlockResult(ContextStatus.SUCCESS, response);
        }

        static ContextUnlockResult insufficientFunds() {
            return new ContextUnlockResult(ContextStatus.INSUFFICIENT_FUNDS, null);
        }

        static ContextUnlockResult notFound() {
            return new ContextUnlockResult(ContextStatus.NOT_FOUND, null);
        }

        static ContextUnlockResult error() {
            return new ContextUnlockResult(ContextStatus.ERROR, null);
        }
    }

    public enum ContextStatus {
        SUCCESS,
        INSUFFICIENT_FUNDS,
        NOT_FOUND,
        ERROR
    }
}
