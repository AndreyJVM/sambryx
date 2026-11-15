package mari.samba.filesystem;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import mari.samba.core.SambaCommandException;
import mari.samba.infra.LinuxCommands;
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
@Epic("Filesystem")
@Feature("HTTP security chain: private directory preparation (real services, mock SSH)")
class PrivateDirectoryHttpIntegrationTest {
    private static final String REQUEST =
            """
            {"parentPath":"/srv/samba","dirName":"private","preparation":
            {"owner":"alice","group":"private","mode":"0700","confirmed":true}}
            """;

    @Autowired
    MockMvc mvc;

    @MockitoBean
    SshSessionManager ssh;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anonymousAndMissingCsrfCannotCreateDirectories() throws Exception {
        mvc.perform(post("/api/fs/mkdir")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(ssh);
        var session = login();
        clearInvocations(ssh);
        mvc.perform(post("/api/fs/mkdir")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().isForbidden());
        verifyNoInteractions(ssh);
    }

    @Test
    void confirmedPreparationUsesCurrentSessionAndReportsFailureHonestly() throws Exception {
        var session = login();
        clearInvocations(ssh);
        when(ssh.execute(eq(session.getId()), anyString()))
                .thenThrow(new SambaCommandException("synthetic-private-details"));
        mvc.perform(post("/api/fs/mkdir")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("may remain")))
                .andExpect(content()
                        .string(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("synthetic-private-details"))));
        verify(ssh).execute(eq(session.getId()), contains("mkdir -m 0700"));
        verifyNoMoreInteractions(ssh);
    }

    @Test
    void confirmedPreparationReturnsSuccessOnlyAfterCommandSuccess() throws Exception {
        var session = login();
        clearInvocations(ssh);
        mvc.perform(post("/api/fs/mkdir")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        verify(ssh).execute(eq(session.getId()), contains("chown --no-dereference"));
        verifyNoMoreInteractions(ssh);
    }

    @Test
    void unconfirmedUnsafeAndOutOfRootRequestsNeverExecuteCommands() throws Exception {
        var session = login();
        clearInvocations(ssh);
        for (String request : new String[] {REQUEST.replace("true", "false"), REQUEST.replace("0700", "0777")}) {
            mvc.perform(post("/api/fs/mkdir")
                            .session(session)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(request))
                    .andExpect(status().isBadRequest());
        }
        for (String request :
                new String[] {REQUEST.replace("alice", "@group"), REQUEST.replace("/srv/samba", "/etc")}) {
            mvc.perform(post("/api/fs/mkdir")
                            .session(session)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(request))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(ssh);
    }

    @Test
    void omittedPreparationDoesNotIssueOwnershipCommands() throws Exception {
        var session = login();
        clearInvocations(ssh);
        mvc.perform(post("/api/fs/mkdir")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentPath\":\"/srv/samba\",\"dirName\":\"ordinary\"}"))
                .andExpect(status().isOk());
        verify(ssh).execute(session.getId(), LinuxCommands.mkdirNew("/srv/samba/ordinary"));
        verifyNoMoreInteractions(ssh);
    }

    private MockHttpSession login() throws Exception {
        return (MockHttpSession) mvc.perform(
                        post("/api/auth/login")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"host\":\"fixture.test\",\"port\":22,\"username\":\"operator\",\"password\":\"test-only\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getRequest()
                .getSession(false);
    }
}
