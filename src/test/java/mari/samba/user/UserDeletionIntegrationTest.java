package mari.samba.user;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import mari.samba.auth.ConnectionRequestDto;
import mari.samba.config.SecurityConfig;
import mari.samba.core.SambaCommandException;
import mari.samba.infra.ExecutionSessionManager;
import mari.samba.infra.LinuxCommands;
import mari.samba.infra.SshSessionManager;
import mari.samba.session.AdministratorSessions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(UserApiController.class)
@Import({SecurityConfig.class, SambaUserService.class, ExecutionSessionManager.class})
@Epic("Users and groups")
@Feature("HTTP security chain: user deletion (mock SSH)")
class UserDeletionIntegrationTest {
    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SshSessionManager commands;

    @Autowired
    private ExecutionSessionManager execution;

    @Autowired
    private AdministratorSessions lifecycle;

    @DisplayName("Anonymous deletion returns unauthorized without commands")
    @Test
    void anonymousDeletionDoesNotExecuteCommands() throws Exception {
        mvc.perform(delete("/api/users/alice").with(csrf())).andExpect(status().isUnauthorized());
        verifyNoInteractions(commands);
    }

    @DisplayName("Deletion without CSRF returns forbidden without commands")
    @Test
    @WithMockUser
    void deletionRequiresCsrf() throws Exception {
        mvc.perform(delete("/api/users/alice").session(connectedSession())).andExpect(status().isForbidden());
        verifyNoInteractions(commands);
    }

    @DisplayName("Protected account deletion returns forbidden without commands")
    @Test
    @WithMockUser
    void protectedUserIsRejectedWithoutCommands() throws Exception {
        mvc.perform(delete("/api/users/root").session(connectedSession()).with(csrf()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(commands);
    }

    @DisplayName("Authenticated deletion issues only Samba removal then nonrecursive Linux account removal")
    @Test
    @WithMockUser
    void accountDeletionPreservesFiles() throws Exception {
        var session = connectedSession();
        mvc.perform(delete("/api/users/alice").session(session).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        var order = inOrder(commands);
        order.verify(commands).execute(session.getId(), "sudo smbpasswd -x 'alice'");
        order.verify(commands).execute(session.getId(), "sudo userdel -- 'alice'");
        verifyNoMoreInteractions(commands);
    }

    @DisplayName("Samba deletion failure returns an HTTP error without Linux deletion")
    @Test
    @WithMockUser
    void sambaFailureReturnsErrorAndStopsDeletion() throws Exception {
        var session = connectedSession();
        when(commands.execute(session.getId(), LinuxCommands.deleteSambaUser("alice")))
                .thenThrow(new SambaCommandException("Samba deletion failed"));
        mvc.perform(delete("/api/users/alice").session(session).with(csrf()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false));
        verify(commands).execute(session.getId(), LinuxCommands.deleteSambaUser("alice"));
        verifyNoMoreInteractions(commands);
    }

    @DisplayName("Linux deletion failure returns an HTTP error describing partial completion")
    @Test
    @WithMockUser
    void linuxFailureReturnsPartialCompletion() throws Exception {
        var session = connectedSession();
        when(commands.execute(session.getId(), LinuxCommands.deleteSambaUser("alice")))
                .thenReturn("");
        when(commands.execute(session.getId(), LinuxCommands.deleteSystemUser("alice")))
                .thenThrow(new SambaCommandException("Linux deletion failed"));
        mvc.perform(delete("/api/users/alice").session(session).with(csrf()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(
                        jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Samba account was deleted")));
        verify(commands).execute(session.getId(), LinuxCommands.deleteSambaUser("alice"));
        verify(commands).execute(session.getId(), LinuxCommands.deleteSystemUser("alice"));
        verifyNoMoreInteractions(commands);
    }

    private MockHttpSession connectedSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        execution.createSession(
                session.getId(),
                new ConnectionRequestDto("fixture.test", 22, "operator", "password", "synthetic", null, null));
        session.setAttribute("sambaUser", "operator");
        lifecycle.register(session);
        clearInvocations(commands);
        return session;
    }
}
