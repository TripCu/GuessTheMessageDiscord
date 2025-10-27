# Jeopardy Guess-The-Author Server

Self-hosted Discord export guessing game with support for multiplayer rooms, responsive UI, and Docker/Maven builds.

## Quick Start

### Prerequisites
- Java 21
- Maven 3.9+
- Git

### Local Run
```bash
chmod +x build_and_run.sh
./build_and_run.sh --db path/to/discord_messages.db --room-name "Party" --port 8080
```

Open `http://localhost:8080`, create or join a room, and share the room ID with others on the same network. Omit `--db` to upload a database through the UI later.

### Docker
```bash
docker build -t jeopardy-server .
docker run --rm -p 8080:8080 \
           -v "$(pwd)/rooms:/app/rooms" \
           jeopardy-server
```

Optional environment variables accepted by the container:
- `SERVER_PORT` (default `8080`)
- `ROOMS_DIR` (`/app/rooms` by default)
- `WEB_ROOT` (`/app/public` by default)
- `JAVA_OPTS`

### CLI Flags
```
--db PATH        Path to a Discord SQLite export to seed a default room
--room-name NAME Display name for the seeded room
--port PORT      HTTP port (default 8080)
--rooms-dir DIR  Directory for persistent room storage
--web-root DIR   Static asset directory
```

## Repository Layout
```
pom.xml                     Maven build file
Dockerfile                  Multi-stage Docker build
build_and_run.sh            Local build/run helper
src/main/java/io/guessauthor/...  Server source code
public/                     Front-end assets
rooms/                      (created at runtime) uploaded room databases
target/                     (generated) build output
```

## Deployment Outline (AWS)
1. Build and push the Docker image to Amazon ECR.
2. Run it on ECS Fargate (or EKS/Beanstalk) behind an Application Load Balancer.
3. Request an ACM TLS certificate and map a Route 53 DNS record to the ALB.
4. Back `/app/rooms` with EFS or S3 for persistence.
5. Add CloudWatch metrics/alarms and optional AWS WAF protection.

## License
MIT (add your chosen license here).
