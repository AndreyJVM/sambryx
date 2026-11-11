package mari.samba.smbconfig;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import mari.samba.config.SambaProperties;
import mari.samba.core.SambaCommandException;
import mari.samba.infra.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;

class BackupCollisionTest {
    private static final String STAMP = "20301015_143000";

    @TempDir
    Path temporary;

    private Path config;
    private Path backups;
    private Path bin;
    private Path restart;
    private CommandExecutor executor;
    private SambaConfigService service;
    private MockedConstruction<SimpleDateFormat> time;

    @BeforeEach
    void setUp() throws Exception {
        config = Files.writeString(temporary.resolve("smb config's.conf"), "current");
        backups = Files.createDirectory(temporary.resolve("backup dir's"));
        bin = Files.createDirectory(temporary.resolve("bin"));
        restart = temporary.resolve("restarted");
        stub("sudo", "if [ \"$1\" = -n ]; then shift; fi; exec \"$@\"");
        stub("testparm", "exit 0");
        stub("systemctl", "touch '" + restart + "'");
        executor = mock(CommandExecutor.class);
        when(executor.execute(anyString(), anyString())).thenAnswer(call -> run(call.getArgument(1)));
        SambaProperties properties = mock(SambaProperties.class);
        SambaProperties.Paths paths = mock(SambaProperties.Paths.class);
        when(properties.paths()).thenReturn(paths);
        when(paths.config()).thenReturn(config.toString());
        when(paths.backups()).thenReturn(backups.toString());
        service = new SambaConfigService(executor, new SmbConfParser(), properties);
        time = mockConstruction(SimpleDateFormat.class, (mock, context) -> when(mock.format(any(java.util.Date.class)))
                .thenReturn(STAMP));
    }

    @AfterEach
    void closeTime() {
        time.close();
    }

    @Test
    void createsBackupWhenSudoRejectsPrivilegedShells() throws Exception {
        stub(
                "sudo",
                """
                if [ "$1" = -n ]; then shift; fi
                case "$1" in
                  bash|sh|/bin/bash|/bin/sh) exit 77 ;;
                esac
                exec "$@"
                """);

        service.createBackup("session");

        assertThat(Files.readString(backup())).isEqualTo("current");
        assertThat(Files.readString(config)).isEqualTo("current");
        assertThat(restart).doesNotExist();
    }

    @Test
    void cleanupFailureIsReportedEvenWhenTheBackupWasAlreadyPublished() throws Exception {
        stub("rm", "exit 29");

        assertThatThrownBy(() -> run(LinuxCommands.createBackup(config.toString(), backup().toString())))
                .isInstanceOf(SambaCommandException.class);

        assertThat(Files.readString(backup())).isEqualTo("current");
        assertThat(Files.readString(config)).isEqualTo("current");
    }

    @Test
    void createsAndListsLegacyBackupWithoutChangingCurrentConfig() throws Exception {
        service.createBackup("session");
        assertThat(Files.readString(backup())).isEqualTo("current");
        assertThat(Files.readString(config)).isEqualTo("current");
        assertThat(service.listBackups("session"))
                .extracting(SambaBackupDto::filename)
                .containsExactly(backup().getFileName().toString());
    }

    @Test
    void repeatedSameSecondCreationPreservesEverySnapshot() throws Exception {
        service.createBackup("session");
        Files.writeString(config, "second");
        service.createBackup("session");
        Files.writeString(config, "third");
        service.createBackup("session");
        assertBackupContents("current", "second", "third");
    }

    @Test
    void restorePreservesSelectedBackupAndCapturesCurrentStateOnNameCollision() throws Exception {
        Files.writeString(backup(), "selected");
        service.restoreBackup("session", backup().getFileName().toString());
        assertThat(Files.readString(config)).isEqualTo("selected");
        assertThat(Files.readString(backup())).isEqualTo("selected");
        assertBackupContents("selected", "current");
        assertThat(restart).exists();
    }

    @ParameterizedTest
    @ValueSource(strings = {"mktemp", "cp", "ln"})
    void safetyCreationFailureLeavesSelectedAndActiveFilesUntouched(String utility) throws Exception {
        Files.writeString(backup(), "selected");
        stub(utility, utility.equals("cp") ? "printf partial > \"$3\"; exit 29" : "exit 29");
        assertThatThrownBy(() ->
                        service.restoreBackup("session", backup().getFileName().toString()))
                .isInstanceOf(SambaCommandException.class);
        assertThat(Files.readString(config)).isEqualTo("current");
        assertThat(Files.readString(backup())).isEqualTo("selected");
        assertThat(restart).doesNotExist();
        try (var files = Files.list(backups)) {
            assertThat(files.toList()).containsExactly(backup());
        }
        verify(executor, never()).execute("session", LinuxCommands.cleanupOldBackups(backups.toString(), 10));
        verify(executor, never()).execute("session", LinuxCommands.copy(backup().toString(), config.toString()));
    }

