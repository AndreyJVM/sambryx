package mari.samba.session;

import jakarta.servlet.http.HttpServletRequest;
import mari.samba.core.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class SessionApiController {
    private final AdministratorSessions sessions;

    public SessionApiController(AdministratorSessions sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/session")
    public ApiResponse<AdministratorSessions.Snapshot> status(HttpServletRequest request) {
        return ApiResponse.ok(sessions.status(request.getSession(false), false));
    }

    @PostMapping("/activity")
    public ApiResponse<AdministratorSessions.Snapshot> activity(HttpServletRequest request) {
        return ApiResponse.ok(sessions.status(request.getSession(false), false));
    }
}
