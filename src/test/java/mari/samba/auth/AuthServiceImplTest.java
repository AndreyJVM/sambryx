package mari.samba.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Named.named;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.util.stream.Stream;
import mari.samba.core.SambaCommandException;
import mari.samba.infra.ExecutionSessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
@Epic("Authentication and sessions")
@Feature("Unit: authentication publication and safe diagnostics (mock sessions)")
class AuthServiceImplTest {
    @Mock
    private ExecutionSessionManager sessions;

    @Mock
    private BruteForceProtectionService bruteForce;

    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        service = new AuthServiceImpl(sessions, bruteForce);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static Stream<Arguments> missingPasswords() {
        return Stream.of(
                Arguments.of(named("null password", null)),
                Arguments.of(named("empty password", "")),
                Arguments.of(named("blank password", "   ")));
    }

    @DisplayName("Missing password does not create session")
    @ParameterizedTest(name = "{0}")
    @MethodSource("missingPasswords")
    void missingPasswordDoesNotCreateSession(String password) {
        ConnectionRequestDto request =
                new ConnectionRequestDto("server", 22, "admin", "password", password, null, null);

        assertThatThrownBy(() -> service.login("session", request, "127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Password is required");

        verifyNoInteractions(sessions);
        verify(bruteForce).isBlocked("127.0.0.1", "admin");
        verifyNoMoreInteractions(bruteForce);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private static Stream<Arguments> missingPrivateKeys() {
        return Stream.of(
                Arguments.of(named("null private key", null)),
                Arguments.of(named("empty private key", "")),
                Arguments.of(named("blank private key", "   ")));
    }

    @DisplayName("Missing private key does not create session")
    @ParameterizedTest(name = "{0}")
    @MethodSource("missingPrivateKeys")
    void missingPrivateKeyDoesNotCreateSession(String privateKey) {
        ConnectionRequestDto request = new ConnectionRequestDto("server", 22, "admin", "key", null, privateKey, null);

        assertThatThrownBy(() -> service.login("session", request, "127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Private key is required");

        verifyNoInteractions(sessions);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private static Stream<Arguments> missingHosts() {
        return Stream.of(
                Arguments.of(named("null host", null)),
                Arguments.of(named("empty host", "")),
                Arguments.of(named("blank host", "   ")));
    }

    @DisplayName("Missing host does not create session")
    @ParameterizedTest(name = "{0}")
    @MethodSource("missingHosts")
    void missingHostDoesNotCreateSession(String host) {
        ConnectionRequestDto request = new ConnectionRequestDto(host, 22, "admin", "password", "test-only", null, null);
        assertThatThrownBy(() -> service.login("session", request, "127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Host is required");
        verifyNoInteractions(sessions);
        verify(bruteForce, never()).resetFailedLogin(anyString(), anyString());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private static Stream<Arguments> authenticationMethods() {
        return Stream.of(
                Arguments.of(named("password authentication", false)), Arguments.of(named("key authentication", true)));
    }

    @DisplayName("Successful SSH connection is required before publishing authentication")
    @ParameterizedTest(name = "{0}")
    @MethodSource("authenticationMethods")
    void successfulSshConnectionIsRequiredBeforePublishingAuthentication(boolean keyAuth) throws Exception {
        ConnectionRequestDto request = connectionRequest(keyAuth);
        doAnswer(invocation -> {
                    assertThat(SecurityContextHolder.getContext().getAuthentication())
                            .isNull();
                    verify(bruteForce, never()).resetFailedLogin(anyString(), anyString());
                    return null;
                })
                .when(sessions)
                .createSession("session", request);

        service.login("session", request, "127.0.0.1");

        assertThat(SecurityContextHolder.getContext().getAuthentication().isAuthenticated())
                .isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName())
                .isEqualTo("admin");
        verify(sessions).createSession("session", request);
        verify(bruteForce).resetFailedLogin("127.0.0.1", "admin");
    }

    private static Stream<Arguments> failedAuthenticationMethods() {
        return Stream.of(
                Arguments.of(named("password authentication", false)), Arguments.of(named("key authentication", true)));
    }

    @DisplayName("Connection failure registers attempt without authenticating")
    @ParameterizedTest(name = "{0}")
    @MethodSource("failedAuthenticationMethods")
    void connectionFailureRegistersAttemptWithoutAuthenticating(boolean keyAuth) throws Exception {
        ConnectionRequestDto request = connectionRequest(keyAuth);
        SambaCommandException failure = new SambaCommandException("Connection rejected");
        doThrow(failure).when(sessions).createSession("session", request);

        assertThatThrownBy(() -> service.login("session", request, "127.0.0.1")).isSameAs(failure);

        verify(bruteForce).registerFailedLogin("127.0.0.1", "admin");
        verify(bruteForce, never()).resetFailedLogin(anyString(), anyString());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private static Stream<Arguments> loginOutcomes() {
        return Stream.of(Arguments.of(named("successful login", false)), Arguments.of(named("failed login", true)));
    }

    @DisplayName("Authentication logs cannot forge entries or expose SSH exception text")
    @ParameterizedTest(name = "{0}")
    @MethodSource("loginOutcomes")
    void authenticationLogsCannotForgeEntriesOrExposeSshExceptionText(boolean failure) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(AuthServiceImpl.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        String secret = "synthetic-secret-do-not-log";
        ConnectionRequestDto request =
                new ConnectionRequestDto("server\r\nforged", 22, "admin\r\nforged", "password", secret, null, null);
        if (failure) {
            doThrow(new SambaCommandException(secret + "\r\nforged"))
                    .when(sessions)
                    .createSession("session", request);
        }
        try {
            if (failure) {
                assertThatThrownBy(() -> service.login("session", request, "127.0.0.1\r\nforged"))
                        .isInstanceOf(SambaCommandException.class);
            } else {
                service.login("session", request, "127.0.0.1\r\nforged");
            }
            assertThat(events.list).isNotEmpty().allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("\r", "\n", secret);
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    @DisplayName("Logout without session clears authentication without disconnecting")
    @Test
    void logoutWithoutSessionClearsAuthenticationWithoutDisconnecting() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", null));

        service.logout(null);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(sessions, bruteForce);
    }

    private ConnectionRequestDto connectionRequest(boolean keyAuth) {
        return new ConnectionRequestDto(
                "server",
                22,
                "admin",
                keyAuth ? "key" : "password",
                keyAuth ? null : "test-only",
                keyAuth ? "synthetic-key" : null,
                keyAuth ? "synthetic-passphrase" : null);
    }
}
