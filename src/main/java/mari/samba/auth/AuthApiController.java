package mari.samba.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.Map;
import mari.samba.core.ApiResponse;
import mari.samba.core.SambaCommandException;
import mari.samba.core.SshHostKeyException;
import mari.samba.infra.ExecutionSessionManager;
import mari.samba.session.AdministratorSessions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

/**
 * Controller handling authentication and session management.
 *
 * <p>Provides endpoints to authenticate users (via SSH), retrieve current session details, and
 * perform logout operations. Incorporates Spring Security session contexts and basic protection
 * against brute-force attacks via injected services.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthApiController {

    private final AuthService authService;
    private final AdministratorSessions administratorSessions;

    /**
     * Constructs a new {@link AuthApiController}.
     *
     * <p>Provides a fallback default implementation of {@link AuthService} (i.e., {@link
     * AuthServiceImpl}) if no such bean is provided by the Spring context, which is primarily useful
     * for targeted slice-tests.
     *
     * @param sessionManager manages SSH execution sessions
     * @param bruteForceService provides tracking and limiting for login attempts
     * @param authService the overarching authentication service (optional)
     * @param administratorSessions owns the administrator activity deadline
     */
    public AuthApiController(
            ExecutionSessionManager sessionManager,
            BruteForceProtectionService bruteForceService,
            @Autowired(required = false) AuthService authService,
            AdministratorSessions administratorSessions) {
        this.authService = authService != null ? authService : new AuthServiceImpl(sessionManager, bruteForceService);
        this.administratorSessions = administratorSessions;
    }

    @GetMapping("/environment")
    public ApiResponse<AuthEnvironmentDto> getEnvironment() {
        return ApiResponse.ok(new AuthEnvironmentDto("remote"));
    }

    /**
     * Uses the servlet client address. Forwarded headers must be handled by a trusted proxy
     * configuration in the container, never by parsing client-supplied headers here.
     *
     * @param request the HTTP request
     * @return the resolved client IP address
     */
    private String getClientIp(@NonNull HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    /**
     * Authenticates a user and establishes a remote secure session.
     *
     * <p>Upon successful authentication, the authenticated user is populated into the {@link
     * SecurityContextHolder} and appropriate session attributes are created.
     *
     * @param request the connection credentials DTO
     * @param httpRequest the raw external HTTP request for determining IP and session
     * @return a {@link ResponseEntity} with an {@link ApiResponse} wrapping the authentication status
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<?>> connect(
            @Valid @RequestBody ConnectionRequestDto request, @NonNull HttpServletRequest httpRequest) {

        String clientIp = getClientIp(httpRequest);

        try {
            HttpSession session = prepareLoginSession(httpRequest);
            String sessionId = session.getId();

            authService.login(sessionId, request, clientIp);

            try {
                publishSession(session, request);
            } catch (IllegalStateException e) {
                authService.logout(sessionId);
                throw e;
            }

            return ResponseEntity.ok(ApiResponse.ok("Authentication successful", null));

        } catch (SshHostKeyException e) {
            var details = new HostKeyFailure(e.code(), e.fingerprint());
            var status = e.reason() == SshHostKeyException.Reason.UNAVAILABLE
                    ? HttpStatus.SERVICE_UNAVAILABLE
                    : HttpStatus.CONFLICT;
            return ResponseEntity.status(status).body(new ApiResponse<>(false, e.getMessage(), details));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        } catch (SambaCommandException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("Authentication failed: " + e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Login error: " + e.getMessage()));
        }
    }

    private void publishSession(HttpSession session, ConnectionRequestDto request) {
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, SecurityContextHolder.getContext());
        String hostDisplay =
                request.getHost() + (request.getResolvedPort() != 22 ? ":" + request.getResolvedPort() : "");
        session.setAttribute("sambaHost", hostDisplay);
        session.setAttribute("sambaUser", request.getUsername());
        administratorSessions.register(session);
    }

    public record HostKeyFailure(String code, String fingerprint) {}

    private HttpSession prepareLoginSession(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        String previousId = session.getId();
        administratorSessions.forget(previousId);
        boolean previouslyAuthenticated =
                session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) != null
                        || session.getAttribute("sambaUser") != null;

        SecurityContextHolder.clearContext();
        session.removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        session.removeAttribute("sambaHost");
        session.removeAttribute("sambaUser");
        if (previouslyAuthenticated) {
            authService.logout(previousId);
        }
        // Execution sessions are keyed by HTTP session ID, so rotate before opening a connection.
        request.changeSessionId();
        return session;
    }

    /**
     * Retrieves information about the currently authenticated user session.
     *
     * <p>Normally this forces the creation/validation of a CSRF token. If a user is missing
     * credentials in the session context, it responds with an UNAUTHORIZED status.
     *
     * @param request the HTTP request used for CSRF token retrieval
     * @param httpSession the user's HTTP session holding session keys
     * @return an {@link ApiResponse} with a map containing current user properties
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<Map<String, String>>> getCurrentUser(
            @NonNull HttpServletRequest request, @Nullable HttpSession httpSession) {

        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken != null) {
            csrfToken.getToken();
        }

        if (httpSession == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String host = (String) httpSession.getAttribute("sambaHost");
        String user = (String) httpSession.getAttribute("sambaUser");

        if (host == null || user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Map<String, String> data = authService.getCurrentUserInfo(host, user);
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    /**
     * Logs out the current user, unbinds the remote connections, and invalidates the session.
     *
     * @param session the user's current HTTP session bound for invalidation
     * @return a 200 OK {@link ResponseEntity} indicating successful logout
     */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(@Nullable HttpSession session) {
        if (session != null) {
            administratorSessions.forget(session.getId());
            authService.logout(session.getId());
            session.invalidate();
        } else {
            authService.logout(null);
        }
        return ResponseEntity.ok(ApiResponse.ok("Logged out successfully", null));
    }
}
