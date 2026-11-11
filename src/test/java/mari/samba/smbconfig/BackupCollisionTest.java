package mari.samba.smbconfig;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.List;
import mari.samba.config.SambaProperties;
import mari.samba.core.SambaCommandException;
import mari.samba.infra.*;
import mari.samba.support.ShellCommandFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;

@Epic("Configuration and backups")
@Feature("Local shell component: backup creation and collision recovery (stub utilities)")
class BackupCollisionTest {
    private static final String STAMP = "20301015_143000";

    @TempDir
    Path temporary;

    private Path config;
    private Path backups;
    private ShellCommandFixture shell;
    private Path restart;
    private CommandExecutor executor;
    private SambaConfigService service;
    private MockedConstruction<SimpleDateFormat> time;

    @BeforeEach
    void setUp() throws Exception {
        config = Files.writeString(temporary.resolve("smb config's.conf"), "current");
        backups = Files.createDirectory(temporary.resolve("backup dir's"));
        shell = new ShellCommandFixture(temporary);
        restart = temporary.resolve("restarted");
        shell.stub("sudo", "if [ \"$1\" = -n ]; then shift; fi; exec \"$@\"");
        shell.stub("testparm", "exit 0");
        shell.stub("systemctl", "touch '" + restart + "'");
        executor = mock(CommandExecutor.class);
        when(executor.execute(anyString(), anyString())).thenAnswer(call -> shell.runChecked(call.getArgument(1)));
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

    @DisplayName("Creates backup when sudo rejects privileged shells")
    @Test
    void createsBackupWhenSudoRejectsPrivilegedShells() throws Exception {
        shell.stub(
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

    @DisplayName("Cleanup failure is reported even when the backup was already published")
    @Test
    void cleanupFailureIsReportedEvenWhenTheBackupWasAlreadyPublished() throws Exception {
        shell.stub("rm", "exit 29");

        assertThatThrownBy(() -> shell.runChecked(LinuxCommands.createBackup(config.toString(), backup().toString())))
                .isInstanceOf(SambaCommandException.class);

        assertThat(Files.readString(backup())).isEqualTo("current");
        assertThat(Files.readString(config)).isEqualTo("current");
    }

    @DisplayName("Creates and lists legacy backup without changing current config")
    @Test
    void createsAndListsLegacyBackupWithoutChangingCurrentConfig() throws Exception {
        service.createBackup("session");
        assertThat(Files.readString(backup())).isEqualTo("current");
        assertThat(Files.readString(config)).isEqualTo("current");
        assertThat(service.listBackups("session"))
                .extracting(SambaBackupDto::filename)
                .containsExactly(backup().getFileName().toString());
    }

    @DisplayName("Repeated same second creation preserves every snapshot")
    @Test
    void repeatedSameSecondCreationPreservesEverySnapshot() throws Exception {
        service.createBackup("session");
        Files.writeString(config, "second");
        service.createBackup("session");
        Files.writeString(config, "third");
        service.createBackup("session");
        assertBackupContents("current", "second", "third");
    }

    @DisplayName("Restore preserves selected backup and captures current state on name collision")
    @Test
    void restorePreservesSelectedBackupAndCapturesCurrentStateOnNameCollision() throws Exception {
        Files.writeString(backup(), "selected");
        service.restoreBackup("session", backup().getFileName().toString());
        assertThat(Files.readString(config)).isEqualTo("selected");
        assertThat(Files.readString(backup())).isEqualTo("selected");
        assertBackupContents("selected", "current");
        assertThat(restart).exists();
    }

    @DisplayName("Safety creation failure leaves selected and active files untouched")
    @ParameterizedTest(name = "{argumentsWithNames}")
    @ValueSource(strings = {"mktemp", "cp", "ln"})
    void safetyCreationFailureLeavesSelectedAndActiveFilesUntouched(String utility) throws Exception {
        Files.writeString(backup(), "selected");
        shell.stub(utility, utility.equals("cp") ? "printf partial > \"$3\"; exit 29" : "exit 29");
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

    @DisplayName("Exhausted names abort restore without changing any backup")
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

    @DisplayName("Suffixed backup can be listed and restored")
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

    @DisplayName("Skips directory and dangling symlink without following them")
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

    @DisplayName("Retention still keeps ten completed backups")
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

    @DisplayName("Rejects unsafe command paths before execution")
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

    @DisplayName("Lists accessible empty directory successfully")
    @Test
    void listsAccessibleEmptyDirectorySuccessfully() {
        assertThat(service.listBackups("session")).isEmpty();
    }

    @DisplayName("Reports missing directory without creating it")
    @Test
    void reportsMissingDirectoryWithoutCreatingIt() throws Exception {
        Files.delete(backups);
        assertThatThrownBy(() -> service.listBackups("session")).isInstanceOf(SambaCommandException.class);
        assertThat(backups).doesNotExist();
        assertThat(Files.readString(config)).isEqualTo("current");
        assertThat(restart).doesNotExist();
    }

    @DisplayName("Reports listing failure instead of empty result")
    @Test
    void reportsListingFailureInsteadOfEmptyResult() throws Exception {
        shell.stub("ls", "echo 'synthetic-sensitive-output' >&2; exit 13");
        Files.writeString(backup(), "snapshot");
        assertThatThrownBy(() -> service.listBackups("session"))
                .isInstanceOf(SambaCommandException.class)
                .hasMessage("Configuration backup list is unavailable.");
        assertThat(Files.readString(backup())).isEqualTo("snapshot");
        assertThat(restart).doesNotExist();
    }

    @DisplayName("Rejects backup path that is not adirectory")
    @Test
    void rejectsBackupPathThatIsNotADirectory() throws Exception {
        Files.delete(backups);
        Files.writeString(backups, "unexpected file");
        assertThatThrownBy(() -> service.listBackups("session")).isInstanceOf(SambaCommandException.class);
        assertThat(Files.readString(backups)).isEqualTo("unexpected file");
    }

    @DisplayName("Lists configured directory symlink instead of reporting it empty")
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

    @DisplayName("Missing selected backup stops before creating safety copy")
    @Test
    void missingSelectedBackupStopsBeforeCreatingSafetyCopy() throws Exception {
        shell.stub("testparm", "test -s \"$3\"");

        assertThatThrownBy(() ->
                        service.restoreBackup("session", backup().getFileName().toString()))
                .isInstanceOf(SambaCommandException.class);

        assertThat(Files.readString(config)).isEqualTo("current");
        assertBackupContents();
        assertThat(restart).doesNotExist();
        verify(executor, times(1)).execute(anyString(), anyString());
    }

    @DisplayName("Rejected selected content stops before safety copy and preserves both files")
    @Test
    void rejectedSelectedContentStopsBeforeSafetyCopyAndPreservesBothFiles() throws Exception {
        Files.writeString(backup(), "invalid selected");
        shell.stub("testparm", "echo 'Selected configuration rejected' >&2; exit 29");

        assertThatThrownBy(() ->
                        service.restoreBackup("session", backup().getFileName().toString()))
                .isInstanceOf(SambaCommandException.class)
                .hasMessageContaining("Selected configuration rejected");

        assertThat(Files.readString(config)).isEqualTo("current");
        assertBackupContents("invalid selected");
        assertThat(restart).doesNotExist();
        verify(executor, times(1)).execute(anyString(), anyString());
    }

    @DisplayName("Retention enumeration failure leaves installed selection and safety copy without restart")
    @Test
    void retentionEnumerationFailureLeavesInstalledSelectionAndSafetyCopyWithoutRestart() throws Exception {
        Files.writeString(backup(), "selected");
        shell.stub("find", "echo 'Backup enumeration failed' >&2; exit 29");

        assertThatThrownBy(() ->
                        service.restoreBackup("session", backup().getFileName().toString()))
                .isInstanceOf(SambaCommandException.class)
                .hasMessageContaining("Backup enumeration failed");

        assertThat(Files.readString(config)).isEqualTo("selected");
        assertBackupContents("selected", "current");
        assertThat(restart).doesNotExist();
        verify(executor).execute("session", LinuxCommands.copy(backup().toString(), config.toString()));
    }

    @DisplayName("Retention deletion failure leaves installed selection and safety copy without restart")
    @Test
    void retentionDeletionFailureLeavesInstalledSelectionAndSafetyCopyWithoutRestart() throws Exception {
        seedFullRetentionWindowWithSelectedOldest();
        shell.stub(
                "rm",
                "case \"$1\" in -f) exec /usr/bin/rm \"$@\" ;; esac; echo 'Retention deletion failed' >&2; exit 29");

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

    @DisplayName("Partial restore copy failure preserves recovery files without restart or rollback")
    @Test
    void partialRestoreCopyFailurePreservesRecoveryFilesWithoutRestartOrRollback() throws Exception {
        Files.writeString(backup(), "selected");
        shell.stub(
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

    @DisplayName("Restore restart failure leaves installed selection and safety copy available")
    @Test
    void restoreRestartFailureLeavesInstalledSelectionAndSafetyCopyAvailable() throws Exception {
        Files.writeString(backup(), "selected");
        shell.stub("systemctl", "echo 'Restart failed' >&2; exit 29");

        assertThatThrownBy(() ->
                        service.restoreBackup("session", backup().getFileName().toString()))
                .isInstanceOf(SambaCommandException.class)
                .hasMessageContaining("Restart failed");

        assertThat(Files.readString(config)).isEqualTo("selected");
        assertBackupContents("selected", "current");
        assertThat(restart).doesNotExist();
        verify(executor, times(1)).execute("session", LinuxCommands.systemctl("restart", "smbd"));
    }

    @DisplayName("Save restart failure leaves validated installation and original safety copy")
    @Test
    void saveRestartFailureLeavesValidatedInstallationAndOriginalSafetyCopy() throws Exception {
        when(executor.executeCommand(anyString(), anyString())).thenReturn(new CommandResult(0, "", ""));
        when(executor.execute(anyString(), anyString(), anyString()))
                .thenAnswer(call -> shell.runChecked(call.getArgument(1), call.getArgument(2)));
        shell.stub("testparm", "test \"$(cat -- \"$3\")\" = updated");
        shell.stub("systemctl", "echo 'Restart failed' >&2; exit 29");

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

    @DisplayName("Restoring oldest retained backup does not delete its own source")
    @Test
    void restoringOldestRetainedBackupDoesNotDeleteItsOwnSource() throws Exception {
        seedFullRetentionWindowWithSelectedOldest();
        shell.stub("testparm", "test \"$(cat -- \"$3\")\" = selected");

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

    private void seedFullRetentionWindowWithSelectedOldest() throws Exception {
        Files.writeString(backup(), "selected");
        Files.setLastModifiedTime(backup(), java.nio.file.attribute.FileTime.fromMillis(1000));
        for (int i = 1; i < 10; i++) {
            Path newer = Files.writeString(backups.resolve(backup().getFileName() + "_" + i), "newer-" + i);
            Files.setLastModifiedTime(newer, java.nio.file.attribute.FileTime.fromMillis(1000 + i));
        }
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
}
