#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

usage() {
  cat <<'EOF'
Usage:
  ./build_and_run.sh [--db PATH_TO_DISCORD_DB] [--port PORT]

Builds the project with Maven and launches the server locally.
If --db is omitted, the app will start without a default room.

Options:
  --db PATH      Path to the Discord SQLite database (optional; can create rooms via API).
  --port PORT    Port for the HTTP server (default: 8080).
  --room-name NAME  Display name for the default room when using --db.
  -h, --help     Show this help text and exit.
EOF
}

DB_PATH=""
ROOM_NAME=""
PORT="8080"
ROOMS_DIR="${SCRIPT_DIR}/rooms"
WEB_ROOT="${SCRIPT_DIR}/public"

mkdir -p "${ROOMS_DIR}"

while [[ $# -gt 0 ]]; do
  case "$1" in
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

if [[ -n "${DB_PATH}" && ! -f "${DB_PATH}" ]]; then
  echo "Error: Discord database not found at '${DB_PATH}'." >&2
  exit 1
fi

echo "Building application with Maven..."
mvn -B clean package

echo "Build complete."
echo "Launching GuessTheAuthor on port ${PORT}..."
echo "Press Ctrl+C to stop the server."

JAVA_ARGS=("--port" "${PORT}")
if [[ -n "${DB_PATH}" ]]; then
  JAVA_ARGS+=("--db" "${DB_PATH}")
fi
if [[ -n "${ROOM_NAME}" ]]; then
  JAVA_ARGS+=("--room-name" "${ROOM_NAME}")
fi
JAVA_ARGS+=("--rooms-dir" "${ROOMS_DIR}")
JAVA_ARGS+=("--web-root" "${WEB_ROOT}")

JAR_FILE="$(find "${SCRIPT_DIR}/target" -maxdepth 1 -name 'jeopardy-server-*.jar' | head -n 1)"
if [[ -z "${JAR_FILE}" ]]; then
  echo "Error: shaded JAR not found in target/. Did the build succeed?" >&2
  exit 1
fi

java -jar "${JAR_FILE}" "${JAVA_ARGS[@]}"
