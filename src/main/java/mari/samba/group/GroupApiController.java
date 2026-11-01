package mari.samba.group;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import mari.samba.core.ApiResponse;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/groups")
public class GroupApiController {

    private final SambaGroupService sambaGroupService;

    public GroupApiController(SambaGroupService sambaGroupService) {
        this.sambaGroupService = sambaGroupService;
    }

    @GetMapping
    public ApiResponse<List<SambaGroup>> getAllGroups(@NonNull HttpSession session) {
        String sessionId = session.getId();
        List<SambaGroup> groups = sambaGroupService.getAllGroups(sessionId);
        return ApiResponse.ok(groups);
    }

    @PostMapping
    public ApiResponse<Void> createGroup(@NonNull HttpSession session, @Valid @RequestBody SambaGroupCreateDto dto) {
        String sessionId = session.getId();
        sambaGroupService.createGroup(sessionId, dto);
        return ApiResponse.ok("Group '" + dto.groupName() + "' created successfully", null);
    }

    @DeleteMapping("/{groupName}")
    public ApiResponse<Void> deleteGroup(@NonNull HttpSession session, @PathVariable String groupName) {
        String sessionId = session.getId();
        sambaGroupService.deleteGroup(sessionId, groupName);
        return ApiResponse.ok("Group '" + groupName + "' deleted successfully", null);
    }

    @PostMapping("/{groupName}/users")
    public ApiResponse<Void> addUserToGroup(
            @NonNull HttpSession session, @PathVariable String groupName, @RequestBody Map<String, String> body) {
        String sessionId = session.getId();
        String username = body != null ? body.get("username") : null;
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username must not be blank.");
        }
        sambaGroupService.addUserToGroup(sessionId, username, groupName);
        return ApiResponse.ok("User '" + username + "' added to group '" + groupName + "'", null);
    }

    @DeleteMapping("/{groupName}/users/{username}")
    public ApiResponse<Void> removeUserFromGroup(
            @NonNull HttpSession session, @PathVariable String groupName, @PathVariable String username) {
        String sessionId = session.getId();
        sambaGroupService.removeUserFromGroup(sessionId, username, groupName);
        return ApiResponse.ok("User '" + username + "' removed from group '" + groupName + "'", null);
    }
}
