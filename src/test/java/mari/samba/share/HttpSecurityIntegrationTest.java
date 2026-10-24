package mari.samba.share;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.servlet.http.Cookie;
import java.util.concurrent.atomic.AtomicReference;
import mari.samba.auth.AuthApiController;
import mari.samba.auth.AuthServiceImpl;
import mari.samba.auth.BruteForceProtectionService;
import mari.samba.auth.ConnectionRequestDto;
import mari.samba.config.SecurityConfig;
import mari.samba.core.SambaCommandException;
import mari.samba.infra.ExecutionSessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@WebMvcTest({AuthApiController.class, ShareApiController.class})
@Import({SecurityConfig.class, AuthServiceImpl.class})
class HttpSecurityIntegrationTest {
    private static final String LOGIN =
            """
            {"host":"samba-node","port":22,"username":"qa-user","authType":"password","password":"test-only"}
            """;
    private static final String SHARE =
            """
            {"name":"qa-share","path":"/srv/samba/qa-share"}
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ExecutionSessionManager sessions;

    @MockitoBean
    private BruteForceProtectionService bruteForce;

    @MockitoBean
    private SambaShareService shares;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @CsvSource({"bad/host, 22, qa-user", "samba-node, 65536, qa-user", "samba-node, 22, '   '"})
    void invalidSshCoordinatesAreRejectedBeforeConnecting(String host, int port, String username) throws Exception {
        MvcResult result = mvc.perform(withCsrf(post("/api/auth/login"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"host":"%s","port":%d,"username":"%s","password":"synthetic"}
                                """
                                        .formatted(host, port, username)))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertNull(result.getRequest().getSession(false));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        mvc.perform(get("/api/shares")).andExpect(status().isUnauthorized());
        verifyNoInteractions(sessions, bruteForce, shares);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "CHANGED", "UNAVAILABLE"})
    void hostKeyFailureCannotAuthenticateOrReachProtectedResources(String reason) throws Exception {
        var failure = new mari.samba.core.SshHostKeyException(
                mari.samba.core.SshHostKeyException.Reason.valueOf(reason), "SHA256:fixture");
        doThrow(failure).when(sessions).createSession(anyString(), any(ConnectionRequestDto.class));
        var result = mvc.perform(withCsrf(post("/api/auth/login"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN))
                .andExpect(status().is(reason.equals("UNAVAILABLE") ? 503 : 409))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("SSH_HOST_KEY_" + reason))
                .andExpect(jsonPath("$.data.fingerprint").value("SHA256:fixture"))
                .andReturn();
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        var session = (MockHttpSession) result.getRequest().getSession(false);
        assertNull(session.getAttribute("SPRING_SECURITY_CONTEXT"));
        mvc.perform(get("/api/shares").session(session)).andExpect(status().isUnauthorized());
        verify(bruteForce).registerFailedLogin(anyString(), eq("qa-user"));
        verify(bruteForce, never()).resetFailedLogin(anyString(), anyString());
        verifyNoInteractions(shares);
    }

    @Test
    void anonymousReadsRequireAuthenticationWithoutRedirect() throws Exception {
        for (String path : new String[] {"/api/shares", "/api/auth/me"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Location"));
        }
        verifyNoInteractions(shares);
    }

    @Test
    void anonymousLoginWithoutCsrfDoesNotAttemptAuthentication() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN))
                .andExpect(status().isForbidden());
        verifyNoInteractions(sessions, bruteForce, shares);
    }

    @ParameterizedTest
    @CsvSource({
        "login, missing", "login, cookie-only", "login, header-only", "login, mismatch",
        "logout, missing", "logout, cookie-only", "logout, header-only", "logout, mismatch"
    })
    void rejectedAuthCsrfDoesNotChangeExistingAuthentication(String endpoint, String mode) throws Exception {
        MockHttpSession session = login();
        String sessionId = session.getId();
        Cookie token = csrfCookie(session);
        clearInvocations(sessions, bruteForce);
        MockHttpServletRequestBuilder request = post("/api/auth/" + endpoint)
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(LOGIN);
        switch (mode) {
            case "cookie-only" -> request.cookie(token);
            case "header-only" -> request.header("X-XSRF-TOKEN", token.getValue());
            case "mismatch" -> request.cookie(token).header("X-XSRF-TOKEN", token.getValue() + "-wrong");
            default -> {}
        }

        mvc.perform(request).andExpect(status().isForbidden());

        assertFalse(session.isInvalid());
        assertEquals(sessionId, session.getId());
        verifyNoInteractions(sessions, bruteForce, shares);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
    }

    @Test
    void loginWithCsrfPersistsAuthenticationForReads() throws Exception {
        MockHttpSession session = login();
        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user").value("qa-user"))
                .andExpect(jsonPath("$.data.host").value("samba-node"));
        mvc.perform(get("/api/shares").session(session)).andExpect(status().isOk());
        verify(sessions).createSession(eq(session.getId()), any(ConnectionRequestDto.class));
        verify(bruteForce).resetFailedLogin("127.0.0.1", "qa-user");
        verify(shares).getAllShares(session.getId());
    }

    @Test
    void sshFailureRejectsLoginAndRegistersFailure() throws Exception {
        doThrow(new SambaCommandException("SSH rejected test connection"))
                .when(sessions)
                .createSession(anyString(), any(ConnectionRequestDto.class));
        MvcResult result =
                mvc.perform(loginRequest()).andExpect(status().isUnauthorized()).andReturn();
        verify(bruteForce).registerFailedLogin("127.0.0.1", "qa-user");
        verify(bruteForce, never()).resetFailedLogin(anyString(), anyString());
        mvc.perform(get("/api/shares")
                        .session((MockHttpSession) result.getRequest().getSession(false)))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(shares);
    }

    @Test
    void blockedLoginDoesNotAttemptSsh() throws Exception {
        when(bruteForce.isBlocked("127.0.0.1", "qa-user")).thenReturn(true);
        MvcResult result = mvc.perform(loginRequest())
                .andExpect(status().isTooManyRequests())
                .andReturn();
        verify(sessions, never()).createSession(anyString(), any(ConnectionRequestDto.class));
        mvc.perform(get("/api/shares")
                        .session((MockHttpSession) result.getRequest().getSession(false)))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(shares);
    }

    @Test
    void authenticatedWriteWithoutCsrfIsForbidden() throws Exception {
        mvc.perform(write("POST").session(login())).andExpect(status().isForbidden());
        verifyNoInteractions(shares);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE"})
    void mismatchedCsrfRejectsWrites(String method) throws Exception {
        MockHttpSession session = login();
        Cookie token = csrfCookie(session);
        mvc.perform(write(method).session(session).cookie(token).header("X-XSRF-TOKEN", token.getValue() + "-wrong"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(shares);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE"})
    void matchingCookieAndHeaderAllowExactlyOneWrite(String method) throws Exception {
        MockHttpSession session = login();
        Cookie token = csrfCookie(session);
        mvc.perform(write(method).session(session).cookie(token).header("X-XSRF-TOKEN", token.getValue()))
                .andExpect(status().isOk());
        switch (method) {
            case "POST" -> verify(shares).createShare(eq(session.getId()), any(SambaShareCreateDto.class));
            case "PUT" -> verify(shares)
                    .updateShare(eq(session.getId()), eq("qa-share"), any(SambaShareCreateDto.class));
            case "DELETE" -> verify(shares).deleteShare(session.getId(), "qa-share");
            default -> throw new IllegalArgumentException(method);
        }
        verifyNoMoreInteractions(shares);
    }

    @Test
    void validCsrfDoesNotAuthenticateAnonymousWrite() throws Exception {
        Cookie token = csrfCookie(login());
        mvc.perform(write("POST").cookie(token).header("X-XSRF-TOKEN", token.getValue()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(shares);
    }

    @Test
    void logoutWithCsrfInvalidatesSessionAndDisconnectsOnce() throws Exception {
        MockHttpSession session = login();
        String sessionId = session.getId();
        mvc.perform(withCsrf(post("/api/auth/logout")).session(session)).andExpect(status().isOk());
        assertTrue(session.isInvalid());
        verify(sessions).disconnect(sessionId);
        verify(sessions).createSession(eq(sessionId), any(ConnectionRequestDto.class));
        mvc.perform(get("/api/shares")).andExpect(status().isUnauthorized());
        verifyNoInteractions(shares);
    }

    @Test
    void anonymousLogoutDisconnectsAndInvalidatesSessionCreatedByMvc() throws Exception {
        AtomicReference<MockHttpSession> createdSession = new AtomicReference<>();
        AtomicReference<String> createdSessionId = new AtomicReference<>();
        doAnswer(invocation -> {
                    // MVC resolves the HttpSession argument by creating a session even for anonymous logout.
                    ServletRequestAttributes attributes =
                            (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
                    MockHttpSession session =
                            (MockHttpSession) attributes.getRequest().getSession(false);
                    assertNotNull(session);
                    createdSession.set(session);
                    createdSessionId.set(session.getId());
                    return null;
                })
                .when(sessions)
                .disconnect(anyString());

        mvc.perform(withCsrf(post("/api/auth/logout"))).andExpect(status().isOk());

        assertNotNull(createdSession.get());
        verify(sessions).disconnect(createdSessionId.get());
        assertTrue(createdSession.get().isInvalid());
        mvc.perform(get("/api/shares")).andExpect(status().isUnauthorized());
        verifyNoInteractions(shares);
    }

    @Test
    void anonymousClientCanDiscoverRemoteEnvironment() throws Exception {
        mvc.perform(get("/api/auth/environment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("remote"));
        verifyNoInteractions(sessions, shares, bruteForce);
    }

    @Test
    void loginWithoutHostDoesNotAuthenticateOrAllowProtectedRequests() throws Exception {
        MvcResult result = mvc.perform(withCsrf(post("/api/auth/login"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"test-only\"}"))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertNull(result.getRequest().getSession(false));
        mvc.perform(get("/api/shares")).andExpect(status().isUnauthorized());
        verifyNoInteractions(sessions, shares, bruteForce);
    }

    private MockHttpSession login() throws Exception {
        MvcResult result =
                mvc.perform(loginRequest()).andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session);
        return session;
    }

    @Test
    void loginRotatesExistingAnonymousSessionBeforeOpeningConnection() throws Exception {
        MockHttpSession anonymous = new MockHttpSession();
        String originalId = anonymous.getId();

        MvcResult result = mvc.perform(loginRequest().session(anonymous))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession authenticated = (MockHttpSession) result.getRequest().getSession(false);

        assertNotNull(authenticated);
        assertNotEquals(originalId, authenticated.getId());
        verify(sessions).createSession(eq(authenticated.getId()), any(ConnectionRequestDto.class));
        verify(sessions, never()).createSession(eq(originalId), any(ConnectionRequestDto.class));
        mvc.perform(get("/api/auth/me").session(authenticated)).andExpect(status().isOk());
    }

    @Test
    void failedReloginRemovesPreviousAuthenticationAndConnection() throws Exception {
        MockHttpSession session = login();
        String previousId = session.getId();
        doThrow(new SambaCommandException("Rejected connection"))
                .when(sessions)
                .createSession(anyString(), any(ConnectionRequestDto.class));

        mvc.perform(loginRequest().session(session)).andExpect(status().isUnauthorized());

        assertNotEquals(previousId, session.getId());
        assertNull(session.getAttribute("sambaUser"));
        assertNull(session.getAttribute("sambaHost"));
        verify(sessions).disconnect(previousId);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/shares").session(session)).andExpect(status().isUnauthorized());
        verifyNoInteractions(shares);
    }

    @Test
    void successfulReloginDisconnectsOldConnectionBeforeOpeningNewOne() throws Exception {
        MockHttpSession session = login();
        String previousId = session.getId();
        clearInvocations(sessions);

        mvc.perform(loginRequest().session(session)).andExpect(status().isOk());

        assertNotEquals(previousId, session.getId());
        InOrder order = inOrder(sessions);
        order.verify(sessions).disconnect(previousId);
        order.verify(sessions).createSession(eq(session.getId()), any(ConnectionRequestDto.class));
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
    }

    @Test
    void blockedReloginAlsoRevokesPreviousAuthenticationWithoutOpeningConnection() throws Exception {
        MockHttpSession session = login();
        String previousId = session.getId();
        clearInvocations(sessions);
        when(bruteForce.isBlocked("127.0.0.1", "qa-user")).thenReturn(true);

        mvc.perform(loginRequest().session(session)).andExpect(status().isTooManyRequests());

        assertNotEquals(previousId, session.getId());
        verify(sessions).disconnect(previousId);
        verify(sessions, never()).createSession(anyString(), any(ConnectionRequestDto.class));
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/shares").session(session)).andExpect(status().isUnauthorized());
        verifyNoInteractions(shares);
    }

    private MockHttpServletRequestBuilder loginRequest() throws Exception {
        return withCsrf(post("/api/auth/login"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(LOGIN);
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) throws Exception {
        Cookie token = mvc.perform(get("/api/auth/environment"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie("XSRF-TOKEN");
        assertNotNull(token);
        return request.cookie(token).header("X-XSRF-TOKEN", token.getValue());
    }

    private Cookie csrfCookie(MockHttpSession session) throws Exception {
        Cookie token = mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie("XSRF-TOKEN");
        assertNotNull(token);
        assertFalse(token.getValue().isBlank());
        return token;
    }

    private MockHttpServletRequestBuilder write(String method) {
        return switch (method) {
            case "POST" -> post("/api/shares")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(SHARE);
            case "PUT" -> put("/api/shares/qa-share")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(SHARE);
            case "DELETE" -> delete("/api/shares/qa-share");
            default -> throw new IllegalArgumentException(method);
        };
    }
}
