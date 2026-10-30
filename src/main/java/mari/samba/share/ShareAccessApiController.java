package mari.samba.share;

import jakarta.servlet.http.HttpSession;
import mari.samba.core.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ShareAccessApiController {
    private final ShareAccessService service;

    public ShareAccessApiController(ShareAccessService service) {
        this.service = service;
    }

    @GetMapping("/api/shares/{share}/access-check")
    public ApiResponse<ShareAccessReport> check(
            @PathVariable String share, @RequestParam String username, HttpSession session) {
        return ApiResponse.ok(service.check(session.getId(), share, username));
    }
}
