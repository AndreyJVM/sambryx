package mari.samba.smbconfig;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import mari.samba.core.SambaCommandException;
import mari.samba.infra.CommandExecutor;
import mari.samba.infra.LinuxCommands;

/** Bounded, literal comparison from the current server file to a selected backup. */
final class BackupComparisonReader {
    private final Semaphore slots = new Semaphore(2);

    BackupComparison compare(
            CommandExecutor commands, String session, String currentPath, String backupPath, String filename) {
        if (!slots.tryAcquire()) throw unavailable();
        try {
            List<String> current = read(commands, session, currentPath);
            List<String> backup = read(commands, session, backupPath);
            List<BackupComparison.Change> changes = changes(current, backup);
            return new BackupComparison(filename, changes.isEmpty(), changes);
        } catch (SambaCommandException | IllegalArgumentException e) {
            throw unavailable();
        } finally {
            slots.release();
        }
    }

    private List<String> read(CommandExecutor commands, String session, String path) {
        String text = commands.execute(session, LinuxCommands.readComparisonFile(path));
        if (text == null
                || text.getBytes(StandardCharsets.UTF_8).length > 262144
                || text.indexOf('\0') >= 0
                || text.indexOf('\uFFFD') >= 0) {
            throw unavailable();
        }
        // Keep line endings so a missing final newline cannot be reported as equality.
        List<String> lines = text.isEmpty() ? List.of() : List.of(text.split("(?<=\\n)", -1));
        if (!lines.isEmpty() && lines.getLast().isEmpty()) lines = lines.subList(0, lines.size() - 1);
        if (lines.size() > 1000) throw unavailable();
        return lines;
    }

    private List<BackupComparison.Change> changes(List<String> current, List<String> backup) {
        int[][] lengths = new int[current.size() + 1][backup.size() + 1];
        for (int i = current.size() - 1; i >= 0; i--) {
            for (int j = backup.size() - 1; j >= 0; j--) {
                lengths[i][j] = current.get(i).equals(backup.get(j))
                        ? 1 + lengths[i + 1][j + 1]
                        : Math.max(lengths[i + 1][j], lengths[i][j + 1]);
            }
        }
        var result = new ArrayList<BackupComparison.Change>();
        int i = 0;
        int j = 0;
        while (i < current.size() || j < backup.size()) {
            if (i < current.size() && j < backup.size() && current.get(i).equals(backup.get(j))) {
                i++;
                j++;
            } else if (i < current.size() && (j == backup.size() || lengths[i + 1][j] >= lengths[i][j + 1])) {
                result.add(new BackupComparison.Change("REMOVED", i + 1, current.get(i++)));
            } else {
                result.add(new BackupComparison.Change("ADDED", j + 1, backup.get(j++)));
            }
        }
        return List.copyOf(result);
    }

    private static SambaCommandException unavailable() {
        return new SambaCommandException(
                "Configuration backup comparison is unavailable (read failure, limit exceeded or busy). Try again later.");
    }
}
