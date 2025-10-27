package io.guessauthor.jeopardy.data;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class MessageRepository {

    private final String jdbcUrl;

    public MessageRepository(Path databasePath) {
        this.jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath();
    }

    public Optional<Message> fetchMessageById(String messageId) throws SQLException {
        if (messageId == null || messageId.isBlank()) {
            return Optional.empty();
        }
        String sql = """
            SELECT
                m.id,
                m.content,
                m.author_id,
                m.timestamp,
                COALESCE(NULLIF(TRIM(p.nickname), ''), NULLIF(TRIM(p.name), ''), 'Unknown') AS display_name,
                CASE
                    WHEN p.name IS NOT NULL AND p.discriminator IS NOT NULL
                    THEN p.name || '#' || p.discriminator
                    ELSE p.name
                END AS full_name
            FROM messages m
            LEFT JOIN participants p ON p.id = m.author_id
            WHERE m.id = ?
              AND m.content IS NOT NULL
              AND TRIM(m.content) <> ''
              AND (p.is_bot IS NULL OR p.is_bot = 0)
        """;

        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, messageId);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return Optional.of(mapMessage(connection, result));
                }
            }
        }
        return Optional.empty();
    }

    public List<String> fetchEligibleMessageIds() throws SQLException {
        String sql = """
            SELECT m.id
            FROM messages m
            LEFT JOIN participants p ON p.id = m.author_id
            WHERE m.content IS NOT NULL
              AND TRIM(m.content) <> ''
              AND (p.is_bot IS NULL OR p.is_bot = 0)
        """;

        List<String> ids = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                String id = result.getString("id");
                if (id != null && !id.isBlank()) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    public MessageContext fetchContext(String messageId) throws SQLException {
        if (messageId == null || messageId.isBlank()) {
            return new MessageContext(null, null);
        }
        try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
            ContextSnippet before = fetchAdjacentMessage(connection, messageId, true);
            ContextSnippet after = fetchAdjacentMessage(connection, messageId, false);
            return new MessageContext(before, after);
        }
    }

    private Message mapMessage(Connection connection, ResultSet result) throws SQLException {
        String messageId = result.getString("id");
        String authorId = result.getString("author_id");
        List<Attachment> attachments = loadAttachments(connection, messageId);
        List<String> embeds = loadEmbeds(connection, messageId);
        List<Choice> choices = buildChoices(connection, authorId, 4);

        return new Message(
            messageId,
            cleanContent(result.getString("content")),
            result.getString("timestamp"),
            result.getString("display_name"),
            result.getString("full_name"),
            attachments,
            embeds,
            authorId,
            choices
        );
    }

    private List<Choice> buildChoices(Connection connection, String authorId, int totalChoices) throws SQLException {
        Choice authorChoice = loadChoiceForParticipant(connection, authorId);
        List<Choice> choices = new ArrayList<>();
        if (authorChoice != null) {
            choices.add(authorChoice);
        }

        int needed = Math.max(totalChoices - choices.size(), 0);
        if (needed > 0) {
            choices.addAll(loadDistractorChoices(connection, authorId, needed * 3));
        }

        List<Choice> unique = new ArrayList<>();
        for (Choice choice : choices) {
            if (choice == null) {
                continue;
            }
            boolean exists = unique.stream()
                .anyMatch(existing -> existing.participantId().equals(choice.participantId()));
            if (!exists) {
                unique.add(choice);
            }
            if (unique.size() >= totalChoices) {
                break;
            }
        }

        if (authorChoice != null && unique.stream().noneMatch(c -> c.participantId().equals(authorChoice.participantId()))) {
            unique.add(authorChoice);
        }

        java.util.Collections.shuffle(unique);
        if (unique.size() > totalChoices) {
            return new ArrayList<>(unique.subList(0, totalChoices));
        }
        return unique;
    }

    private Choice loadChoiceForParticipant(Connection connection, String participantId) throws SQLException {
        if (participantId == null || participantId.isBlank()) {
            return null;
        }
        String sql = """
            SELECT
                id,
                COALESCE(NULLIF(TRIM(nickname), ''), NULLIF(TRIM(name), ''), 'Unknown') AS display_name,
                CASE
                    WHEN name IS NOT NULL AND discriminator IS NOT NULL
                    THEN name || '#' || discriminator
                    ELSE name
                END AS full_name
            FROM participants
            WHERE id = ?
              AND (is_bot IS NULL OR is_bot = 0)
        """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, participantId);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return new Choice(
                        participantId,
                        result.getString("display_name"),
                        result.getString("full_name")
                    );
                }
            }
        }
        return null;
    }

    private List<Choice> loadDistractorChoices(Connection connection, String authorId, int limit) throws SQLException {
        String sql = """
            SELECT
                id,
                COALESCE(NULLIF(TRIM(nickname), ''), NULLIF(TRIM(name), ''), 'Unknown') AS display_name,
                CASE
                    WHEN name IS NOT NULL AND discriminator IS NOT NULL
                    THEN name || '#' || discriminator
                    ELSE name
                END AS full_name
            FROM participants
            WHERE id != ?
              AND (is_bot IS NULL OR is_bot = 0)
            ORDER BY RANDOM()
            LIMIT ?
        """;
        List<Choice> distractors = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, authorId);
            statement.setInt(2, Math.max(limit, 0));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    distractors.add(new Choice(
                        result.getString("id"),
                        result.getString("display_name"),
                        result.getString("full_name")
                    ));
                }
            }
        }
        return distractors;
    }

    private List<Attachment> loadAttachments(Connection connection, String messageId) throws SQLException {
        String sql = """
            SELECT url, file_name
            FROM attachments
            WHERE message_id = ?
            ORDER BY rowid
        """;
        List<Attachment> attachments = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, messageId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    attachments.add(new Attachment(
                        result.getString("url"),
                        result.getString("file_name")
                    ));
                }
            }
        }
        return attachments;
    }

    private List<String> loadEmbeds(Connection connection, String messageId) throws SQLException {
        String sql = """
            SELECT raw_json
            FROM embeds
            WHERE message_id = ?
            ORDER BY id
        """;
        List<String> embeds = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, messageId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String raw = result.getString("raw_json");
                    if (raw != null && !raw.isBlank()) {
                        embeds.add(raw.trim());
                    }
                }
            }
        }
        return embeds;
    }

    private ContextSnippet fetchAdjacentMessage(
        Connection connection,
        String messageId,
        boolean before
    ) throws SQLException {
        String comparator = before ? "<" : ">";
        String sortDirection = before ? "DESC" : "ASC";
        String sql = """
            SELECT
                m.id,
                m.content,
                m.timestamp,
                COALESCE(NULLIF(TRIM(p.nickname), ''), NULLIF(TRIM(p.name), ''), 'Unknown') AS display_name
            FROM messages m
            LEFT JOIN participants p ON p.id = m.author_id
            WHERE m.timestamp IS NOT NULL
              AND m.timestamp %s
                (SELECT timestamp FROM messages WHERE id = ?)
            ORDER BY m.timestamp %s
            LIMIT 1
        """.formatted(comparator, sortDirection);

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, messageId);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return new ContextSnippet(
                        result.getString("id"),
                        cleanContent(result.getString("content")),
                        result.getString("timestamp"),
                        result.getString("display_name")
                    );
                }
            }
        }
        return null;
    }

    private static String cleanContent(String content) {
        if (content == null) {
            return null;
        }
        String trimmed = content.trim();
        return trimmed.replaceAll("\\s+", " ");
    }

    public record Message(
        String id,
        String content,
        String timestamp,
        String displayName,
        String fullName,
        List<Attachment> attachments,
        List<String> embeds,
        String authorId,
        List<Choice> choices
    ) {}

    public record Attachment(String url, String fileName) {}

    public record Choice(String participantId, String displayName, String fullName) {}

    public record ContextSnippet(String id, String content, String timestamp, String displayName) {}

    public record MessageContext(ContextSnippet before, ContextSnippet after) {}
}
