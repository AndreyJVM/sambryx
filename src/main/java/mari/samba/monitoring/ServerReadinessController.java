package mari.samba.monitoring;

import jakarta.servlet.http.HttpSession;
import mari.samba.core.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/monitoring/readiness")
public class ServerReadinessController {
    private final ServerReadinessService service;

    public ServerReadinessController(ServerReadinessService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<ReadinessReport> inspect(HttpSession session) {
        return ApiResponse.ok(service.inspect(session.getId()));
    }
}
