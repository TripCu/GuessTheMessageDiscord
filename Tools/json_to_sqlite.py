#!/usr/bin/env python3

"""
Populate a SQLite database from the Discord JSON export in this directory.

Tables created:
  - guild, channel, export_info, date_range
  - participants (all users referenced anywhere)
  - messages (main message metadata)
  - attachments, embeds, stickers, inline_emojis
  - mentions (message ↔ participant links)
  - reactions and reaction_users (who reacted with what)

Usage:
    python json_to_sqlite.py <discord_export.json> <output.db>
"""

import argparse
import json
import sqlite3
from collections.abc import Mapping
from pathlib import Path
from typing import Any, Iterable


def bool_to_int(value: Any) -> int | None:
    if value is None:
        return None
    return 1 if bool(value) else 0


def ensure_participant(
    cursor: sqlite3.Cursor, cache: set[str], participant: Mapping[str, Any] | None
) -> str | None:
    if not participant:
        return None
    participant_id = participant.get("id")
    if not participant_id:
        return None
    if participant_id in cache:
        return participant_id

    cursor.execute(
        """
        INSERT INTO participants (
            id, name, discriminator, nickname, color, is_bot, avatar_url
        )
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET
            name = excluded.name,
            discriminator = excluded.discriminator,
            nickname = excluded.nickname,
            color = excluded.color,
            is_bot = excluded.is_bot,
            avatar_url = excluded.avatar_url
        """,
        (
            participant_id,
            participant.get("name"),
            participant.get("discriminator"),
            participant.get("nickname"),
            participant.get("color"),
            bool_to_int(participant.get("isBot")),
            participant.get("avatarUrl"),
        ),
    )
    cache.add(participant_id)
    return participant_id


def create_schema(connection: sqlite3.Connection) -> None:
    connection.executescript(
        """
        PRAGMA foreign_keys = ON;

        CREATE TABLE IF NOT EXISTS guild (
            id TEXT PRIMARY KEY,
            name TEXT,
            icon_url TEXT
        );

        CREATE TABLE IF NOT EXISTS channel (
            id TEXT PRIMARY KEY,
            type TEXT,
            category_id TEXT,
            category TEXT,
            name TEXT,
            topic TEXT,
            icon_url TEXT
        );

        CREATE TABLE IF NOT EXISTS export_info (
            id INTEGER PRIMARY KEY CHECK(id = 1),
            exported_at TEXT,
            message_count INTEGER
        );

        CREATE TABLE IF NOT EXISTS date_range (
            id INTEGER PRIMARY KEY CHECK(id = 1),
            after TEXT,
            before TEXT
        );

        CREATE TABLE IF NOT EXISTS participants (
            id TEXT PRIMARY KEY,
            name TEXT,
            discriminator TEXT,
            nickname TEXT,
            color TEXT,
            is_bot INTEGER,
            avatar_url TEXT
        );

        CREATE TABLE IF NOT EXISTS messages (
            id TEXT PRIMARY KEY,
            type TEXT,
            timestamp TEXT,
            timestamp_edited TEXT,
            call_ended_timestamp TEXT,
            is_pinned INTEGER,
            content TEXT,
            author_id TEXT,
            FOREIGN KEY(author_id) REFERENCES participants(id)
        );

        CREATE TABLE IF NOT EXISTS mentions (
            message_id TEXT,
            participant_id TEXT,
            PRIMARY KEY (message_id, participant_id),
            FOREIGN KEY(message_id) REFERENCES messages(id),
            FOREIGN KEY(participant_id) REFERENCES participants(id)
        );

        CREATE TABLE IF NOT EXISTS attachments (
            id TEXT PRIMARY KEY,
            message_id TEXT,
            url TEXT,
            file_name TEXT,
            file_size_bytes INTEGER,
            FOREIGN KEY(message_id) REFERENCES messages(id)
        );

        CREATE TABLE IF NOT EXISTS embeds (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            message_id TEXT,
            raw_json TEXT,
            FOREIGN KEY(message_id) REFERENCES messages(id)
        );

        CREATE TABLE IF NOT EXISTS stickers (
            id TEXT PRIMARY KEY,
            message_id TEXT,
            name TEXT,
            format TEXT,
            source_url TEXT,
            FOREIGN KEY(message_id) REFERENCES messages(id)
        );

        CREATE TABLE IF NOT EXISTS inline_emojis (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            message_id TEXT,
            emoji_id TEXT,
            name TEXT,
            code TEXT,
            is_animated INTEGER,
            image_url TEXT,
            FOREIGN KEY(message_id) REFERENCES messages(id)
        );

        CREATE TABLE IF NOT EXISTS reactions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            message_id TEXT,
            emoji_id TEXT,
            name TEXT,
            code TEXT,
            is_animated INTEGER,
            image_url TEXT,
            count INTEGER,
            FOREIGN KEY(message_id) REFERENCES messages(id)
        );

        CREATE TABLE IF NOT EXISTS reaction_users (
            reaction_id INTEGER,
            participant_id TEXT,
            PRIMARY KEY (reaction_id, participant_id),
            FOREIGN KEY(reaction_id) REFERENCES reactions(id),
            FOREIGN KEY(participant_id) REFERENCES participants(id)
        );
        """
    )


