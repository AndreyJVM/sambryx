package sambryx.browser;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class BrowserServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;

    BrowserServer(Path dist) {
        Path root = dist.toAbsolutePath().normalize();
        if (!Files.isRegularFile(root.resolve("index.html")))
            throw new IllegalArgumentException("Build frontend/dist before running browser tests");
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            Path file =
                    path.startsWith("/ui/") ? root.resolve(path.substring(4)).normalize() : null;
            if (!exchange.getRequestMethod().equals("GET") || file == null || !file.startsWith(root)) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            if (!Files.isRegularFile(file) && !path.contains(".") && !path.startsWith("/ui/assets/"))
                file = root.resolve("index.html");
            if (!Files.isRegularFile(file)) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            String type =
                    switch (file.getFileName().toString().replaceFirst(".*\\.", "")) {
                        case "html" -> "text/html";
                        case "js" -> "application/javascript";
                        case "css" -> "text/css";
                        case "svg" -> "image/svg+xml";
                        default -> "application/octet-stream";
                    };
            byte[] bytes = Files.readAllBytes(file);
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
    }

    URI origin() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }
}
