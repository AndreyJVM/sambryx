package mari.samba.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import mari.samba.core.SambaCommandException;
import mari.samba.core.SshSessionExpiredException;
import mari.samba.infra.CommandResult;
import mari.samba.infra.SshSessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Epic("Monitoring and readiness")
@Feature("HTTP security chain: monitoring read failures (mock SSH)")
class MonitoringReadIntegrationTest {
    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SshSessionManager ssh;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Anonymous dashboard requests return HTTP 401 without SSH calls")
    void anonymousDashboardReadCannotReachSsh() throws Exception {
        mvc.perform(get("/api/monitoring/dashboard")).andExpect(status().isUnauthorized());
        verifyNoInteractions(ssh);
    }

    @ParameterizedTest(name = "endpoint={0}")
    @ValueSource(strings = {"dashboard", "status", "connections", "locks", "disk"})
    @DisplayName("Failed monitoring reads return HTTP 500 without data or sensitive output")
    void failedReadCannotReturnSuccessfulEmptyData(String endpoint) throws Exception {
        var session = login();
        when(ssh.execute(anyString(), anyString())).thenThrow(new SambaCommandException("synthetic-sensitive-output"));
        when(ssh.executeCommand(anyString(), anyString(), isNull()))
                .thenThrow(new SambaCommandException("synthetic-sensitive-output"));

        mvc.perform(get("/api/monitoring/" + endpoint).session(session))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(content()
                        .string(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("synthetic-sensitive-output"))));
        assertThat(mockingDetails(ssh).getInvocations()).hasSize(1);
    }

    @Test
    @DisplayName("An expired SSH read returns HTTP 401 and invalidates the HTTP session")
    void expiredReadInvalidatesHttpSession() throws Exception {
        var session = login();
        when(ssh.execute(anyString(), anyString())).thenThrow(new SshSessionExpiredException("SSH session expired."));
        when(ssh.executeCommand(anyString(), anyString(), isNull()))
                .thenThrow(new SshSessionExpiredException("SSH session expired."));

        mvc.perform(get("/api/monitoring/status").session(session)).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    @DisplayName("Unavailable service status recovers on the next read without another login")
    void unavailableStatusCanRecoverWithoutRelogin() throws Exception {
        var session = login();
        when(ssh.executeCommand(anyString(), anyString(), isNull()))
                .thenReturn(new CommandResult(4, "unknown", "synthetic-sensitive-output"))
                .thenReturn(new CommandResult(0, "active\n", ""));

        mvc.perform(get("/api/monitoring/status").session(session)).andExpect(status().isInternalServerError());
        mvc.perform(get("/api/monitoring/status").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.running").value(true));
    }

    @Test
    @DisplayName("Confirmed inactive service status remains a successful HTTP response")
    void confirmedInactiveStateRemainsSuccessful() throws Exception {
        var session = login();
        when(ssh.executeCommand(anyString(), anyString(), isNull())).thenReturn(new CommandResult(3, "inactive\n", ""));

        mvc.perform(get("/api/monitoring/status").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.running").value(false));
    }

    private MockHttpSession login() throws Exception {
        var session = (MockHttpSession) mvc.perform(
                        post("/api/auth/login")
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        """
                                {"host":"monitoring.fixture.test","port":22,"username":"operator","password":"synthetic"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getRequest()
                .getSession(false);
        clearInvocations(ssh);
        return session;
    }
}
