package sambryx.smoke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

final class SmokeApi implements AutoCloseable {
    record Response(int status, JsonNode body) {}

    private final URI base;
    private final HttpClient client;
    private final CookieManager cookies;
    private final ObjectMapper json = new ObjectMapper();

    SmokeApi(URI base, HttpClient client, CookieManager cookies) {
        this.base = base;
        this.client = client;
        this.cookies = cookies;
    }

    static SmokeApi trusted(URI base, Path certificate) throws Exception {
        var store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null);
        try (var stream = Files.newInputStream(certificate)) {
            store.setCertificateEntry(
                    "fixture", CertificateFactory.getInstance("X.509").generateCertificate(stream));
        }
        var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        var tls = SSLContext.getInstance("TLS");
        tls.init(null, trust.getTrustManagers(), null);
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        return new SmokeApi(
                base,
                HttpClient.newBuilder()
                        .sslContext(tls)
                        .cookieHandler(cookies)
                        .connectTimeout(Duration.ofSeconds(10))
                        .build(),
                cookies);
    }

    Response request(String method, String path, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(60));
        var publisher = HttpRequest.BodyPublishers.noBody();
        if (body != null) {
            builder.header("Content-Type", "application/json")
                    .header("X-XSRF-TOKEN", cookie("XSRF-TOKEN").getValue());
            publisher = HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
        }
        var response = client.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), json.readTree(response.body()));
    }

    JsonNode data(String method, String path, Object body) throws Exception {
        var response = request(method, path, body);
        if (response.status() != 200 || !response.body().path("success").asBoolean()) {
            throw new IllegalStateException(
                    "API operation failed: " + method + " " + path + " (HTTP " + response.status() + ")");
        }
        return response.body().path("data");
    }

    HttpCookie cookie(String name) {
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing expected cookie: " + name));
    }

    @Override
    public void close() {
        client.close();
    }
}
