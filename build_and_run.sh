#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="${SCRIPT_DIR}/output"

cd "${SCRIPT_DIR}"

usage() {
  cat <<'EOF'
Usage:
  ./build_and_run.sh [--jar PATH_TO_SQLITE_JDBC] [--db PATH_TO_DISCORD_DB] [--port PORT]

Compiles the GuessTheAuthor project into ./output and launches the server.
If --jar or --db are omitted, the script will attempt to locate them automatically.

Options:
  --jar PATH     Path to the sqlite-jdbc JAR file (auto-detected if omitted).
  --db PATH      Path to the Discord SQLite database (optional; can create rooms via API).
  --port PORT    Port for the HTTP server (default: 8080).
  --room-name NAME  Display name for the default room when using --db.
  -h, --help     Show this help text and exit.
EOF
}

JAR_PATH=""
DB_PATH=""
ROOM_NAME=""
PORT="8080"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --jar)
      JAR_PATH="$2"
      shift 2
      ;;
    --db)
      DB_PATH="$2"
      shift 2
      ;;
    --port)
      PORT="$2"
      shift 2
      ;;
    --room-name)
      ROOM_NAME="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ -z "${JAR_PATH}" ]]; then
  JAR_CANDIDATES=()
  while IFS= read -r -d '' candidate; do
    JAR_CANDIDATES+=("$candidate")
  done < <(find "${SCRIPT_DIR}" -maxdepth 1 -type f -name 'sqlite-jdbc*.jar' -print0 2>/dev/null)

  if [[ ${#JAR_CANDIDATES[@]} -eq 1 ]]; then
    JAR_PATH="${JAR_CANDIDATES[0]}"
    echo "Detected SQLite JDBC JAR: ${JAR_PATH}"
  elif [[ ${#JAR_CANDIDATES[@]} -gt 1 ]]; then
    echo "Multiple sqlite-jdbc*.jar files found. Please specify one with --jar PATH." >&2
    printf 'Found candidates:\n' >&2
    for candidate in "${JAR_CANDIDATES[@]}"; do
      printf '  %s\n' "$candidate" >&2
    done
    exit 1
  else
    echo "Error: SQLite JDBC JAR not found. Provide it via --jar PATH." >&2
    exit 1
  fi
fi

if [[ -z "${DB_PATH}" ]]; then
  if [[ -f "${SCRIPT_DIR}/discord_messages.db" ]]; then
    DB_PATH="${SCRIPT_DIR}/discord_messages.db"
    echo "Detected Discord DB: ${DB_PATH}"
  else
    DB_CANDIDATES=()
    while IFS= read -r -d '' candidate; do
      DB_CANDIDATES+=("$candidate")
    done < <(find "${SCRIPT_DIR}" -maxdepth 1 -type f -name '*.db' -print0 2>/dev/null)

    if [[ ${#DB_CANDIDATES[@]} -eq 1 ]]; then
      DB_PATH="${DB_CANDIDATES[0]}"
      echo "Detected Discord DB: ${DB_PATH}"
    elif [[ ${#DB_CANDIDATES[@]} -gt 1 ]]; then
      echo "Multiple .db files found. Starting without a default room; use --db to specify one."
      DB_PATH=""
    else
      echo "No Discord database found. You can create rooms via the web UI."
      DB_PATH=""
    fi
  fi
fi

if [[ ! -f "${JAR_PATH}" ]]; then
  echo "Error: SQLite JDBC JAR not found at '${JAR_PATH}'." >&2
  exit 1
fi

if [[ -n "${DB_PATH}" && ! -f "${DB_PATH}" ]]; then
  echo "Error: Discord database not found at '${DB_PATH}'." >&2
  exit 1
fi

mkdir -p "${OUTPUT_DIR}"

echo "Compiling Java sources into ${OUTPUT_DIR}..."
find "${SCRIPT_DIR}/src" -name '*.java' -print0 \
  | xargs -0 javac -cp "${JAR_PATH}" -d "${OUTPUT_DIR}"

echo "Compilation complete."
echo "Launching GuessTheAuthor on port ${PORT}..."
echo "Press Ctrl+C to stop the server."

JAVA_ARGS=("--port" "${PORT}")
if [[ -n "${DB_PATH}" ]]; then
  JAVA_ARGS+=("--db" "${DB_PATH}")
fi
if [[ -n "${ROOM_NAME}" ]]; then
  JAVA_ARGS+=("--room-name" "${ROOM_NAME}")
fi

java -cp "${JAR_PATH}:${OUTPUT_DIR}" com.trip.jeopardy.ServerLauncher "${JAVA_ARGS[@]}"
