package mari.samba.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import jakarta.servlet.http.Cookie;
import mari.samba.config.SambaProperties;
import mari.samba.config.SecurityConfig;
import mari.samba.core.SambaCommandException;
import mari.samba.infra.ExecutionSessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthApiController.class)
@Import({
    SecurityConfig.class,
    AuthServiceImpl.class,
    BruteForceProtectionServiceImpl.class,
    LoginThrottleIntegrationTest.PropertiesConfiguration.class
})
@TestPropertySource(properties = "samba.security.max-login-attempts=2")
@Epic("Authentication and sessions")
@Feature("HTTP security chain: login throttling (mock SSH)")
class LoginThrottleIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SambaProperties.class)
    static class PropertiesConfiguration {}

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ExecutionSessionManager sessions;

    @BeforeEach
    void rejectConnections() throws Exception {
        doThrow(new SambaCommandException("Rejected fixture credentials"))
                .when(sessions)
                .createSession(anyString(), any(ConnectionRequestDto.class));
    }

    @DisplayName("Changing forwarded headers and usernames does not bypass IP limit")
    @ParameterizedTest(name = "{argumentsWithNames}")
    @ValueSource(strings = {"X-Forwarded-For", "Forwarded"})
    void changingForwardedHeadersAndUsernamesDoesNotBypassIpLimit(String header) throws Exception {
        String suffix = header.equals("Forwarded") ? "forwarded" : "xff";
        String ip = header.equals("Forwarded") ? "198.51.100.41" : "198.51.100.42";
        Cookie token = mvc.perform(get("/api/auth/environment"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie("XSRF-TOKEN");
        org.junit.jupiter.api.Assertions.assertNotNull(token);

        for (int attempt = 0; attempt < 3; attempt++) {
            String fakeAddress = "203.0.113." + (attempt + 1);
            mvc.perform(post("/api/auth/login")
                            .with(request -> {
                                request.setRemoteAddr(ip);
                                return request;
                            })
                            .header(
                                    header,
                                    header.equals("Forwarded") ? "for=" + fakeAddress : fakeAddress + ", 127.0.0.1")
                            .cookie(token)
                            .header("X-XSRF-TOKEN", token.getValue())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                    """
                            {"host":"fixture.test","username":"%s-user-%d","password":"synthetic-fixture"}
                            """
                                            .formatted(suffix, attempt)))
                    .andExpect(attempt < 2 ? status().isUnauthorized() : status().isTooManyRequests());
        }
        verify(sessions, times(2)).createSession(anyString(), any(ConnectionRequestDto.class));
    }
}
