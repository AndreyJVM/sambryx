package mari.samba.user;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.util.stream.Stream;
import mari.samba.auth.ConnectionRequestDto;
import mari.samba.config.SecurityConfig;
import mari.samba.core.SambaCommandException;
import mari.samba.core.SshSessionExpiredException;
import mari.samba.infra.ExecutionSessionManager;
import mari.samba.infra.LinuxCommands;
import mari.samba.infra.SshSessionManager;
import mari.samba.session.AdministratorSessions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
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

@WebMvcTest(UserApiController.class)
@Import({SecurityConfig.class, SambaUserService.class, ExecutionSessionManager.class})
@Epic("Users and groups")
@Feature("HTTP security chain: user creation and password failures (mock SSH)")
@WithMockUser
class UserMutationIntegrationTest {
    private static final String PASSWORD = "synthetic-password";
    private static final String PRIVATE_CAUSE = "private-boundary-detail";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SshSessionManager commands;

    @Autowired
    private ExecutionSessionManager execution;

    @Autowired
    private AdministratorSessions lifecycle;

    @DisplayName("Mutation failures return HTTP errors without exposing nested causes or continuing commands")
    @ParameterizedTest(name = "{0}, {1}")
    @CsvSource({"create, command", "create, unexpected", "password, command", "password, unexpected"})
    void mutationFailureReturnsSafeError(String operation, String failureKind) throws Exception {
        var session = connectedSession();
        RuntimeException failure = failureKind.equals("command")
                ? new SambaCommandException("Account operation rejected", new IllegalStateException(PRIVATE_CAUSE))
                : new IllegalStateException(PRIVATE_CAUSE);
        failLinuxPasswordStep(session, failure);

        mvc.perform(mutation(operation).session(session).with(csrf()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value(
                                failureKind.equals("command")
                                        ? "Samba command failed: Account operation rejected"
                                        : "An unexpected error occurred."))
                .andExpect(content().string(not(containsString(PRIVATE_CAUSE))))
                .andExpect(content().string(not(containsString(PASSWORD))));

        verifyThroughLinuxPasswordStep(session, operation);
    }

    @DisplayName("SSH expiry during mutation invalidates the HTTP session and requires login before retry")
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"create", "password"})
    void mutationExpiryRequiresNewLogin(String operation) throws Exception {
        var session = connectedSession();
        failLinuxPasswordStep(session, new SshSessionExpiredException("SSH session expired. Please log in again."));

        mvc.perform(mutation(operation).session(session).with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("SSH session expired. Please log in again."));

        assertTrue(session.isInvalid());
        verifyThroughLinuxPasswordStep(session, operation);
        clearInvocations(commands);
        mvc.perform(mutation(operation).session(new MockHttpSession()).with(csrf()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(commands);
    }

    @DisplayName("Invalid account DTO fields return bad request before any SSH commands")
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    void invalidDtoDoesNotReachSsh(String invalidField) throws Exception {
        var request =
                switch (invalidField) {
                    case "create username" -> post("/api/users")
                            .content("{\"username\":\" \",\"password\":\"synthetic-password\"}");
                    case "create password" -> post("/api/users").content("{\"username\":\"alice\",\"password\":\" \"}");
                    case "new password" -> put("/api/users/alice/password").content("{\"newPassword\":\" \"}");
                    default -> throw new IllegalArgumentException("Unknown invalid field");
                };
        mvc.perform(request.contentType(MediaType.APPLICATION_JSON)
                        .session(connectedSession())
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("Validation error:")))
                .andExpect(content().string(not(containsString(PASSWORD))));
        verifyNoInteractions(commands);
    }

    private static Stream<String> invalidRequests() {
        return Stream.of("create username", "create password", "new password");
    }

    private void failLinuxPasswordStep(MockHttpSession session, RuntimeException failure) {
        when(commands.execute(session.getId(), LinuxCommands.chpasswd(), "alice:" + PASSWORD + "\n"))
                .thenThrow(failure);
    }

    private void verifyThroughLinuxPasswordStep(MockHttpSession session, String operation) {
        var order = inOrder(commands);
        if (operation.equals("create")) {
            order.verify(commands).execute(session.getId(), LinuxCommands.addSystemUserWithHome("alice", "Alice"));
        }
        order.verify(commands).execute(session.getId(), LinuxCommands.chpasswd(), "alice:" + PASSWORD + "\n");
        verifyNoMoreInteractions(commands);
    }

    private MockHttpServletRequestBuilder mutation(String operation) {
        return switch (operation) {
            case "create" -> post("/api/users")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"alice\",\"fullName\":\"Alice\",\"password\":\"" + PASSWORD + "\"}");
            case "password" -> put("/api/users/alice/password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"newPassword\":\"" + PASSWORD + "\"}");
            default -> throw new IllegalArgumentException("Unknown user operation");
        };
    }

    private MockHttpSession connectedSession() throws Exception {
        var session = new MockHttpSession();
        execution.createSession(
                session.getId(),
                new ConnectionRequestDto("fixture.test", 22, "operator", "password", "synthetic", null, null));
        session.setAttribute("sambaUser", "operator");
        lifecycle.register(session);
        clearInvocations(commands);
        return session;
    }
}
