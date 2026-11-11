package mari.samba.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import mari.samba.infra.CommandResult;
import mari.samba.infra.SshSessionManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Epic("Monitoring and readiness")
@Feature("HTTP security chain: readiness after login (mock SSH)")
class ServerReadinessIntegrationTest {
    @Autowired
    MockMvc mvc;

    @MockitoBean
    SshSessionManager ssh;

    @Test
    @DisplayName("Anonymous readiness requests return HTTP 401 without SSH calls")
    void anonymousRequestCannotReachSsh() throws Exception {
        mvc.perform(get("/api/monitoring/readiness")).andExpect(status().isUnauthorized());
        verifyNoInteractions(ssh);
    }

    @Test
    @DisplayName("After mocked SSH login rejected probes return a partial sanitized readiness report")
    void authenticatedSshWithRejectedProbeReturnsPartialSanitizedReport() throws Exception {
        var session = (MockHttpSession) mvc.perform(
                        post("/api/auth/login")
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        """
                {"host":"readiness.fixture.test","port":22,"username":"operator","password":"synthetic"}
                """))
                .andExpect(status().isOk())
                .andReturn()
                .getRequest()
                .getSession(false);
        when(ssh.executeCommand(eq(session.getId()), anyString(), isNull()))
                .thenReturn(new CommandResult(1, "synthetic-secret", "synthetic-secret"));
        var response = mvc.perform(get("/api/monitoring/readiness").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ready").value(false))
                .andExpect(jsonPath("$.data.checks.length()").value(5))
                .andExpect(jsonPath("$.data.checks[0].status").value("FAILED"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(response).doesNotContain("synthetic-secret");
        verify(ssh, times(4)).executeCommand(eq(session.getId()), anyString(), isNull());
        verify(ssh, never()).execute(anyString(), anyString());
    }
}
