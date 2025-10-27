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
MAVEN_VERSION="3.9.6"
MVN_CMD="$(command -v mvn || true)"

ensure_maven() {
  local maven_base="${SCRIPT_DIR}/.maven"
  local maven_dir="${maven_base}/apache-maven-${MAVEN_VERSION}"
  local maven_bin="${maven_dir}/bin/mvn"

  if [[ -x "${maven_bin}" ]]; then
    MVN_CMD="${maven_bin}"
    return
  fi

  if ! command -v curl >/dev/null 2>&1; then
    echo "Error: Maven is not installed and 'curl' is unavailable to download it automatically." >&2
    exit 1
  fi

  echo "Maven not found. Downloading Apache Maven ${MAVEN_VERSION}..."
  mkdir -p "${maven_base}"
  local archive="${maven_base}/apache-maven-${MAVEN_VERSION}-bin.tar.gz"
  local url="https://dlcdn.apache.org/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz"

  if ! curl -fsSL "${url}" -o "${archive}"; then
    echo "Error: failed to download Maven from ${url}" >&2
    exit 1
  fi

  if ! tar -xzf "${archive}" -C "${maven_base}"; then
    echo "Error: failed to extract Maven archive." >&2
    exit 1
  fi

  rm -f "${archive}"
  MVN_CMD="${maven_bin}"
}

if [[ -z "${MVN_CMD}" ]]; then
  ensure_maven
else
  echo "Using system Maven at ${MVN_CMD}"
fi

if [[ -z "${MVN_CMD}" || ! -x "${MVN_CMD}" ]]; then
  echo "Error: Maven executable not found even after installation attempt." >&2
  exit 1
fi

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
  echo "No Discord database specified. The server will start without a default room."
  DB_PATH=""
fi

if [[ -n "${DB_PATH}" && ! -f "${DB_PATH}" ]]; then
  echo "Error: Discord database not found at '${DB_PATH}'." >&2
  exit 1
fi

echo "Building application with Maven..."
"${MVN_CMD}" -B clean package

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

PIDS_TO_KILL=()

collect_pids() {
  PIDS_TO_KILL=()

  if [[ -n "${PORT}" ]]; then
    while IFS= read -r pid; do
      [[ -n "${pid}" ]] && PIDS_TO_KILL+=("${pid}")
    done < <(lsof -nti :"${PORT}" 2>/dev/null || true)
  fi

  while IFS= read -r pid; do
    [[ -n "${pid}" ]] && PIDS_TO_KILL+=("${pid}")
  done < <(pgrep -f "jeopardy-server-.*\.jar" 2>/dev/null || true)
}

stop_existing_instances() {
  collect_pids
  if [[ ${#PIDS_TO_KILL[@]} -eq 0 ]]; then
    return
  fi

  local pids_to_stop=()
  while IFS= read -r pid; do
    [[ -n "${pid}" ]] && pids_to_stop+=("${pid}")
  done < <(printf '%s\n' "${PIDS_TO_KILL[@]}" | awk '!seen[$0]++')

  if [[ ${#pids_to_stop[@]} -eq 0 ]]; then
    return
  fi

  echo "Stopping existing server processes..."
  for pid in "${pids_to_stop[@]}"; do
    kill "${pid}" 2>/dev/null || true
  done
  sleep 1

  for pid in "${pids_to_stop[@]}"; do
    if kill -0 "${pid}" 2>/dev/null; then
      echo "Forcing termination for PID: ${pid}"
      kill -9 "${pid}" 2>/dev/null || true
    fi
  done
}

stop_existing_instances

java -jar "${JAR_FILE}" "${JAVA_ARGS[@]}"
