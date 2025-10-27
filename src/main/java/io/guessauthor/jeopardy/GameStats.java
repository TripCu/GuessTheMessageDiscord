package io.guessauthor.jeopardy;

public final class GameStats {

    private long totalPoints;
    private int currentStreak;
    private int bestStreak;

    public synchronized GameSnapshot snapshot() {
        return new GameSnapshot(totalPoints, currentStreak, bestStreak);
    }

    public synchronized ScoreChange applyCorrect(double basePoints, double streakBonusStep) {
        currentStreak += 1;
        if (currentStreak > bestStreak) {
            bestStreak = currentStreak;
        }
        double multiplier = 1.0 + streakBonusStep * Math.max(0, currentStreak - 1);
        long awarded = Math.max(0L, Math.round(basePoints * multiplier));
        totalPoints += awarded;
        return new ScoreChange(awarded, basePoints, multiplier, snapshot());
    }

    public synchronized ScoreChange applyIncorrect(double basePoints) {
        long before = totalPoints;
        long after = before / 2;
        long lost = before - after;
        totalPoints = after;
        currentStreak = 0;
        return new ScoreChange(-lost, basePoints, 0.0, snapshot());
    }

    public synchronized GameSnapshot spendPoints(long cost) {
        if (cost < 0 || totalPoints < cost) {
            return null;
        }
        totalPoints -= cost;
        return snapshot();
    }

    public synchronized void refundPoints(long amount) {
        if (amount > 0) {
            totalPoints += amount;
        }
    }

    public record GameSnapshot(long totalPoints, int currentStreak, int bestStreak) {}

    public record ScoreChange(long awardedPoints, double basePoints, double streakMultiplier, GameSnapshot snapshot) {}
}
