package mari.samba.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BackupCleanupCommandTest {
    @TempDir
    Path temporary;

    private Path bin;
    private Path backups;

    @BeforeEach
    void setUp() throws IOException {
        bin = Files.createDirectory(temporary.resolve("bin"));
        backups = Files.createDirectory(temporary.resolve("backups"));
        stub("sudo", "#!/bin/sh\nexec \"$@\"\n");
    }

    @Test
    void preservesArgumentBoundariesForUntrustedFileNames() throws Exception {
        Path victim = Files.writeString(temporary.resolve("victim"), "keep");
        Path crafted = backup("old victim", 1000);
        backup("new", 2000);
        int status = runCleanup(1);
        assertTrue(Files.exists(victim), "A backup name must not introduce another deletion operand");
        assertFalse(Files.exists(crafted));
        assertEquals(0, status);
    }

    @Test
    void enumerationFailureStopsDeletionAndReturnsFailure() throws Exception {
        Path old = backup("old", 1000);
        backup("new", 2000);
        stub("ls", "#!/bin/sh\nprintf '%s\\n' \"$BACKUP_OLD\" \"$BACKUP_OLD\"\nexit 23\n");
        stub("find", "#!/bin/sh\nprintf '%s\\0' \"1000 $BACKUP_OLD\" \"2000 $BACKUP_OLD\"\nexit 23\n");
        assertNotEquals(0, runCleanup(1));
        assertTrue(Files.exists(old), "Partial enumeration must not trigger deletion");
    }

    @ParameterizedTest
    @ValueSource(strings = {"quote'", "double\"quote", "line\nbreak", "tab\tname", "$(touch injected);&", "-rf"})
    void removesOnlyTheSelectedBackupWithSpecialCharacters(String suffix) throws Exception {
        Path old = backup(suffix, 1000);
        Path newest = backup("new", 2000);
        assertEquals(0, runCleanup(1));
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(newest));
        assertFalse(Files.exists(temporary.resolve("injected")));
    }

    @Test
    void deletionFailureIsReportedWithoutRemovingBackup() throws Exception {
        Path old = backup("old", 1000);
        backup("new", 2000);
        stub("sudo", "#!/bin/sh\nexit 31\n");
        assertNotEquals(0, runCleanup(1));
        assertTrue(Files.exists(old));
    }

    @Test
    void emptyDirectorySucceedsWithoutPrivilegedDeletion() throws Exception {
        stub("sudo", "#!/bin/sh\ntouch \"$DELETE_MARKER\"\nexit 31\n");
        assertEquals(0, runCleanup(1));
        assertFalse(Files.exists(temporary.resolve("delete-marker")));
    }

    @Test
    void rejectsInvalidInputsBeforeExecution() {
        for (String path : new String[] {"", "relative", "/tmp/../etc", "/tmp/;id", "/tmp/\nname"}) {
            assertThrows(SecurityException.class, () -> LinuxCommands.cleanupOldBackups(path, 1));
        }
        assertThrows(SecurityException.class, () -> LinuxCommands.cleanupOldBackups(null, 1));
        for (int count : new int[] {-1, 0, 101}) {
            assertThrows(SecurityException.class, () -> LinuxCommands.cleanupOldBackups(backups.toString(), count));
        }
    }

    @Test
    void retainsNewestBackupsAndUnrelatedFiles() throws Exception {
        Path oldest = backup("old", 1000);
        Path middle = backup("middle", 2000);
        Path newest = backup("new", 3000);
        Path unrelated = Files.writeString(backups.resolve("notes"), "keep");
        assertEquals(0, runCleanup(2));
        assertFalse(Files.exists(oldest));
        assertTrue(Files.exists(middle));
        assertTrue(Files.exists(newest));
        assertTrue(Files.exists(unrelated));
    }

    @Test
    void supportsQuotedBackupDirectory() throws Exception {
        backups = Files.createDirectory(temporary.resolve("backup dir's"));
        Path old = backup("old", 1000);
        backup("new", 2000);
        assertEquals(0, runCleanup(1));
        assertFalse(Files.exists(old));
    }

    @ParameterizedTest
    @ValueSource(strings = {"mktemp", "sort"})
    void preparationFailureStopsDeletion(String utility) throws Exception {
        Path old = backup("old", 1000);
        backup("new", 2000);
        stub(utility, "#!/bin/sh\nexit 29\n");
        assertNotEquals(0, runCleanup(1));
        assertTrue(Files.exists(old));
    }

    @Test
    void missingDirectoryReturnsFailure() throws Exception {
        Files.delete(backups);
        assertNotEquals(0, runCleanup(1));
    }

    @Test
    void ignoresDirectoriesAndSymbolicLinks() throws Exception {
        Path target = Files.writeString(temporary.resolve("target"), "keep");
        Path link = Files.createSymbolicLink(backups.resolve("smb.conf.backup_link"), target);
        Path directory = Files.createDirectory(backups.resolve("smb.conf.backup_directory"));
        Path child = Files.writeString(directory.resolve("child"), "keep");
        Path old = backup("old", 1000);
        Path newest = backup("new", 2000);
        assertEquals(0, runCleanup(1));
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(newest));
        assertTrue(Files.exists(link));
        assertTrue(Files.exists(target));
        assertTrue(Files.exists(child));
    }

    private Path backup(String suffix, long modified) throws IOException {
        Path file = Files.writeString(backups.resolve("smb.conf.backup_" + suffix), "synthetic");
        Files.setLastModifiedTime(file, FileTime.fromMillis(modified));
        return file;
    }

    private void stub(String name, String script) throws IOException {
        Path executable = Files.writeString(bin.resolve(name), script);
        assertTrue(executable.toFile().setExecutable(true));
    }

    private int runCleanup(int keep) throws Exception {
        ProcessBuilder builder =
                new ProcessBuilder("/bin/sh", "-c", LinuxCommands.cleanupOldBackups(backups.toString(), keep));
        builder.directory(temporary.toFile());
        builder.environment().put("PATH", bin + ":/usr/bin:/bin");
        builder.environment()
                .put("BACKUP_OLD", backups.resolve("smb.conf.backup_old").toString());
        builder.environment()
                .put("DELETE_MARKER", temporary.resolve("delete-marker").toString());
        builder.redirectErrorStream(true);
        builder.redirectOutput(temporary.resolve("output").toFile());
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Cleanup must terminate");
            return process.exitValue();
        } finally {
            process.destroyForcibly();
        }
    }
}
