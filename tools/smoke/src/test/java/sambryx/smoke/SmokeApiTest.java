package sambryx.smoke;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SmokeApiTest {
    @Test
    void sendsCsrfCookieTokenWithoutLoggingCredentials() throws Exception {
        var token = new AtomicReference<String>();
        var cookie = new AtomicReference<String>();
        withServer(200, "{\"success\":true,\"data\":{\"mode\":\"remote\"}}", (api, server) -> {
            server.createContext("/api/auth/login", exchange -> {
                token.set(exchange.getRequestHeaders().getFirst("X-XSRF-TOKEN"));
                cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
                exchange.getRequestBody().readAllBytes();
                byte[] result = "{\"success\":true,\"data\":null}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, result.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(result);
                }
            });
            assertEquals(
                    "remote",
                    api.data("GET", "/api/auth/environment", null).path("mode").asText());
            api.data("POST", "/api/auth/login", Map.of("privateKey", "synthetic-key"));
            assertEquals("fixture-token", token.get());
            assertTrue(cookie.get().contains("XSRF-TOKEN=fixture-token"));
        });
    }

    @Test
    void retainsHostIdentityConflictStatusAndDetails() throws Exception {
        withServer(
                409,
                "{\"success\":false,\"data\":{\"code\":\"SSH_HOST_KEY_CHANGED\",\"fingerprint\":\"SHA256:fixture\"}}",
                (api, server) -> {
                    var result = api.request("GET", "/api/auth/login", null);
                    assertEquals(409, result.status());
                    assertEquals(
                            "SSH_HOST_KEY_CHANGED",
                            result.body().path("data").path("code").asText());
                    assertEquals(
                            "SHA256:fixture",
                            result.body().path("data").path("fingerprint").asText());
                    assertThrows(IllegalStateException.class, () -> api.data("GET", "/api/auth/login", null));
                });
    }

    @Test
    void missingCsrfTokenPreventsMutation() throws Exception {
        withServer(
                200,
                "{\"success\":true}",
                (api, server) ->
                        assertThrows(IllegalStateException.class, () -> api.data("POST", "/api/users", Map.of())));
    }

    @Test
    void unsuccessfulEnvelopeIsRejectedEvenWithHttp200() throws Exception {
        withServer(
                200,
                "{\"success\":false}",
                (api, server) -> assertThrows(
                        IllegalStateException.class, () -> api.data("GET", "/api/auth/environment", null)));
    }

    @FunctionalInterface
    interface Scenario {
        void run(SmokeApi api, HttpServer server) throws Exception;
    }

    private void withServer(int status, String body, Scenario scenario) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "XSRF-TOKEN=fixture-token; Path=/; SameSite=Lax");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        try (var client = HttpClient.newBuilder().cookieHandler(cookies).build()) {
            scenario.run(
                    new SmokeApi(
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort()), client, cookies),
                    server);
        } finally {
            server.stop(0);
        }
    }
}
