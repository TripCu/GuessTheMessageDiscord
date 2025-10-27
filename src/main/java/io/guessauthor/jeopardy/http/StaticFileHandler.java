package io.guessauthor.jeopardy.http;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static io.guessauthor.jeopardy.util.HttpUtil.respondWithStatus;

public final class StaticFileHandler implements HttpHandler {

    private final Path baseDir;

    public StaticFileHandler(Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            respondWithStatus(exchange, 405, "Method Not Allowed");
            return;
        }

        Path target = resolvePath(exchange.getRequestURI().getPath());
        if (target == null || !Files.exists(target) || Files.isDirectory(target)) {
            respondWithStatus(exchange, 404, "Not Found");
            return;
        }

        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", detectContentType(target));
        headers.set("Cache-Control", "no-cache, no-store, must-revalidate");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");

        if ("HEAD".equalsIgnoreCase(method)) {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }

        byte[] content = Files.readAllBytes(target);
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(content);
        }
    }

    private Path resolvePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank() || "/".equals(rawPath)) {
            return baseDir.resolve("index.html");
        }
        String trimmed = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;
        Path resolved = baseDir.resolve(trimmed).normalize();
        if (!resolved.startsWith(baseDir)) {
            return null;
        }
        return resolved;
    }

    private static String detectContentType(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.US);
        if (fileName.endsWith(".html") || fileName.endsWith(".htm")) {
            return "text/html; charset=utf-8";
        }
        if (fileName.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (fileName.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (fileName.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        if (fileName.endsWith(".png")) {
            return "image/png";
        }
        if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (fileName.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (fileName.endsWith(".webp")) {
            return "image/webp";
        }
        return "application/octet-stream";
    }
}
