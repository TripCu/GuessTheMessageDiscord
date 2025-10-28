#!/usr/bin/env python3
"""
Combine Discord chat export JSON files into a single aggregate file.

The script walks a directory (recursively by default), gathers every ``.json``
file, and joins the ones containing at least one message. Empty chat files are
reported, skipped, and can optionally be deleted with ``--delete-empty``.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any, Dict, Iterable, List


def parse_args(argv: Iterable[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Combine exported chat JSON files into one."
    )
    parser.add_argument(
        "input_dir",
        nargs="?",
        default=".",
        help="Directory containing chat JSON files (default: current directory).",
    )
    parser.add_argument(
        "-o",
        "--output",
        default="combined_chats.json",
        help="Path for the combined JSON output (default: combined_chats.json).",
    )
    parser.add_argument(
        "--delete-empty",
        action="store_true",
        help="Delete JSON files that do not contain any messages.",
    )
    parser.add_argument(
        "--no-recursive",
        dest="recursive",
        action="store_false",
        help="Only scan the top-level directory for JSON files.",
    )
    parser.set_defaults(recursive=True)
    return parser.parse_args(list(argv))


def load_chat(path: Path) -> Dict[str, Any]:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def main(argv: Iterable[str]) -> int:
    args = parse_args(argv)
    input_dir = Path(args.input_dir).expanduser().resolve()
    if not input_dir.exists() or not input_dir.is_dir():
        print(f"Input directory does not exist or is not a directory: {input_dir}", file=sys.stderr)
        return 1

    output_path = Path(args.output).expanduser().resolve()
    if output_path.exists() and output_path.is_dir():
        print(f"Output path is a directory, not a file: {output_path}", file=sys.stderr)
        return 1

    if args.recursive:
        candidates = input_dir.rglob("*.json")
    else:
        candidates = input_dir.glob("*.json")

    chat_files: List[Path] = [
        path
        for path in sorted(candidates)
        if path.is_file() and path.resolve() != output_path
    ]
    combined_chats: List[Dict[str, Any]] = []
    combined_files: List[Path] = []
    skipped_files: List[Path] = []

    for chat_file in chat_files:
        try:
            chat_data = load_chat(chat_file)
        except (json.JSONDecodeError, OSError) as exc:
            print(f"Skipping unreadable file {chat_file}: {exc}", file=sys.stderr)
            continue

        messages = chat_data.get("messages")
        if not isinstance(messages, list) or not messages:
            skipped_files.append(chat_file)
            continue

        chat_data["messageCount"] = len(messages)
        combined_chats.append(chat_data)
        combined_files.append(chat_file)

    if args.delete_empty:
        for empty_file in skipped_files:
            try:
                empty_file.unlink()
            except OSError as exc:
                print(f"Failed to delete {empty_file}: {exc}", file=sys.stderr)

    output_payload = {
        "chats": combined_chats,
        "totalChats": len(combined_chats),
        "totalMessages": sum(chat["messageCount"] for chat in combined_chats),
    }

    with output_path.open("w", encoding="utf-8") as handle:
        json.dump(output_payload, handle, indent=2, ensure_ascii=False)

    print(
        f"Combined {len(combined_chats)} chat file(s) "
        f"into {output_path} (skipped {len(skipped_files)} without messages)."
    )
    if combined_files:
        print("Combined files:")
        for combined in combined_files:
            print(f"  - {combined}")
    else:
        print("Combined files: none")

    if skipped_files:
        print("Skipped files (no messages):")
        for skipped in skipped_files:
            print(f"  - {skipped}")
    else:
        print("Skipped files (no messages): none")

    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
