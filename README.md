# Guess The Author Server

Self-hosted guessing game that turns a Discord chat export into a multiplayer challenge. Players join web rooms, see random historic messages, and compete to identify who sent them while the server tracks streaks, points, and leaderboards. The back end is pure Java 21 (standard `HttpServer`) with a vanilla HTML/CSS/JS front end served from the same process.

## Feature Highlights
- Multiplayer rooms with randomly generated 10-character IDs and optional friendly names.
- Automatic SQLite ingestion with bot filtering, attachment rendering, and Discord embed support (images, GIFs, videos, rich links).
- Multiple-choice guessing, anti-repeat message deck, and optional paid context (previous/next messages).
- Real-time scoring that decays over time, rewards streaks, halves points on incorrect answers, and exposes leaderboards in the UI and API.
- Room persistence on disk, upload validation (≤25 MB), and responsive layout optimised for phones and desktops.
- Batteries-included Docker and Maven builds for frictionless local runs, CI, or cloud deployment.

## Architecture Overview
- **Language**: Java 21 with packages rooted at `io.guessauthor.jeopardy`.
- **Dependencies**: Only `org.xerial:sqlite-jdbc` (managed by Maven).
- **HTTP stack**: `com.sun.net.httpserver.HttpServer` with custom handlers in `src/main/java/io/guessauthor/jeopardy/http`.
- **Game engine**: Pure Java state machine (`GameEngine`, `MessageDeck`, `GameStats`) managing scoring, streaks, and context.
- **Data access**: `MessageRepository` wraps SQLite queries, filters bot authors, loads attachments, embeds, and candidate choices.
- **Front end**: Static assets in `public/` (responsive HTML/CSS and ES6 JavaScript).
- **Persistence**: Room databases stored under `rooms/` (created at runtime, overridable via CLI/ENV).

## Requirements
- Java Development Kit 21+
- Maven 3.9+
- Git (optional but recommended)
- SQLite Discord export (`*.db`) for real gameplay

## Installation
### macOS
```bash
brew install openjdk@21 maven
```
Ensure `/usr/local/opt/openjdk@21/bin` (or the Homebrew prefix you use) is on `PATH`.

