
# Guess The Author Server

Self-hosted web app that turns a Discord chat export into a competitive guessing game. Players join shared rooms, the server streams random messages, and everyone races to identify the original author while leaderboards track streaks and points.

---
## Table of Contents
1. [Features](#features)
2. [Prerequisites](#prerequisites)
3. [Project Setup](#project-setup)
4. [Running the Server](#running-the-server)
   - [macOS & Linux](#macos--linux)
   - [Windows](#windows)
   - [Docker](#docker)
5. [Gameplay Overview](#gameplay-overview)
6. [Command-line Flags](#command-line-flags)
7. [API Reference](#api-reference)
8. [Project Structure](#project-structure)
9. [Deployment Guide](#deployment-guide)
10. [Troubleshooting](#troubleshooting)
11. [Security Notes](#security-notes)
12. [License](#license)

---
## Features
- Multiplayer rooms with 10-character IDs and optional friendly names.
- Smart SQLite importer that filters bots, renders attachments, and handles Discord embeds.
- Multiple-choice guessing with anti-repeat decks, streak multipliers, and time-based point decay.
- Optional paid context reveals adjacent messages; incorrect answers halve your score.
- Responsive HTML/CSS/JS front end served by a pure Java 21 backend using `HttpServer`.
- Single-command launch scripts for macOS/Linux (`build_and_run.sh`) and Windows (`build_and_run.ps1`).

## Prerequisites
Before you start, install or gather the following:
- **Java 21+** (e.g., Temurin or Microsoft OpenJDK). Verify with `java -version`.
- **Maven 3.9+**. Verify with `mvn -v`. Both helper scripts auto-install Maven 3.9.6 locally if missing.
- **Git** (optional but recommended) to clone the repository.
- **Discord SQLite export (`*.db`)** produced via an export tool. This seeds the game with messages.
- **Docker 24+** (optional) if you plan to containerize.

## Project Setup
1. **Clone or download** the repository.
   ```bash
   git clone https://github.com/your-org/guess-the-author.git
   cd guess-the-author
   ```
2. **Review project layout** (see [Project Structure](#project-structure)).
3. **Place your Discord export** (`discord_messages.db`) in the project root or note its path.

## Running the Server

### macOS & Linux
1. Ensure the script is executable:
   ```bash
   chmod +x build_and_run.sh
   ```
2. Launch (replace the DB path as needed):
   ```bash
   ./build_and_run.sh --db path/to/discord_messages.db --room-name "Friday Night" --port 8080
   ```
   - If you omit `--db`, the server starts with no default room; upload a database from the web UI.
   - The script downloads Maven 3.9.6 into `.maven/` if `mvn` is not on PATH.
   - Kills any running server on the specified port before launching.
3. Open <http://localhost:8080> and create or join a room via the UI.

### Windows
1. Open **PowerShell** *as your user* (not necessarily elevated) in the project directory.
2. The first time, allow local scripts:
   ```powershell
   Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
   ```
3. Run the helper script:
   ```powershell
   ./build_and_run.ps1 -DiscordDb "C:\exports\discord_messages.db" -RoomName "Friday Night" -Port 8080
   ```
   - Arguments are optional; omit `-DiscordDb` to upload via the UI later.
   - The script verifies Java, auto-installs Maven 3.9.6 in `.maven\`, stops existing server processes, builds, and launches the app.
4. Visit <http://localhost:8080> in your browser.

### Docker
1. Build the image:
   ```bash
   docker build -t guess-the-author .
   ```
2. Run the container:
   ```bash
   docker run --rm -p 8080:8080 -v $(pwd)/rooms:/app/rooms guess-the-author
   ```
   - Environment variables: `SERVER_PORT` (default `8080`), `ROOMS_DIR`, `WEB_ROOT`, `JAVA_OPTS`.
   - Bind mount `/app/rooms` to persist uploaded databases.

## Gameplay Overview
1. **Create or join** a room via the UI.
2. The server pulls a random eligible message and presents multiple-choice senders.
3. **Scoring**:
   - Base 1,000 points per question.
   - 25-point decay each second until you answer.
   - Streak multiplier: +20% per consecutive correct answer.
   - Buying context costs 200 points and reveals the previous and next messages.
   - Wrong answers halve your total points and reset the streak.
4. **Skipping counts as incorrect** — using “Show Another Message” without answering applies the same penalty as a wrong guess.
5. **Leaderboards** update every 15 seconds and display total points, streak, and best streak.
6. Attachments and Discord embeds (images, GIFs, video, links) render inline.

## Command-line Flags
| Flag | Description |
| ---- | ----------- |
| `--db PATH` | Seed a room from a Discord SQLite export |
| `--room-name NAME` | Friendly name for the seeded room |
| `--port PORT` | HTTP port (default `8080`) |
| `--rooms-dir DIR` | Directory to persist room databases |
| `--web-root DIR` | Static asset directory (default `public/`) |

The Windows script uses equivalent parameters: `-DiscordDb`, `-RoomName`, `-Port`.

## API Reference
- `POST /api/rooms` – Form-urlencoded body with `dbBase64` (base64 SQLite file) and optional `roomName`. Returns `{ roomId, displayName }`.
- `GET /api/rooms?roomId=ID` – Returns `{ roomId, displayName, leaderboard: [...] }`.
- `GET /api/random-message?roomId=ID&username=NAME` – Returns question data (content, attachments, choices, score snapshot).
- `POST /api/guess` – Form-urlencoded `roomId`, `username`, `questionId`, `choiceId`. Returns scoring results.
- `POST /api/context` – Form-urlencoded `roomId`, `username`, `questionId`. Deducts points and returns previous/next message context.

## Project Structure
```
build_and_run.sh        # macOS/Linux helper script
build_and_run.ps1       # Windows helper script
Dockerfile
pom.xml                 # Maven build definition
public/                 # Front-end (HTML, CSS, JS)
src/main/java/io/guessauthor/jeopardy/  # Java backend
rooms/                  # Created at runtime for uploaded DBs (ignored by git)
.maven/                 # Auto-downloaded Maven (ignored by git)
```

## Deployment Guide
1. Build the Docker image and push to a registry (ECR/GCR/ Docker Hub).
2. Run on ECS Fargate, Kubernetes, or another orchestrator behind an HTTPS load balancer.
3. Mount `/app/rooms` to persistent storage (Amazon EFS, Kubernetes PVC, etc.).
4. Obtain TLS certificates (ACM, Let’s Encrypt) and enforce HTTPS.
5. Add monitoring (CloudWatch, Prometheus) and optionally protect endpoints with WAF or IP allowlists.
6. For CI/CD, set up pipelines to `mvn package`, build/push the Docker image, and deploy.

## Troubleshooting
- **Maven missing** – Scripts auto-install Maven 3.9.6. Double-check the script output if the download fails.
- **Jar not found** – Ensure `mvn -B clean package` succeeded; check `target/` contents and Maven logs.
- **SLF4J warning** – Add `slf4j-simple` or another binding if you need logging output (warning is harmless).
- **Port already in use** – Scripts attempt to kill existing processes; otherwise, choose a different `--port` / `-Port`.
- **Database has no eligible messages** – Verify the export includes `messages` and `participants` with non-bot entries.

## Security Notes
- Serve the app behind HTTPS; terminate TLS at your load balancer or reverse proxy.
- Restrict access to `/rooms` directory (never expose database files publicly).
- Validate uploads server-side (size checks already in place; add malware scanning for production).
- Run the Docker container as a non-root user if you extend the image.
- Review logs and consider rate limiting, authentication, or WAF for public deployments.

## License
MIT (replace with your preferred license before publishing).
