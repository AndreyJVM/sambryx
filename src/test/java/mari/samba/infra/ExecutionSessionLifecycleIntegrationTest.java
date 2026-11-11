package mari.samba.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import mari.samba.auth.ConnectionRequestDto;
import mari.samba.core.SshSessionExpiredException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ExecutionSessionLifecycleIntegrationTest.FixtureController.class)
@Epic("Authentication and sessions")
@Feature("HTTP server component: servlet session lifecycle (mock SSH)")
class ExecutionSessionLifecycleIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ExecutionSessionManager sessions;

    @MockitoBean
    private SshSessionManager ssh;

    @DisplayName("Servlet session invalidation closes execution connection only once")
    @Test
    void servletSessionInvalidationClosesExecutionConnectionOnlyOnce() throws Exception {
        try (HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .build()) {
            String sessionId = get(client, "create");
            assertThat(sessionId).isNotBlank();

            assertThat(get(client, "invalidate")).isEqualTo("invalidated");

            verify(ssh).disconnect(sessionId);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> sessions.execute(sessionId, "unused"))
                    .isInstanceOf(SshSessionExpiredException.class);
            sessions.disconnect(sessionId);
            verify(ssh, times(1)).disconnect(sessionId);
            verify(ssh, never()).execute(anyString(), anyString());
        }
    }

    private String get(HttpClient client, String action) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/ui/session-fixture/" + action))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    @RestController
    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureController {
        private final ExecutionSessionManager sessions;

        FixtureController(ExecutionSessionManager sessions) {
            this.sessions = sessions;
        }

        @GetMapping("/ui/session-fixture/create")
        String create(HttpServletRequest request) throws Exception {
            String id = request.getSession(true).getId();
            sessions.createSession(
                    id, new ConnectionRequestDto("fixture.test", 22, "fixture", "password", "synthetic", null, null));
            return id;
        }

        @GetMapping("/ui/session-fixture/invalidate")
        String invalidate(HttpServletRequest request) {
            HttpSession session = request.getSession(false);
            if (session == null) throw new IllegalStateException("Expected fixture session");
            session.invalidate();
            return "invalidated";
        }
    }
}
