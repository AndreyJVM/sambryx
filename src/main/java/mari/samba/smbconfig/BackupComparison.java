package mari.samba.smbconfig;

import java.util.List;

public record BackupComparison(String filename, boolean equal, List<Change> changes) {
    public record Change(String type, int lineNumber, String text) {}
}
