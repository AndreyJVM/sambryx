package sambryx.smoke;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipFile;
import org.jsoup.Jsoup;

final class ArtifactChecks {
    private static final String PREFIX = "BOOT-INF/classes/static";

    static Map<String, byte[]> frontend(Path jar) throws IOException {
        try (var zip = new ZipFile(jar.toFile())) {
            byte[] index = read(zip, "/ui/index.html");
            var document = Jsoup.parse(new String(index, StandardCharsets.UTF_8));
            var files = new LinkedHashMap<String, byte[]>();
            files.put("/ui/", index);
            for (var element : document.select("script[src], link[rel=stylesheet][href]")) {
                String reference = element.attr(element.tagName().equals("script") ? "src" : "href");
                URI uri = URI.create(reference);
                String path = uri.getPath();
                if (uri.isAbsolute()
                        || uri.getRawAuthority() != null
                        || path == null
                        || !path.startsWith("/ui/assets/")
                        || !URI.create(path).normalize().getPath().equals(path)) {
                    throw new IllegalStateException("UI bundles must use local /ui/assets/ URLs");
                }
                files.put(path, read(zip, path));
            }
            if (files.keySet().stream().noneMatch(path -> path.endsWith(".js"))
                    || files.keySet().stream().noneMatch(path -> path.endsWith(".css"))) {
                throw new IllegalStateException("Packaged UI must contain JavaScript and CSS bundles");
            }
            return Map.copyOf(files);
        }
    }

    static void verifyHttp(URI base, Map<String, byte[]> files) throws IOException, InterruptedException {
        try (var client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            for (var entry : files.entrySet()) {
                var response = get(client, base.resolve(entry.getKey()));
                if (response.statusCode() != 200 || !Arrays.equals(response.body(), entry.getValue())) {
                    throw new IllegalStateException("Served UI resource differs from JAR: " + entry.getKey());
                }
            }
            var environment = get(client, base.resolve("/api/auth/environment"));
            var json = new ObjectMapper().readTree(environment.body());
            if (environment.statusCode() != 200
                    || !json.path("success").asBoolean()
                    || !json.path("data").path("mode").asText().equals("remote")) {
                throw new IllegalStateException("Container must expose the remote execution environment");
            }
            if (get(client, base.resolve("/api/auth/me")).statusCode() != 401) {
                throw new IllegalStateException("Anonymous /api/auth/me must return HTTP 401");
            }
        }
    }

    private static byte[] read(ZipFile zip, String path) throws IOException {
        var entry = zip.getEntry(PREFIX + path);
        if (entry == null) throw new IllegalStateException("Missing packaged resource: " + path);
        try (var stream = zip.getInputStream(entry)) {
            return stream.readAllBytes();
        }
    }

    private static HttpResponse<byte[]> get(HttpClient client, URI uri) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }
}