### Windows
1. Install [Microsoft OpenJDK 21](https://learn.microsoft.com/java/openjdk/download) or [Adoptium Temurin 21](https://adoptium.net/).
2. Install Maven via [scoop](https://scoop.sh/) (`scoop install maven`) or download the Apache Maven binary zip and add its `bin` to `PATH`.

### Linux
- Debian/Ubuntu: `sudo apt update && sudo apt install openjdk-21-jdk maven`
- Fedora: `sudo dnf install java-21-openjdk maven`
- Arch: `sudo pacman -S jdk21-openjdk maven`

Verify:
```bash
java -version
mvn -v
```

## Building & Running Locally
### Quick Start Script (recommended)
```bash
chmod +x build_and_run.sh
./build_and_run.sh --db path/to/discord_messages.db --room-name "Friday Night" --port 8080
```
Flags are optional; the script auto-detects `.db` files in the project root if you omit `--db`. Once the server launches, visit `http://localhost:8080`.

### Maven Manual Build
```bash
mvn -B clean package
```
Output: `target/jeopardy-server-1.0.0.jar` (shaded, ready to run anywhere with Java 21).

### Run the Shaded JAR
```bash
java -jar target/jeopardy-server-1.0.0.jar \
  --port 8080 \
  --rooms-dir ./rooms \
  --web-root ./public \
  --db /absolute/path/to/discord_messages.db \
  --room-name "Default Room"
```
All flags are optional; omit `--db` to start without a seeded room.

### Docker Workflow
```bash
docker build -t jeopardy-server .
docker run --rm -p 8080:8080 \
           -v "$(pwd)/rooms:/app/rooms" \
           -e SERVER_PORT=8080 \
           jeopardy-server
```
Optional environment variables:
- `SERVER_PORT` (default `8080`)
- `ROOMS_DIR` (default `/app/rooms`)
- `WEB_ROOT` (default `/app/public`)
- `JAVA_OPTS` (extra JVM flags, e.g. `-Xms256m -Xmx512m`)

### Command-line Options
| Flag | Description |
| ---- | ----------- |
| `--db PATH` | Seed a room from a Discord SQLite export. |
| `--room-name NAME` | Friendly name for the seeded room (fallback: `Room <id>`). |
| `--port PORT` | HTTP port (default `8080`). |
| `--rooms-dir DIR` | Directory for storing uploaded or seeded room databases. |
| `--web-root DIR` | Directory containing static assets to serve. |

## Gameplay & Scoring
1. **Join/Create**: Players enter a room ID or upload a database to create a new room. Room IDs are 10 lowercase alphanumerics.
2. **Choices**: Messages are presented with multiple-choice options. Bots are filtered out from both questions and options.
3. **Scoring**:
   - Base points: 1,000 per question.
   - Time decay: 25 points per elapsed second.
   - Streak multiplier: +20 % per consecutive correct answer (1.0, 1.2, 1.4, …).
   - Incorrect guesses: total points immediately halve; streak resets.
   - Context purchase: costs 200 points, reveals previous/next messages.
   - Question expiry: 10 minutes—expired questions need a redraw.
4. **Attachments & Embeds**: Images, GIFs, and videos render inline; large links display as cards or fall back to anchors.
5. **Leaderboards**: Aggregate per room, sorted by total points, updating every 15 seconds in the browser.

## Room Storage & Persistence
- Upload size limit: 25 MB (checked before writing to disk).
- Stored under the `rooms/` directory (auto-created). Map this directory to persistent storage for long-lived deployments.
- Room IDs map to `<roomId>.db` SQLite files; the repository loads eligible messages (`content` not blank, `is_bot = 0`).
- Sanitised usernames and room names prevent injection and keep leaderboard tidy.

## API Reference
All endpoints live under `/api/*` and accept/return UTF-8 JSON or form data.

### `POST /api/rooms`
- Body: `application/x-www-form-urlencoded` with `dbBase64` (base64 encoded SQLite file) and optional `roomName`.
- Response `201`: `{ "roomId": "...", "displayName": "..." }`
- Errors: `400` (invalid payload or no eligible messages), `413` (file too large), `500` (storage failure).

### `GET /api/rooms?roomId=xxxx`
- Returns `{ "roomId": "...", "displayName": "...", "leaderboard": [ { "username": "...", "totalPoints": 0, ... } ] }`
- Errors: `400` (missing roomId), `404` (unknown room).

### `GET /api/random-message?roomId=xxxx&username=Player`
- Returns a question payload: `questionId`, message content, attachments, embeds, choices, and current score snapshot.
- Errors: `400`, `404`, `503` (deck exhausted or DB issue).

### `POST /api/guess`
- Body: `roomId`, `username`, `questionId`, `choiceId`.
- Response `200`: includes correctness, awarded points, base points, elapsed time, updated totals, and the correct author info.
- Errors: `400` (invalid input), `404` (room or question not found).

### `POST /api/context`
- Body: `roomId`, `username`, `questionId`.
- Response `200`: deducts points and returns `before`/`after` messages plus updated score.
- Errors: `400` (insufficient funds), `404` (question expired), `500` (DB error).

## Static Assets & Responsiveness
- `public/index.html`: room selection, game board, leaderboard (mobile-first layout).
- `public/app.js`: handles REST calls, scoring UI updates, context retrieval, debounce, and sanitisation helpers.
- `public/styles.css`: themed styling with CSS Grid/Flexbox, responsive breakpoints, mobile-friendly tables, and accessible contrast.
You can point the server at a different web root (`--web-root`) if you customise the client or use a separate build pipeline.

## Development Workflow
- Source tree:
  ```
  src/main/java/io/guessauthor/jeopardy/...   core server code
  public/                                     static front-end assets
  rooms/                                      runtime data (created on launch)
  ```
- Java code style: prefer small, focused classes. HTTP handlers live in `http/`, game logic in top-level package, utilities under `util/`.
- Build targets: `mvn -B clean package` and `./build_and_run.sh` both produce the same shaded jar.
- Tests: none yet—consider adding JUnit tests around `MessageRepository` and `GameEngine` for regressions.
- Formatting: keep files ASCII; Maven project encoding is UTF-8.

## Deployment Guide (AWS Example)
1. **Build & Tag**: `docker build -t <account>.dkr.ecr.<region>.amazonaws.com/jeopardy:latest .`
2. **Create ECR repo**: `aws ecr create-repository --repository-name jeopardy`
3. **Push Image**: authenticate (`aws ecr get-login-password | docker login ...`), then `docker push`.
4. **Provision ECS Fargate Service**:
   - Cluster + task definition referencing the pushed image.
   - Container port `8080`, command `sh -c "java $JAVA_OPTS -jar /app/app.jar --port ${SERVER_PORT} ..."`.
   - Mount an EFS access point to `/app/rooms` for persistent databases.
5. **Load Balancer**: Application Load Balancer (ALB) with target group pointing to the service, health check `/`.
6. **TLS & Domain**:
   - Request an ACM certificate for `game.example.com`.
   - Create Route 53 A-record (alias) pointing to the ALB.
7. **Observability & Security**:
   - Enable CloudWatch Logs for the task.
   - Set alarms on CPU/memory and 5XX count.
   - Optionally front with AWS WAF (rate limiting, IP allowlists).
8. **Secrets & Configuration**:
   - Store custom JVM flags or room directories as task definition env vars.
   - Restrict outbound network if running in a VPC with security groups.

Alternative options: deploy to Elastic Beanstalk, set up GitHub Actions → ECR → ECS pipeline, or run on a VM/EC2 with Docker Compose.

## Security Recommendations
- Terminate TLS in front of the server (ALB, Nginx, Caddy, etc.).
- Keep the `/rooms` directory outside public static assets; never serve uploaded DBs directly.
- Validate uploaded databases—server already enforces size and basic content checks; consider adding antivirus scanning in production.
- Run the Docker container as a non-root user if customising the image.
- Use firewall rules/VPC security groups to limit access to the HTTP port.
- Rotate databases periodically and review logs for abuse.

## Troubleshooting
- `mvn: command not found` – Install Maven (see installation section) and reopen your shell.
- `java.lang.UnsupportedClassVersionError` – Ensure you run with Java 21 (older runtimes cannot execute the shaded jar).
- HTTP 404 on static assets – Check `--web-root` flag and that `public/` exists. The default handler resolves `index.html` for `/`.
- `Database has no eligible messages` – Verify the SQLite export contains `messages` and `participants` tables with non-bot authors.
- Questions stop appearing – All messages exhausted for the session. Restart room or upload a new database.

## License
MIT (replace with your preferred licence before publishing).
