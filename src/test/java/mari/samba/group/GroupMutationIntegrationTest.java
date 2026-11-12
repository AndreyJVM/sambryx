package mari.samba.group;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import mari.samba.auth.ConnectionRequestDto;
import mari.samba.config.SecurityConfig;
import mari.samba.core.SambaCommandException;
import mari.samba.infra.ExecutionSessionManager;
import mari.samba.infra.SshSessionManager;
import mari.samba.session.AdministratorSessions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(GroupApiController.class)
@Import({SecurityConfig.class, SambaGroupService.class, ExecutionSessionManager.class})
@Epic("Users and groups")
@Feature("HTTP security chain: group mutation failures (mock SSH)")
class GroupMutationIntegrationTest {
    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SshSessionManager commands;

    @Autowired
    private ExecutionSessionManager execution;

    @Autowired
    private AdministratorSessions lifecycle;

    @DisplayName("Group mutation failure returns an error without follow-up commands or nested cause details")
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"create group", "delete group", "add member", "remove member"})
    @WithMockUser
    void failedMutationNeverReturnsSuccess(String operation) throws Exception {
        var session = connectedSession();
        String command =
                switch (operation) {
                    case "create group" -> "sudo groupadd 'smb_engineering'";
                    case "delete group" -> "sudo groupdel 'smb_engineering'";
                    case "add member" -> "sudo gpasswd -a 'alice' 'smb_engineering'";
                    case "remove member" -> "sudo gpasswd -d 'alice' 'smb_engineering'";
                    default -> throw new IllegalArgumentException("Unknown group operation");
                };
        when(commands.execute(session.getId(), command))
                .thenThrow(new SambaCommandException(
                        "Group operation rejected", new IllegalStateException("internal-cause-marker")));

        MockHttpServletRequestBuilder request =
                switch (operation) {
                    case "create group" -> post("/api/groups")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"groupName\":\"engineering\"}");
                    case "delete group" -> delete("/api/groups/engineering");
                    case "add member" -> post("/api/groups/engineering/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"alice\"}");
                    case "remove member" -> delete("/api/groups/engineering/users/alice");
                    default -> throw new IllegalArgumentException("Unknown group operation");
                };
        mvc.perform(request.session(session).with(csrf()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Samba command failed: Group operation rejected"))
                .andExpect(content().string(not(containsString("internal-cause-marker"))));

        verify(commands).execute(session.getId(), command);
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
