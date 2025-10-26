package com.trip.jeopardy.util;

import com.trip.jeopardy.GameEngine;
import com.trip.jeopardy.GameStats.GameSnapshot;
import com.trip.jeopardy.data.MessageRepository;

import java.util.List;

public final class JsonResponses {

    private JsonResponses() {
    }

    public static String question(GameEngine.QuestionResponse response) {
        MessageRepository.Message message = response.message();
        return """
            {
              "questionId": %s,
              "messageId": %s,
              "content": %s,
              "timestamp": %s,
              "attachments": %s,
              "embeds": %s,
              "choices": %s,
              "score": %s
            }
            """.formatted(
            JsonUtil.toJsonValue(response.questionId()),
            JsonUtil.toJsonValue(message.id()),
            JsonUtil.toJsonValue(message.content()),
            JsonUtil.toJsonValue(message.timestamp()),
            attachmentsToJson(message.attachments()),
            embedsToJson(message.embeds()),
            choicesToJson(message.choices()),
            scoreToJson(response.score())
        );
    }

    public static String guess(GameEngine.GuessResponse response) {
        return """
            {
              "correct": %s,
              "displayName": %s,
              "fullName": %s,
              "correctChoiceId": %s,
              "awardedPoints": %s,
              "basePoints": %s,
              "streakMultiplier": %s,
              "elapsedSeconds": %s,
              "totalPoints": %s,
              "currentStreak": %s,
              "bestStreak": %s
            }
            """.formatted(
            Boolean.toString(response.correct()),
            JsonUtil.toJsonValue(response.displayName()),
            JsonUtil.toJsonValue(response.fullName()),
            JsonUtil.toJsonValue(response.correctChoiceId()),
            Long.toString(response.awardedPoints()),
            formatDouble(response.basePoints()),
            formatDouble(response.streakMultiplier()),
            formatDouble(response.elapsedSeconds()),
            Long.toString(response.score().totalPoints()),
            Integer.toString(response.score().currentStreak()),
            Integer.toString(response.score().bestStreak())
        );
    }

    public static String context(GameEngine.ContextResponse response) {
        return """
            {
              "cost": %s,
              "contextUnlocked": true,
              "context": {
                "before": %s,
                "after": %s
              },
              "score": %s
            }
            """.formatted(
            Long.toString(response.cost()),
            contextSnippetToJson(response.context().before()),
            contextSnippetToJson(response.context().after()),
            scoreToJson(response.score())
        );
    }

    public static String scoreToJson(GameSnapshot snapshot) {
        return """
            {
              "totalPoints": %s,
              "currentStreak": %s,
              "bestStreak": %s
            }
            """.formatted(
            Long.toString(snapshot.totalPoints()),
            Integer.toString(snapshot.currentStreak()),
            Integer.toString(snapshot.bestStreak())
        );
    }

    private static String attachmentsToJson(List<MessageRepository.Attachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder();
        builder.append('[');
        boolean first = true;
        for (MessageRepository.Attachment attachment : attachments) {
            if (attachment == null) {
                continue;
            }
            if (!first) {
                builder.append(',');
            }
            builder
                .append('{')
                .append("\"url\":").append(JsonUtil.toJsonValue(attachment.url())).append(',')
                .append("\"fileName\":").append(JsonUtil.toJsonValue(attachment.fileName()))
                .append('}');
            first = false;
        }
        builder.append(']');
        return builder.toString();
    }

    private static String embedsToJson(List<String> embeds) {
        if (embeds == null || embeds.isEmpty()) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder();
        builder.append('[');
        boolean first = true;
        for (String embed : embeds) {
            if (embed == null || embed.isBlank()) {
                continue;
            }
            if (!first) {
                builder.append(',');
            }
            builder.append(embed);
            first = false;
        }
        builder.append(']');
        return builder.toString();
    }

    private static String choicesToJson(List<MessageRepository.Choice> choices) {
        if (choices == null || choices.isEmpty()) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder();
        builder.append('[');
        boolean first = true;
        for (MessageRepository.Choice choice : choices) {
            if (choice == null) {
                continue;
            }
            if (!first) {
                builder.append(',');
            }
            builder
                .append('{')
                .append("\"participantId\":").append(JsonUtil.toJsonValue(choice.participantId())).append(',')
                .append("\"displayName\":").append(JsonUtil.toJsonValue(choice.displayName())).append(',')
                .append("\"fullName\":").append(JsonUtil.toJsonValue(choice.fullName()))
                .append('}');
            first = false;
        }
        builder.append(']');
        return builder.toString();
    }

    private static String contextSnippetToJson(MessageRepository.ContextSnippet snippet) {
        if (snippet == null) {
            return "null";
        }
        return """
            {
              "messageId": %s,
              "content": %s,
              "timestamp": %s,
              "displayName": %s
            }
            """.formatted(
            JsonUtil.toJsonValue(snippet.id()),
            JsonUtil.toJsonValue(snippet.content()),
            JsonUtil.toJsonValue(snippet.timestamp()),
            JsonUtil.toJsonValue(snippet.displayName())
        );
    }

    private static String formatDouble(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "0";
        }
        return String.format(java.util.Locale.US, "%.2f", value);
    }
}