    @Test
    void exhaustedNamesAbortRestoreWithoutChangingAnyBackup() throws Exception {
        Files.writeString(backup(), "selected");
        for (int i = 1; i < 100; i++) {
            Files.writeString(backups.resolve(backup().getFileName() + "_" + i), "existing");
        }
        assertThatThrownBy(() ->
                        service.restoreBackup("session", backup().getFileName().toString()))
                .isInstanceOf(SambaCommandException.class);
        assertThat(Files.readString(config)).isEqualTo("current");
        assertThat(Files.readString(backup())).isEqualTo("selected");
        assertThat(restart).doesNotExist();
        try (var files = Files.list(backups)) {
            assertThat(files.toList()).hasSize(100).allSatisfy(path -> {
                assertThat(Files.readString(path)).isEqualTo(path.equals(backup()) ? "selected" : "existing");
            });
        }
        verify(executor, never()).execute("session", LinuxCommands.cleanupOldBackups(backups.toString(), 10));
    }

    @Test
    void suffixedBackupCanBeListedAndRestored() throws Exception {
        Files.writeString(backup(), "older");
        service.createBackup("session");
        String suffixed = backup().getFileName() + "_1";
        assertThat(service.listBackups("session"))
                .extracting(SambaBackupDto::filename)
                .contains(suffixed);
        Files.writeString(config, "changed");
        service.restoreBackup("session", suffixed);
        assertThat(Files.readString(config)).isEqualTo("current");
        assertThat(Files.readString(backups.resolve(suffixed))).isEqualTo("current");
        assertThat(Files.readString(backups.resolve(backup().getFileName() + "_2")))
                .isEqualTo("changed");
    }

    @Test
    void skipsDirectoryAndDanglingSymlinkWithoutFollowingThem() throws Exception {
        Files.createDirectory(backup());
        Path target = temporary.resolve("missing");
        Path link = Files.createSymbolicLink(backups.resolve(backup().getFileName() + "_1"), target);
        service.createBackup("session");
        assertThat(backup()).isDirectory();
        assertThat(Files.readSymbolicLink(link)).isEqualTo(target);
        assertThat(target).doesNotExist();
        assertThat(Files.readString(backups.resolve(backup().getFileName() + "_2")))
                .isEqualTo("current");
    }

    @Test
    void retentionStillKeepsTenCompletedBackups() throws Exception {
        for (int i = 0; i < 12; i++) {
            Files.writeString(config, "snapshot-" + i);
            service.createBackup("session");
        }
        try (var files = Files.list(backups)) {
            assertThat(files.toList()).hasSize(10).allSatisfy(path -> {
                assertThat(path.getFileName().toString()).startsWith("smb.conf.backup_");
                assertThat(Files.readString(path)).startsWith("snapshot-");
            });
        }
    }

