package mari.samba.ad;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import mari.samba.core.ApiResponse;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ad")
public class AdApiController {

    private final AdIntegrationService adService;

    public AdApiController(@NonNull AdIntegrationService adService) {
        this.adService = adService;
    }

    @GetMapping("/status")
    public ApiResponse<AdStatusDto> getStatus(@NonNull HttpSession session) {
        return ApiResponse.ok(adService.getStatus(session.getId()));
    }

    @PostMapping("/join")
    public ApiResponse<AdStatusDto> joinDomain(
            @NonNull HttpSession session, @NonNull @Valid @RequestBody AdJoinRequestDto request) {
        AdStatusDto status = adService.joinDomain(session.getId(), request);
        return ApiResponse.ok("Successfully joined domain", status);
    }

    @PostMapping("/leave")
    public ApiResponse<AdStatusDto> leaveDomain(
            @NonNull HttpSession session, @NonNull @Valid @RequestBody AdJoinRequestDto request) {
        AdStatusDto status = adService.leaveDomain(session.getId(), request);
        return ApiResponse.ok("Successfully left domain", status);
    }
}
