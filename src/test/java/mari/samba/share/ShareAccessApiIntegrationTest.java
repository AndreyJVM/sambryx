package mari.samba.share;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import mari.samba.infra.CommandResult;
import mari.samba.infra.SshSessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ShareAccessApiIntegrationTest {
    @Autowired
    MockMvc mvc;

    @MockitoBean
    SshSessionManager ssh;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anonymousCannotDiagnose() throws Exception {
        mvc.perform(get("/api/shares/team/access-check").param("username", "alice"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(ssh);
    }

    @Test
    void authenticatedReportUsesRealServicesAndOnlyReadCommands() throws Exception {
        var session = login();
        when(ssh.execute(eq(session.getId()), anyString())).thenReturn("[team]\npath = /srv/team\n");
        when(ssh.executeCommand(eq(session.getId()), anyString(), isNull())).thenAnswer(invocation -> {
            String command = invocation.getArgument(1);
            String output = command.startsWith("testparm")
                    ? "[global]\nsecurity = user\n[team]\npath = /srv/team\navailable = no\n"
                    : "";
            return new CommandResult(0, output, "");
        });
        mvc.perform(get("/api/shares/team/access-check")
                        .session(session)
                        .param("username", "alice")
                        .param("path", "/etc/shadow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.share").value("team"))
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.findings[?(@.code == 'UNAVAILABLE')].status")
                        .value("BLOCKING"));
        var commands = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(ssh, atLeastOnce()).executeCommand(eq(session.getId()), commands.capture(), isNull());
        org.assertj.core.api.Assertions.assertThat(commands.getAllValues())
                .allMatch(c -> c.startsWith("testparm ") || c.startsWith("getent ") || c.startsWith("realpath "));
        verify(ssh).execute(session.getId(), "cat -- '/etc/samba/smb.conf'");
    }

    @Test
    void invalidUsernameIsRejectedBeforeRemoteInspection() throws Exception {
        var session = login();
        clearInvocations(ssh);
        mvc.perform(get("/api/shares/team/access-check").session(session).param("username", "-root;id"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(ssh);
    }

    @Test
    void configurationReadFailureIsSanitized() throws Exception {
        var session = login();
        when(ssh.execute(eq(session.getId()), anyString()))
                .thenThrow(new mari.samba.core.SambaCommandException("synthetic-secret"));
        mvc.perform(get("/api/shares/team/access-check").session(session).param("username", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.findings[0].status").value("UNKNOWN"))
                .andExpect(content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("synthetic-secret"))));
    }

    private MockHttpSession login() throws Exception {
        return (MockHttpSession) mvc.perform(
                        post("/api/auth/login")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"host\":\"samba-node\",\"port\":22,\"username\":\"operator\",\"password\":\"test-only\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getRequest()
                .getSession(false);
    }
}
