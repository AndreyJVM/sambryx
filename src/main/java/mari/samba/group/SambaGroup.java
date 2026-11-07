package mari.samba.group;

import java.util.List;

public record SambaGroup(String name, List<String> members, String systemName) {

    public SambaGroup {
        if (members == null) {
            members = List.of();
        }
    }

    public String getName() {
        return name;
    }

    public List<String> getMembers() {
        return members;
    }
}
