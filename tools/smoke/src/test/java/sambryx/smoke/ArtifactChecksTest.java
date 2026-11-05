package sambryx.smoke;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ArtifactChecksTest {
    @TempDir
    Path directory;

    @Test
    void extractsPackagedFrontendBytes() throws Exception {
        var files = ArtifactChecks.frontend(jar("/ui/assets/app.js", true));
        assertEquals(3, files.size());
        assertArrayEquals("fixture-js".getBytes(StandardCharsets.UTF_8), files.get("/ui/assets/app.js"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://example.test/app.js",
                "//example.test/app.js",
                "/other/app.js",
                "/ui/assets/%2e%2e/private.js"
            })
    void rejectsExternalOrEscapingAssetReferences(String reference) throws Exception {
        assertThrows(IllegalStateException.class, () -> ArtifactChecks.frontend(jar(reference, true)));
    }

    @Test
    void rejectsMissingBundle() throws Exception {
        assertThrows(IllegalStateException.class, () -> ArtifactChecks.frontend(jar("/ui/assets/app.js", false)));
    }

    @Test
    void verifiesActualHttpResponses() throws Exception {
        verify("fixture-js", "{\"success\":true,\"data\":{\"mode\":\"remote\"}}", 401);
    }

    @Test
    void rejectsDifferentServedBytes() {
        assertThrows(
                IllegalStateException.class,
                () -> verify("different", "{\"success\":true,\"data\":{\"mode\":\"remote\"}}", 401));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"success\":true,\"data\":{\"mode\":\"local\"}}", "{\"success\":false}", "not JSON"})
    void rejectsInvalidExecutionEnvironment(String environment) {
        assertThrows(Exception.class, () -> verify("fixture-js", environment, 401));
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 302, 403, 500})
    void rejectsUnexpectedAnonymousStatus(int status) {
        assertThrows(
                IllegalStateException.class,
                () -> verify("fixture-js", "{\"success\":true,\"data\":{\"mode\":\"remote\"}}", status));
    }

    private Path jar(String script, boolean includeBundle) throws Exception {
        Path jar = directory.resolve("fixture.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            entry(
                    zip,
                    "ui/index.html",
                    "<script src=\"" + script + "\"></script><link rel=\"stylesheet\" href=\"/ui/assets/app.css\">");
            entry(zip, "ui/assets/app.css", "fixture-css");
            if (includeBundle) entry(zip, "ui/assets/app.js", "fixture-js");
        }
        return jar;
    }

    private void entry(ZipOutputStream zip, String path, String text) throws Exception {
        zip.putNextEntry(new ZipEntry("BOOT-INF/classes/static/" + path));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private void verify(String body, String environment, int anonymousStatus) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String response = path.equals("/api/auth/environment") ? environment : body;
            int status = path.equals("/api/auth/me") ? anonymousStatus : 200;
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        try {
            ArtifactChecks.verifyHttp(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    Map.of("/ui/", "fixture-js".getBytes(StandardCharsets.UTF_8)));
        } finally {
            server.stop(0);
        }
    }
}
