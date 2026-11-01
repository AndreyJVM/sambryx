package mari.samba.fstec;

import jakarta.servlet.http.HttpSession;
import mari.samba.core.ApiResponse;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/fstec")
public class FstecApiController {

    private final FstecComplianceService fstecService;

    public FstecApiController(@NonNull FstecComplianceService fstecService) {
        this.fstecService = fstecService;
    }

    @GetMapping("/check")
    public ApiResponse<FstecReportDto> checkCompliance(@NonNull HttpSession session) {
        return ApiResponse.ok(fstecService.check(session.getId()));
    }
}
