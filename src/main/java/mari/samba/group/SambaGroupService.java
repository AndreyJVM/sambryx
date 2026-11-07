package mari.samba.group;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import mari.samba.config.SambaProperties;
import mari.samba.infra.CommandExecutor;
import mari.samba.infra.LinuxCommands;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SambaGroupService {

    public static final String DEFAULT_GROUP_PREFIX = "smb_";

    private final CommandExecutor commandExecutor;
    private final String groupPrefix;

    public SambaGroupService(CommandExecutor commandExecutor, @Autowired(required = false) SambaProperties properties) {
        this.commandExecutor = commandExecutor;
        this.groupPrefix = properties != null
                        && properties.group() != null
                        && properties.group().prefix() != null
                ? properties.group().prefix()
                : DEFAULT_GROUP_PREFIX;
    }

    public List<SambaGroup> getAllGroups(String sessionId) {
        List<SambaGroup> groups = new ArrayList<>();

        String output = commandExecutor.execute(sessionId, LinuxCommands.getentGroup());
        if (output == null || output.isBlank()) {
            return groups;
        }

        String[] lines = output.split("\\r?\\n");
        for (String line : lines) {
            String[] parts = line.split(":", -1);
            if (parts.length >= 3) {
                String groupName = parts[0];

                if (!groupName.startsWith(groupPrefix)) {
                    continue;
                }

                String displayName = groupName.substring(groupPrefix.length());

                List<String> userList = new ArrayList<>();
                if (parts.length >= 4 && !parts[3].isBlank()) {
                    userList = Arrays.asList(parts[3].split(","));
                }

                groups.add(new SambaGroup(displayName, userList, groupName));
            }
        }
        return groups;
    }

    public void createGroup(String sessionId, SambaGroupCreateDto dto) {
        String fullGroupName = groupPrefix + dto.groupName();
        commandExecutor.execute(sessionId, LinuxCommands.addGroup(fullGroupName));
    }

    public void deleteGroup(String sessionId, String groupName) {
        String fullGroupName = groupPrefix + groupName;
        commandExecutor.execute(sessionId, LinuxCommands.deleteGroup(fullGroupName));
    }

    public void addUserToGroup(String sessionId, String username, String groupName) {
        String fullGroupName = groupPrefix + groupName;
        commandExecutor.execute(sessionId, LinuxCommands.addUserToGroup(username, fullGroupName));
    }

    public void removeUserFromGroup(String sessionId, String username, String groupName) {
        String fullGroupName = groupPrefix + groupName;
        commandExecutor.execute(sessionId, LinuxCommands.removeUserFromGroup(username, fullGroupName));
    }
}