    @Test
    void rejectsUnsafeCommandPathsBeforeExecution() {
        for (String unsafe : new String[] {null, "", "relative", "/tmp/../etc", "/tmp/;id", "/tmp/\nname"}) {
            assertThatThrownBy(() -> LinuxCommands.createBackup(unsafe, backup().toString()))
                    .isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> LinuxCommands.createBackup(config.toString(), unsafe))
                    .isInstanceOf(SecurityException.class);
        }
        verifyNoInteractions(executor);
    }

    @Test
    void listsAccessibleEmptyDirectorySuccessfully() {
        assertThat(service.listBackups("session")).isEmpty();
    }

    @Test
    void reportsMissingDirectoryWithoutCreatingIt() throws Exception {
        Files.delete(backups);
        assertThatThrownBy(() -> service.listBackups("session")).isInstanceOf(SambaCommandException.class);
        assertThat(backups).doesNotExist();
        assertThat(Files.readString(config)).isEqualTo("current");
        assertThat(restart).doesNotExist();
    }

    @Test
    void reportsListingFailureInsteadOfEmptyResult() throws Exception {
        stub("ls", "echo 'synthetic-sensitive-output' >&2; exit 13");
        Files.writeString(backup(), "snapshot");
        assertThatThrownBy(() -> service.listBackups("session"))
                .isInstanceOf(SambaCommandException.class)
                .hasMessage("Configuration backup list is unavailable.");
        assertThat(Files.readString(backup())).isEqualTo("snapshot");
        assertThat(restart).doesNotExist();
    }

    @Test
    void rejectsBackupPathThatIsNotADirectory() throws Exception {
        Files.delete(backups);
        Files.writeString(backups, "unexpected file");
        assertThatThrownBy(() -> service.listBackups("session")).isInstanceOf(SambaCommandException.class);
        assertThat(Files.readString(backups)).isEqualTo("unexpected file");
    }

    @Test
    void listsConfiguredDirectorySymlinkInsteadOfReportingItEmpty() throws Exception {
        Path actual = Files.createDirectory(temporary.resolve("actual backups"));
        Files.writeString(actual.resolve(backup().getFileName()), "snapshot");
        Files.delete(backups);
        Files.createSymbolicLink(backups, actual);
        assertThat(service.listBackups("session"))
                .extracting(SambaBackupDto::filename)
                .containsExactly(backup().getFileName().toString());
    }

    @Test
    void missingSelectedBackupStopsBeforeCreatingSafetyCopy() throws Exception {
        stub("testparm", "test -s \"$3\"");

        assertThatThrownBy(() ->
                        service.restoreBackup("session", backup().getFileName().toString()))
                .isInstanceOf(SambaCommandException.class);

        assertThat(Files.readString(config)).isEqualTo("current");
        assertBackupContents();
        assertThat(restart).doesNotExist();
        verify(executor, times(1)).execute(anyString(), anyString());
    }

    @Test
    void rejectedSelectedContentStopsBeforeSafetyCopyAndPreservesBothFiles() throws Exception {
        Files.writeString(backup(), "invalid selected");
        stub("testparm", "echo 'Selected configuration rejected' >&2; exit 29");

        assertThatThrownBy(() ->
                        service.restoreBackup("session", backup().getFileName().toString()))
                .isInstanceOf(SambaCommandException.class)
                .hasMessageContaining("Selected configuration rejected");

        assertThat(Files.readString(config)).isEqualTo("current");
        assertBackupContents("invalid selected");
        assertThat(restart).doesNotExist();
        verify(executor, times(1)).execute(anyString(), anyString());
    }

    @Test
    void retentionEnumerationFailureLeavesInstalledSelectionAndSafetyCopyWithoutRestart() throws Exception {
        Files.writeString(backup(), "selected");
        stub("find", "echo 'Backup enumeration failed' >&2; exit 29");

        assertThatThrownBy(() ->
                        service.restoreBackup("session", backup().getFileName().toString()))
                .isInstanceOf(SambaCommandException.class)
                .hasMessageContaining("Backup enumeration failed");

        assertThat(Files.readString(config)).isEqualTo("selected");
        assertBackupContents("selected", "current");
        assertThat(restart).doesNotExist();
        verify(executor).execute("session", LinuxCommands.copy(backup().toString(), config.toString()));
    }

    @Test
    void retentionDeletionFailureLeavesInstalledSelectionAndSafetyCopyWithoutRestart() throws Exception {
        Files.writeString(backup(), "selected");
        Files.setLastModifiedTime(backup(), java.nio.file.attribute.FileTime.fromMillis(1000));
        for (int i = 1; i < 10; i++) {
            Path newer = Files.writeString(backups.resolve(backup().getFileName() + "_" + i), "newer-" + i);
            Files.setLastModifiedTime(newer, java.nio.file.attribute.FileTime.fromMillis(1000 + i));
        }
        stub("rm", "case \"$1\" in -f) exec /usr/bin/rm \"$@\" ;; esac; echo 'Retention deletion failed' >&2; exit 29");

        assertThatThrownBy(() ->
                        service.restoreBackup("session", backup().getFileName().toString()))
                .isInstanceOf(SambaCommandException.class)
                .hasMessageContaining("Retention deletion failed");

        assertThat(Files.readString(config)).isEqualTo("selected");
        assertThat(Files.readString(backup())).isEqualTo("selected");
        try (var files = Files.list(backups)) {
            assertThat(files.toList()).hasSize(11).anySatisfy(path -> assertThat(Files.readString(path))
                    .isEqualTo("current"));
        }
        assertThat(restart).doesNotExist();
    }

    @Test
    void partialRestoreCopyFailurePreservesRecoveryFilesWithoutRestartOrRollback() throws Exception {
        Files.writeString(backup(), "selected");
        stub(
                "cp",
                "if [ \"$3\" = " + LinuxCommands.escape(config.toString())
                        + " ]; then printf partial > \"$3\"; echo 'Restore copy failed' >&2; exit 29; fi; exec /usr/bin/cp \"$@\"");

        assertThatThrownBy(() ->
                        service.restoreBackup("session", backup().getFileName().toString()))
                .isInstanceOf(SambaCommandException.class)
                .hasMessageContaining("Restore copy failed");

        assertThat(Files.readString(config)).isEqualTo("partial");
        assertBackupContents("selected", "current");
        assertThat(restart).doesNotExist();
        verify(executor, never()).execute("session", LinuxCommands.cleanupOldBackups(backups.toString(), 10));
    }

    @Test
    void restoreRestartFailureLeavesInstalledSelectionAndSafetyCopyAvailable() throws Exception {
        Files.writeString(backup(), "selected");
        stub("systemctl", "echo 'Restart failed' >&2; exit 29");

        assertThatThrownBy(() ->
                        service.restoreBackup("session", backup().getFileName().toString()))
                .isInstanceOf(SambaCommandException.class)
                .hasMessageContaining("Restart failed");

        assertThat(Files.readString(config)).isEqualTo("selected");
        assertBackupContents("selected", "current");
        assertThat(restart).doesNotExist();
        verify(executor, times(1)).execute("session", LinuxCommands.systemctl("restart", "smbd"));
    }

    @Test
    void saveRestartFailureLeavesValidatedInstallationAndOriginalSafetyCopy() throws Exception {
        when(executor.executeCommand(anyString(), anyString())).thenReturn(new CommandResult(0, "", ""));
        when(executor.execute(anyString(), anyString(), anyString()))
                .thenAnswer(call -> run(call.getArgument(1), call.getArgument(2)));
        stub("testparm", "test \"$(cat -- \"$3\")\" = updated");
        stub("systemctl", "echo 'Restart failed' >&2; exit 29");

        assertThatThrownBy(() -> service.updateSmbConf("session", "updated"))
                .isInstanceOf(SambaCommandException.class)
                .hasMessageContaining("Restart failed");

        assertThat(Files.readString(config)).isEqualTo("updated");
        assertBackupContents("current");
        try (var files = Files.list(temporary)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .noneMatch(name -> name.startsWith(".smb.conf.update."));
        }
        verify(executor, times(1)).execute("session", LinuxCommands.systemctl("restart", "smbd"));
    }

    @Test
    void restoringOldestRetainedBackupDoesNotDeleteItsOwnSource() throws Exception {
        Files.writeString(backup(), "selected");
        Files.setLastModifiedTime(backup(), java.nio.file.attribute.FileTime.fromMillis(1000));
        for (int i = 1; i < 10; i++) {
            Path newer = Files.writeString(backups.resolve(backup().getFileName() + "_" + i), "newer-" + i);
            Files.setLastModifiedTime(newer, java.nio.file.attribute.FileTime.fromMillis(1000 + i));
        }
        stub("testparm", "test \"$(cat -- \"$3\")\" = selected");

        assertThatCode(() ->
                        service.restoreBackup("session", backup().getFileName().toString()))
                .doesNotThrowAnyException();

        assertThat(Files.readString(config)).isEqualTo("selected");
        try (var files = Files.list(backups)) {
            assertThat(files.toList()).hasSize(10).doesNotContain(backup()).anySatisfy(path -> assertThat(
                            Files.readString(path))
                    .isEqualTo("current"));
        }
        assertThat(restart).exists();
    }

    private void assertBackupContents(String... expected) throws Exception {
        List<Path> files;
        try (var paths = Files.list(backups)) {
            files = paths.toList();
        }
        var contents = new java.util.ArrayList<String>();
        for (Path file : files) {
            assertThat(file.getFileName().toString()).startsWith("smb.conf.backup_");
            contents.add(Files.readString(file));
        }
        assertThat(contents).containsExactlyInAnyOrder(expected);
    }

    private Path backup() {
        return backups.resolve("smb.conf.backup_" + STAMP);
    }

    private void stub(String name, String body) throws Exception {
        Path file = Files.writeString(bin.resolve(name), "#!/bin/sh\n" + body + "\n");
        assertThat(file.toFile().setExecutable(true)).isTrue();
    }

    private String run(String command) throws Exception {
        return run(command, "");
    }

    private String run(String command, String input) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", command);
        builder.environment().put("PATH", bin + ":/usr/bin:/bin");
        Path output = temporary.resolve("output");
        builder.redirectErrorStream(true).redirectOutput(output.toFile());
        Process process = builder.start();
        try {
            try (var stdin = process.getOutputStream()) {
                stdin.write(input.getBytes(StandardCharsets.UTF_8));
            }
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            String text = Files.readString(output);
            if (process.exitValue() != 0) throw new SambaCommandException("Synthetic remote command failed: " + text);
            return text;
        } finally {
            process.destroyForcibly();
        }
    }
}
