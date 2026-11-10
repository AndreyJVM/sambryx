package mari.samba.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import mari.samba.core.SambaCommandException;
import mari.samba.core.SshSessionExpiredException;
import mari.samba.infra.CommandResult;
import mari.samba.infra.SshSessionManager;
import org.junit.jupiter.api.AfterEach;
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
    void anonymousDashboardReadCannotReachSsh() throws Exception {
        mvc.perform(get("/api/monitoring/dashboard")).andExpect(status().isUnauthorized());
        verifyNoInteractions(ssh);
    }

    @ParameterizedTest
    @ValueSource(strings = {"dashboard", "status", "connections", "locks", "disk"})
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
    void expiredReadInvalidatesHttpSession() throws Exception {
        var session = login();
        when(ssh.execute(anyString(), anyString())).thenThrow(new SshSessionExpiredException("SSH session expired."));
        when(ssh.executeCommand(anyString(), anyString(), isNull()))
                .thenThrow(new SshSessionExpiredException("SSH session expired."));

        mvc.perform(get("/api/monitoring/status").session(session)).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
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
