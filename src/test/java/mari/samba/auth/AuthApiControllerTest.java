package mari.samba.auth;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import mari.samba.infra.ExecutionSessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(AuthApiController.class)
@AutoConfigureMockMvc(addFilters = false)
@org.springframework.context.annotation.Import(mari.samba.config.SecurityConfig.class)
class AuthApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ExecutionSessionManager sessionManager;

    @MockitoBean
    private mari.samba.auth.BruteForceProtectionService bruteForceService;

    @Test
    void reportsRemoteEnvironment() throws Exception {
        mockMvc.perform(get("/api/auth/environment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("remote"));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @CsvSource({"192.168.1.100, root", "samba-node, admin"})
    void validHostAuthenticates(String host, String username) throws Exception {
        ConnectionRequestDto dto = new ConnectionRequestDto(host, 22, username, "password", "synthetic", null, null);

        mockMvc.perform(loginRequest(dto))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(sessionManager).createSession(anyString(), eq(dto));
    }

    @Test
    void loginWithoutHostIsRejectedBeforeOpeningConnection() throws Exception {
        ConnectionRequestDto dto = new ConnectionRequestDto(null, 22, "admin", "password", "secret", null, null);
        mockMvc.perform(loginRequest(dto)).andExpect(status().isBadRequest());
        verifyNoInteractions(sessionManager);
    }

    @ParameterizedTest
    @CsvSource({
        "password, Password is required when not using key authentication.",
        "key, Private key is required when using key authentication."
    })
    void missingCredentialIsRejected(String authType, String message) throws Exception {
        ConnectionRequestDto dto = new ConnectionRequestDto("192.168.1.100", 22, "root", authType, null, null, null);

        mockMvc.perform(loginRequest(dto))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(message));

        verifyNoInteractions(sessionManager);
    }

    @Test
    void testGetCurrentUser() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .sessionAttr("sambaHost", "192.168.1.100")
                        .sessionAttr("sambaUser", "root"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.host").value("192.168.1.100"))
                .andExpect(jsonPath("$.data.user").value("root"));
    }

    @Test
    void testLogout() throws Exception {
        mockMvc.perform(post("/api/auth/logout").sessionAttr("dummy", "dummy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").isString());

        verify(sessionManager).disconnect(anyString());
    }

    private MockHttpServletRequestBuilder loginRequest(ConnectionRequestDto dto) throws Exception {
        return post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto));
    }
}
