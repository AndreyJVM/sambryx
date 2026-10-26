package mari.samba.session;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import mari.samba.core.SshSessionExpiredException;
import org.springframework.web.filter.OncePerRequestFilter;

public class AdministratorSessionFilter extends OncePerRequestFilter {
    private final AdministratorSessions sessions;

    public AdministratorSessionFilter(AdministratorSessions sessions) {
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        if (path.isEmpty()) path = request.getRequestURI();
        if (!path.startsWith("/api/")
                || path.equals("/api/auth/environment")
                || path.equals("/api/auth/logout")
                || path.equals("/api/auth/login")) {
            chain.doFilter(request, response);
            return;
        }
        boolean activity = java.util.Set.of("POST", "PUT", "PATCH", "DELETE").contains(request.getMethod());
        try (var ignored = sessions.admit(request.getSession(false), activity)) {
            chain.doFilter(request, response);
        } catch (SshSessionExpiredException e) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }
}
