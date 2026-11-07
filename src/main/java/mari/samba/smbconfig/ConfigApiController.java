package mari.samba.smbconfig;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.List;
import mari.samba.core.ApiResponse;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/config")
public class ConfigApiController {

    private final SambaConfigService configService;

    public ConfigApiController(SambaConfigService configService) {
        this.configService = configService;
    }

    @GetMapping("/global")
    public ApiResponse<SambaGlobalConfigDto> getGlobalConfig(@NonNull HttpSession session) {
        String sessionId = session.getId();
        SambaGlobalConfigDto config = configService.getGlobalConfig(sessionId);
        return ApiResponse.ok(config);
    }

    @PutMapping("/global")
    public ApiResponse<Void> updateGlobalConfig(
            @NonNull HttpSession session, @Valid @RequestBody SambaGlobalConfigDto dto) {
        String sessionId = session.getId();
        configService.updateGlobalConfig(sessionId, dto);
        return ApiResponse.ok("Global configuration updated successfully", null);
    }

    @GetMapping("/raw")
    public ApiResponse<String> getRawConfig(@NonNull HttpSession session) {
        String sessionId = session.getId();
        String content = configService.getSmbConfContent(sessionId);
        return ApiResponse.ok("Configuration retrieved successfully", content);
    }

    @PutMapping("/raw")
    public ApiResponse<Void> updateRawConfig(@NonNull HttpSession session, @Valid @RequestBody SambaRawConfigDto dto) {
        String sessionId = session.getId();
        configService.updateSmbConf(sessionId, dto.content());
        return ApiResponse.ok("Configuration file updated and service restarted", null);
    }

    @GetMapping("/backups")
    public ApiResponse<List<SambaBackupDto>> listBackups(@NonNull HttpSession session) {
        String sessionId = session.getId();
        List<SambaBackupDto> backups = configService.listBackups(sessionId);
        return ApiResponse.ok(backups);
    }

    @PostMapping("/backups")
    public ApiResponse<Void> createBackup(@NonNull HttpSession session) {
        String sessionId = session.getId();
        configService.createBackup(sessionId);
        return ApiResponse.ok("Backup created successfully", null);
    }

    @GetMapping("/backups/{filename}/compare")
    public ApiResponse<BackupComparison> compareBackup(
            @NonNull HttpSession session, @PathVariable("filename") String filename) {
        return ApiResponse.ok(configService.compareBackup(session.getId(), filename));
    }

    @PostMapping("/backups/{filename}/restore")
    public ApiResponse<Void> restoreBackupByPath(
            @NonNull HttpSession session, @PathVariable("filename") String filename) {
        String sessionId = session.getId();
        configService.restoreBackup(sessionId, filename);
        return ApiResponse.ok("Configuration restored from " + filename, null);
    }

    @PostMapping("/backups/restore")
    public ApiResponse<Void> restoreBackupByParam(
            @NonNull HttpSession session, @RequestParam("filename") String filename) {
        return restoreBackupByPath(session, filename);
    }
}
