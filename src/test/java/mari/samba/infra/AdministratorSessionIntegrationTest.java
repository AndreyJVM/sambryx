package mari.samba.infra;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import({
    AdministratorSessionIntegrationTest.TimeConfiguration.class,
    AdministratorSessionIntegrationTest.RegistrationProbe.class
})
class AdministratorSessionIntegrationTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    TestClock clock;

    @MockitoBean
    SshSessionManager ssh;

    @Autowired
    mari.samba.session.AdministratorSessions lifecycle;

    @Autowired
    RegistrationProbe probe;

    @Autowired
    ExecutionSessionManager execution;

    private final Instant start = Instant.parse("2026-10-27T00:00:00Z");

    @BeforeEach
    void resetTime() {
        clock.now.set(start);
    }

    @Test
    void pollingDoesNotExtendDeadlineAndExpiredRequestsNeverReachSsh() throws Exception {
        MockHttpSession session = login();
        clock.now.set(start.plusSeconds(899));
        when(ssh.execute(eq(session.getId()), anyString())).thenReturn("");
        mvc.perform(get("/api/monitoring/dashboard").session(session)).andExpect(status().isOk());
        clearInvocations(ssh);
        clock.now.set(start.plusSeconds(900));
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
        verify(ssh).disconnect(session.getId());
        assertThat(session.isInvalid()).isTrue();
        verify(ssh, never()).execute(anyString(), anyString());
    }

    @Test
    void explicitActivityExtendsOnlyBeforeDeadline() throws Exception {
        MockHttpSession session = login();
        clock.now.set(start.plusSeconds(899));
        mvc.perform(post("/api/auth/activity").with(csrf()).session(session)).andExpect(status().isOk());
        clock.now.set(start.plusSeconds(900));
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
        clock.now.set(start.plusSeconds(1799));
        mvc.perform(post("/api/auth/activity").with(csrf()).session(session)).andExpect(status().isUnauthorized());
        verify(ssh).disconnect(session.getId());
    }

    @Test
    void activityRequiresCsrfAndCannotBeRestoredByAnAnonymousRequest() throws Exception {
        MockHttpSession session = login();
        mvc.perform(post("/api/auth/activity").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout").with(csrf()).session(session)).andExpect(status().isOk());
        mvc.perform(post("/api/auth/activity").with(csrf())).andExpect(status().isUnauthorized());
        verify(ssh, times(1)).disconnect(session.getId());
    }

    @Test
    void cleanupExpiresIdleSessionWithoutAnyBrowserRequest() throws Exception {
        MockHttpSession session = login();
        clock.now.set(start.plusSeconds(900));
        lifecycle.cleanup();
        assertThat(session.isInvalid()).isTrue();
        lifecycle.cleanup();
        verify(ssh, times(1)).disconnect(session.getId());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void acceptedRestoreFinishesAfterRevocationAndRejectsAnotherRequestBeforeNextCommand(boolean logout)
            throws Exception {
        MockHttpSession session = login();
        var commands = new java.util.ArrayList<String>();
        when(ssh.execute(eq(session.getId()), anyString())).thenAnswer(invocation -> {
            commands.add(invocation.getArgument(1));
            if (commands.size() == 1) {
                if (logout) {
                    mvc.perform(post("/api/auth/logout").with(csrf()).session(session))
                            .andExpect(status().isOk());
                } else {
                    clock.now.set(start.plusSeconds(900));
                    lifecycle.cleanup();
                }
                verify(ssh, never()).disconnect(session.getId());
                mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
            }
            return "";
        });
        mvc.perform(post("/api/config/backups/smb.conf.backup_20261027_000000/restore")
                        .with(csrf())
                        .session(session))
                .andExpect(status().isOk());
        assertThat(commands).hasSize(6);
        assertThat(commands.getLast()).contains("restart");
        verify(ssh).disconnect(session.getId());
    }

    @Test
    void optionsDoesNotCountAsAdministratorActivity() throws Exception {
        MockHttpSession session = login();
        clock.now.set(start.plusSeconds(899));
        mvc.perform(options("/api/auth/me").session(session)).andExpect(status().isOk());
        clock.now.set(start.plusSeconds(900));
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void delayedCleanupCannotGrantExtraDrainTimeBeyondOriginalIdleDeadline() throws Exception {
        MockHttpSession session = login();
        when(ssh.execute(eq(session.getId()), anyString())).thenAnswer(invocation -> {
            clock.now.set(start.plusSeconds(1200));
            lifecycle.cleanup();
            verify(ssh).disconnect(session.getId());
            return "";
        });
        mvc.perform(get("/api/config/raw").session(session)).andExpect(status().isOk());
        verify(ssh, times(1)).disconnect(session.getId());
    }

    @Test
    void expiredMutationCannotStartRemoteWorkflow() throws Exception {
        MockHttpSession session = login();
        clock.now.set(start.plusSeconds(900));
        mvc.perform(post("/api/config/backups").with(csrf()).session(session)).andExpect(status().isUnauthorized());
        verify(ssh, never()).execute(anyString(), anyString());
        verify(ssh).disconnect(session.getId());
    }

    @Test
    void successfulLoginRegistersDeadlineBeforeResponseBodyIsWritten() throws Exception {
        probe.enabled = true;
        try {
            login();
            assertThat(probe.registered).isTrue();
        } finally {
            probe.enabled = false;
        }
    }

    @Test
    void previousDeadlineCannotInvalidateSuccessfullyReauthenticatedSession() throws Exception {
        MockHttpSession session = login();
        clock.now.set(start.plusSeconds(899));
        mvc.perform(
                        post("/api/auth/login")
                                .with(csrf())
                                .session(session)
                                .contentType("application/json")
                                .content(
                                        """
                {"host":"fixture.test","port":22,"username":"operator","password":"synthetic"}
                """))
                .andExpect(status().isOk());
        clock.now.set(start.plusSeconds(900));
        lifecycle.cleanup();
        assertThat(session.isInvalid()).isFalse();
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
    }

    @org.springframework.web.bind.annotation.RestControllerAdvice
    static class RegistrationProbe
            implements org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice<Object> {
        private final mari.samba.session.AdministratorSessions sessions;
        boolean enabled;
        boolean registered;

        RegistrationProbe(mari.samba.session.AdministratorSessions sessions) {
            this.sessions = sessions;
        }

        public boolean supports(
                org.springframework.core.MethodParameter method,
                Class<? extends org.springframework.http.converter.HttpMessageConverter<?>> converter) {
            return enabled
                    && method.getMethod() != null
                    && method.getMethod().getName().equals("connect");
        }

        public Object beforeBodyWrite(
                Object body,
                org.springframework.core.MethodParameter method,
                org.springframework.http.MediaType type,
                Class<? extends org.springframework.http.converter.HttpMessageConverter<?>> converter,
                org.springframework.http.server.ServerHttpRequest request,
                org.springframework.http.server.ServerHttpResponse response) {
            try {
                sessions.status(
                        ((org.springframework.http.server.ServletServerHttpRequest) request)
                                .getServletRequest()
                                .getSession(false),
                        false);
                registered = true;
            } catch (mari.samba.core.SshSessionExpiredException e) {
                registered = false;
            }
            return body;
        }
    }

    @Test
    void sessionInvalidatedDuringSshConnectCannotLeaveAnOrphanConnection() throws Exception {
        MockHttpSession session = new MockHttpSession();
        doAnswer(invocation -> {
                    session.invalidate();
                    return null;
                })
                .when(ssh)
                .createSession(anyString(), any());
        mvc.perform(
                        post("/api/auth/login")
                                .with(csrf())
                                .session(session)
                                .contentType("application/json")
                                .content(
                                        """
                {"host":"fixture.test","port":22,"username":"operator","password":"synthetic"}
                """))
                .andExpect(status().isInternalServerError());
        verify(ssh).disconnect(session.getId());
        assertThatThrownBy(() -> execution.execute(session.getId(), "unused"))
                .isInstanceOf(mari.samba.core.SshSessionExpiredException.class);
    }

    private MockHttpSession login() throws Exception {
        return (MockHttpSession) mvc.perform(
                        post("/api/auth/login")
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        """
                {"host":"fixture.test","port":22,"username":"operator","password":"synthetic"}
                """))
                .andExpect(status().isOk())
                .andReturn()
                .getRequest()
                .getSession(false);
    }

    @TestConfiguration
    static class TimeConfiguration {
        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock();
        }
    }

    static class TestClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-27T00:00:00Z"));

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return now.get();
        }
    }
}