def insert_top_level(connection: sqlite3.Connection, data: Mapping[str, Any]) -> None:
    guild = data.get("guild") or {}
    channel = data.get("channel") or {}
    date_range = data.get("dateRange") or {}

    connection.execute(
        "INSERT OR REPLACE INTO guild (id, name, icon_url) VALUES (?, ?, ?)",
        (guild.get("id"), guild.get("name"), guild.get("iconUrl")),
    )
    connection.execute(
        """
        INSERT OR REPLACE INTO channel (
            id, type, category_id, category, name, topic, icon_url
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        (
            channel.get("id"),
            channel.get("type"),
            channel.get("categoryId"),
            channel.get("category"),
            channel.get("name"),
            channel.get("topic"),
            channel.get("iconUrl"),
        ),
    )
    connection.execute(
        """
        INSERT OR REPLACE INTO export_info (id, exported_at, message_count)
        VALUES (1, ?, ?)
        """,
        (data.get("exportedAt"), data.get("messageCount")),
    )
    connection.execute(
        """
        INSERT OR REPLACE INTO date_range (id, after, before)
        VALUES (1, ?, ?)
        """,
        (date_range.get("after"), date_range.get("before")),
    )


def insert_messages(connection: sqlite3.Connection, data: Mapping[str, Any]) -> None:
    messages: Iterable[Mapping[str, Any]] = data.get("messages", [])
    cursor = connection.cursor()
    seen_participants: set[str] = set()

    for message in messages:
        message_id = message.get("id")
        author_id = ensure_participant(cursor, seen_participants, message.get("author"))

        cursor.execute(
            """
            INSERT OR REPLACE INTO messages (
                id, type, timestamp, timestamp_edited, call_ended_timestamp,
                is_pinned, content, author_id
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                message_id,
                message.get("type"),
                message.get("timestamp"),
                message.get("timestampEdited"),
                message.get("callEndedTimestamp"),
                bool_to_int(message.get("isPinned")),
                message.get("content"),
                author_id,
            ),
        )

        for mention in message.get("mentions", []):
            participant_id = ensure_participant(cursor, seen_participants, mention)
            if participant_id:
                cursor.execute(
                    """
                    INSERT OR IGNORE INTO mentions (message_id, participant_id)
                    VALUES (?, ?)
                    """,
                    (message_id, participant_id),
                )

        for attachment in message.get("attachments", []):
            cursor.execute(
                """
                INSERT OR REPLACE INTO attachments (
                    id, message_id, url, file_name, file_size_bytes
                ) VALUES (?, ?, ?, ?, ?)
                """,
                (
                    attachment.get("id"),
                    message_id,
                    attachment.get("url"),
                    attachment.get("fileName"),
                    attachment.get("fileSizeBytes"),
                ),
            )

        for embed in message.get("embeds", []):
            cursor.execute(
                """
                INSERT INTO embeds (message_id, raw_json)
                VALUES (?, ?)
                """,
                (message_id, json.dumps(embed)),
            )

        for sticker in message.get("stickers", []):
            cursor.execute(
                """
                INSERT OR REPLACE INTO stickers (
                    id, message_id, name, format, source_url
                ) VALUES (?, ?, ?, ?, ?)
                """,
                (
                    sticker.get("id"),
                    message_id,
                    sticker.get("name"),
                    sticker.get("format"),
                    sticker.get("sourceUrl"),
                ),
            )

        for inline_emoji in message.get("inlineEmojis", []):
            cursor.execute(
                """
                INSERT INTO inline_emojis (
                    message_id, emoji_id, name, code, is_animated, image_url
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                (
                    message_id,
                    inline_emoji.get("id") or None,
                    inline_emoji.get("name"),
                    inline_emoji.get("code"),
                    bool_to_int(inline_emoji.get("isAnimated")),
                    inline_emoji.get("imageUrl"),
                ),
            )

        for reaction in message.get("reactions", []):
            emoji = reaction.get("emoji") or {}
            cursor.execute(
                """
                INSERT INTO reactions (
                    message_id, emoji_id, name, code, is_animated, image_url, count
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    message_id,
                    (emoji.get("id") or None) if emoji else None,
                    emoji.get("name"),
                    emoji.get("code"),
                    bool_to_int(emoji.get("isAnimated")),
                    emoji.get("imageUrl"),
                    reaction.get("count"),
                ),
            )
            reaction_row_id = cursor.lastrowid

            for user in reaction.get("users", []):
                participant_id = ensure_participant(cursor, seen_participants, user)
                if participant_id:
                    cursor.execute(
                        """
                        INSERT OR IGNORE INTO reaction_users (reaction_id, participant_id)
                        VALUES (?, ?)
                        """,
                        (reaction_row_id, participant_id),
                    )

    cursor.close()


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Convert the provided Discord JSON export into a SQLite database."
    )
    parser.add_argument("json_path", type=Path, help="Path to the Discord JSON export")
    parser.add_argument("sqlite_path", type=Path, help="Path for the SQLite database")
    args = parser.parse_args()

    data = json.loads(args.json_path.read_text(encoding="utf-8"))

    if args.sqlite_path.exists():
        args.sqlite_path.unlink()
    args.sqlite_path.parent.mkdir(parents=True, exist_ok=True)

    with sqlite3.connect(args.sqlite_path) as connection:
        create_schema(connection)
        insert_top_level(connection, data)
        insert_messages(connection, data)
        connection.commit()


if __name__ == "__main__":
    main()
