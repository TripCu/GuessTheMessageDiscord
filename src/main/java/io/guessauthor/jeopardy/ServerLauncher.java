package io.guessauthor.jeopardy;

import com.sun.net.httpserver.HttpServer;
import io.guessauthor.jeopardy.http.ContextHandler;
import io.guessauthor.jeopardy.http.GuessHandler;
import io.guessauthor.jeopardy.http.RandomMessageHandler;
import io.guessauthor.jeopardy.http.RoomsHandler;
import io.guessauthor.jeopardy.http.StaticFileHandler;
import io.guessauthor.jeopardy.rooms.RoomManager;
import io.guessauthor.jeopardy.rooms.RoomManager.RoomCreationResult;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ServerLauncher {

    private static final double BASE_POINTS = 1_000.0;
    private static final double DECAY_PER_SECOND = 25.0;
    private static final double STREAK_BONUS_STEP = 0.2;
    private static final double CONTEXT_PERCENTAGE = 0.10;
    private static final Duration QUESTION_EXPIRY = Duration.ofMinutes(10);

    private ServerLauncher() {
    }

    public static void main(String[] args) throws IOException {
        if (!ensureSqliteDriver()) {
            System.err.println("SQLite JDBC driver not found. Place sqlite-jdbc.jar on the classpath.");
            return;
        }

        Config config;
        try {
            config = parseArguments(args);
        } catch (IllegalArgumentException ex) {
            System.err.println(ex.getMessage());
            printUsage();
            return;
        }

        Files.createDirectories(config.roomsDir);

        RoomManager roomManager = new RoomManager(
            config.roomsDir,
            BASE_POINTS,
            DECAY_PER_SECOND,
            STREAK_BONUS_STEP,
            CONTEXT_PERCENTAGE,
            QUESTION_EXPIRY
        );

        if (config.databasePath != null) {
            try {
                RoomCreationResult result = roomManager.createRoomFromPath(config.roomName, config.databasePath);
                System.out.printf("Default room created. Share this room id: %s%n", result.roomId());
            } catch (Exception ex) {
                throw new IOException("Failed to create default room from database path.", ex);
            }
        } else {
            System.out.println("No database provided. Use POST /api/rooms to create a room.");
        }

        if (!Files.exists(config.webRoot)) {
            System.err.printf("Warning: web root '%s' does not exist. Static files may 404.%n", config.webRoot);
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(config.port), 0);
        server.createContext("/api/rooms", new RoomsHandler(roomManager));
        server.createContext("/api/random-message", new RandomMessageHandler(roomManager));
        server.createContext("/api/guess", new GuessHandler(roomManager));
        server.createContext("/api/context", new ContextHandler(roomManager));
        server.createContext("/", new StaticFileHandler(config.webRoot));

        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();

        String hostAddress = resolveHostAddress();
        System.out.printf(Locale.US, "Server running at http://%s:%d/%n", hostAddress, config.port);
    }

    private static boolean ensureSqliteDriver() {
        try {
            Class.forName("org.sqlite.JDBC");
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

    private static void printUsage() {
        System.out.println("Usage: java ... io.guessauthor.jeopardy.ServerLauncher [--db PATH] [--room-name NAME] [--port PORT] [--rooms-dir DIR] [--web-root DIR]");
    }

    private static Config parseArguments(String[] args) {
        Config config = new Config();
        int i = 0;
        while (i < args.length) {
            String arg = args[i];
            switch (arg) {
                case "--db" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--db requires a path");
                    }
                    config.databasePath = Path.of(args[++i]);
                }
                case "--room-name" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--room-name requires a value");
                    }
                    config.roomName = args[++i];
                }
                case "--port" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--port requires a value");
                    }
                    config.port = parsePortValue(args[++i], config.port);
                }
                case "--rooms-dir" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--rooms-dir requires a path");
                    }
                    config.roomsDir = Path.of(args[++i]);
                }
                case "--web-root" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--web-root requires a path");
                    }
                    config.webRoot = Path.of(args[++i]);
                }
                default -> {
                    if (arg.startsWith("--")) {
                        throw new IllegalArgumentException("Unknown argument: " + arg);
                    }
                    if (config.databasePath == null) {
                        config.databasePath = Path.of(arg);
                    } else {
                        config.port = parsePortValue(arg, config.port);
                    }
                }
            }
            i++;
        }
        config.roomsDir = config.roomsDir.toAbsolutePath().normalize();
        config.webRoot = config.webRoot.toAbsolutePath().normalize();
        return config;
    }

    private static int parsePortValue(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value);
            return (parsed >= 1 && parsed <= 65_535) ? parsed : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String resolveHostAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
            InetAddress fallback = InetAddress.getLocalHost();
            return fallback.getHostAddress();
        } catch (Exception ex) {
            return "localhost";
        }
    }

    private static final class Config {
        Path databasePath;
        String roomName;
        int port = 8080;
        Path roomsDir = Path.of(System.getenv().getOrDefault("ROOMS_DIR", "rooms"));
        Path webRoot = Path.of(System.getenv().getOrDefault("WEB_ROOT", "public"));
    }
}
