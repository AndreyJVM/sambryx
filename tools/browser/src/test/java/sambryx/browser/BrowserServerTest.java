package sambryx.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BrowserServerTest {
    @TempDir
    Path directory;

    @Test
    void servesBuiltAssetsAndSpaRoutesWithoutFallbackForMissingAssets() throws Exception {
        Files.writeString(directory.resolve("index.html"), "fixture-index");
        Files.createDirectory(directory.resolve("assets"));
        Files.writeString(directory.resolve("assets/app.js"), "fixture-js");
        try (var server = new BrowserServer(directory);
                var client = HttpClient.newHttpClient()) {
            var page = client.send(
                    HttpRequest.newBuilder(server.origin().resolve("/ui/shares"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode());
            assertEquals("fixture-index", page.body());
            var asset = client.send(
                    HttpRequest.newBuilder(server.origin().resolve("/ui/assets/app.js"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals("fixture-js", asset.body());
            assertEquals(
                    "application/javascript",
                    asset.headers().firstValue("Content-Type").orElseThrow());
            assertEquals(
                    404,
                    client.send(
                                    HttpRequest.newBuilder(server.origin().resolve("/ui/assets/missing.js"))
                                            .build(),
                                    HttpResponse.BodyHandlers.discarding())
                            .statusCode());
            assertEquals(
                    404,
                    client.send(
                                    HttpRequest.newBuilder(server.origin().resolve("/api/auth/me"))
                                            .build(),
                                    HttpResponse.BodyHandlers.discarding())
                            .statusCode());
        }
    }

    @Test
    void missingBuildFailsRatherThanSilentlyServingNothing() {
        assertThrows(IllegalArgumentException.class, () -> new BrowserServer(directory));
    }
}
