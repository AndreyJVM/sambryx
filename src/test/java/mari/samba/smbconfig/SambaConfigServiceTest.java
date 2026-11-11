package mari.samba.smbconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Named.named;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.util.List;
import java.util.stream.Stream;
import mari.samba.config.SambaProperties;
import mari.samba.core.SambaCommandException;
import mari.samba.infra.CommandExecutor;
import mari.samba.infra.LinuxCommands;
import mari.samba.infra.SmbConfParser;
import mari.samba.share.SambaShare;
import mari.samba.share.SambaShareCreateDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@Epic("Configuration and backups")
@Feature("Unit: configuration and backup command orchestration (mock commands)")
class SambaConfigServiceTest {

    @Mock
    private CommandExecutor commandExecutor;

    @Mock
    private SmbConfParser smbConfParser;

    @Mock
    private SambaProperties properties;

    @Mock
    private SambaProperties.Paths mockPaths;

    private SambaConfigService sambaConfigService;

    @BeforeEach
    void setUp() {
        when(properties.paths()).thenReturn(mockPaths);
        when(mockPaths.config()).thenReturn("/etc/samba/smb.conf");
        when(mockPaths.backups()).thenReturn("/etc/samba/backups");

        lenient()
                .when(commandExecutor.executeCommand(anyString(), anyString()))
                .thenReturn(new mari.samba.infra.CommandResult(0, "", ""));
        sambaConfigService = new SambaConfigService(commandExecutor, smbConfParser, properties);
    }

    @Nested
    class ContentOperations {

        @DisplayName("Get smb.conf content returns file content")
        @Test
        void getSmbConfContent_returnsFileContent() throws Exception {
            // given
            String expectedCmd = LinuxCommands.cat("/etc/samba/smb.conf");
            when(commandExecutor.execute("session-1", expectedCmd)).thenReturn("[global]\nworkgroup = WORKGROUP");

            // when
            String content = sambaConfigService.getSmbConfContent("session-1");

            // then
            assertThat(content).isEqualTo("[global]\nworkgroup = WORKGROUP");
        }

        @DisplayName("Parse shares delegates to parser")
        @Test
        void parseShares_delegatesToParser() {
            // given
            String configData = "[share]\npath=/data";
            SambaShare mockShare =
                    SambaShare.builder().name("share").path("/data").build();
            when(smbConfParser.parseShares(configData)).thenReturn(List.of(mockShare));

            // when
            List<SambaShare> shares = sambaConfigService.parseShares(configData);

            // then
            assertThat(shares).hasSize(1);
            assertThat(shares.get(0).getName()).isEqualTo("share");
        }

        @DisplayName("Build share section delegates to parser")
        @Test
        void buildShareSection_delegatesToParser() {
            // given
            SambaShareCreateDto dto = new SambaShareCreateDto();
            when(smbConfParser.buildShareSection(dto)).thenReturn("[new_share]");

            // when
            String section = sambaConfigService.buildShareSection(dto);

            // then
            assertThat(section).isEqualTo("[new_share]");
        }

        @DisplayName("Remove share section delegates to parser")
        @Test
        void removeShareSection_delegatesToParser() {
            // given
            String initial = "[share1]\n[share2]";
            when(smbConfParser.removeSection(initial, "share2")).thenReturn("[share1]\n");

            // when
            String updated = sambaConfigService.removeShareSection(initial, "share2");

            // then
            assertThat(updated).isEqualTo("[share1]\n");
        }
    }

    @Nested
    class BackupOperations {

        @DisplayName("Create backup executes commands successfully")
        @Test
        void createBackup_executesCommandsSuccessfully() throws Exception {
            // given
            String sessionId = "sess-1";

            // when
            sambaConfigService.createBackup(sessionId);

            // then
            verify(commandExecutor).execute(eq(sessionId), eq(LinuxCommands.mkdir("/etc/samba/backups")));

            ArgumentCaptor<String> cmdCaptor = ArgumentCaptor.forClass(String.class);
            verify(commandExecutor, times(3)).execute(eq(sessionId), cmdCaptor.capture());
            List<String> executedCommands = cmdCaptor.getAllValues();

            assertThat(executedCommands.get(1))
                    .startsWith("bash -c ")
                    .contains("sudo -n mktemp", "sudo -n cp", "sudo -n ln", ".smb.conf.staging.XXXXXX")
                    .doesNotContain("sudo bash");
            assertThat(executedCommands.get(2)).isEqualTo(LinuxCommands.cleanupOldBackups("/etc/samba/backups", 10));
        }

        @DisplayName("List backups returns parsed backup list")
        @Test
        void listBackups_returnsParsedBackupList() throws Exception {
            // given
            String sessionId = "sess-1";
            String fakeLsOutput =
                    "-rw-r--r-- 1 root root 1200 2024-10-15 14:30:00 /etc/samba/backups/smb.conf.backup_20241015_143000\n"
                            + "-rw-r--r-- 1 root root 1150 2024-10-14 10:20:00 /etc/samba/backups/smb.conf.backup_20241014_102000";
            when(commandExecutor.execute(eq(sessionId), eq(LinuxCommands.listBackupsDetailed("/etc/samba/backups"))))
                    .thenReturn(fakeLsOutput);

            // when
            List<SambaBackupDto> backups = sambaConfigService.listBackups(sessionId);

            // then
            assertThat(backups).hasSize(2);
            assertThat(backups.get(0).filename()).isEqualTo("smb.conf.backup_20241015_143000");
            assertThat(backups.get(0).createdAt()).isEqualTo("2024-10-15 14:30:00");
            assertThat(backups.get(0).size()).isEqualTo("1200");
        }

        @DisplayName("List backups when command fails reports safe failure")
        @ParameterizedTest(name = "{argumentsWithNames}")
        @ValueSource(strings = {"Permission denied", "SSH timeout", "Listing failed"})
        void listBackups_whenCommandFails_reportsSafeFailure(String diagnostic) {
            when(commandExecutor.execute(eq("sess-1"), anyString())).thenThrow(new SambaCommandException(diagnostic));

            assertThatThrownBy(() -> sambaConfigService.listBackups("sess-1"))
                    .isInstanceOf(SambaCommandException.class)
                    .hasMessage("Configuration backup list is unavailable.");
            verify(commandExecutor).execute(eq("sess-1"), anyString());
            verifyNoMoreInteractions(commandExecutor);
        }

        private static Stream<Arguments> unusableListings() {
            return Stream.of(
                    Arguments.of(named("missing command output", null)),
                    Arguments.of(named("LF only", "\n")),
                    Arguments.of(named("CRLF only", "\r\n")),
                    Arguments.of(named("unparseable row", "garbled output")),
                    Arguments.of(named("unexpected total header", "total 0")),
                    Arguments.of(named("whitespace only", "   ")),
                    Arguments.of(
                            named(
                                    "invalid timestamp",
                                    "-rw-r--r-- 1 root root 12 2024-99-99 14:30:00 /etc/samba/backups/smb.conf.backup_20241015_143000")),
                    Arguments.of(named(
                            "unexpected filename",
                            "-rw-r--r-- 1 root root 12 2024-10-15 14:30:00 /etc/samba/backups/unexpected")));
        }

        @DisplayName("List backups rejects unusable output")
        @ParameterizedTest(name = "{0}")
        @MethodSource("unusableListings")
        void listBackups_rejectsUnusableOutput(String output) {
            when(commandExecutor.execute(eq("sess-1"), anyString())).thenReturn(output);
            assertThatThrownBy(() -> sambaConfigService.listBackups("sess-1"))
                    .isInstanceOf(SambaCommandException.class)
                    .hasMessage("Configuration backup list is unavailable.");
        }

        @DisplayName("List backups accepts confirmed empty output")
        @Test
        void listBackups_acceptsConfirmedEmptyOutput() {
            when(commandExecutor.execute(eq("sess-1"), anyString())).thenReturn("");
            assertThat(sambaConfigService.listBackups("sess-1")).isEmpty();
        }

        @DisplayName("Restore backup with valid name executes restore and restarts service")
        @Test
        void restoreBackup_withValidName_executesRestoreAndRestartsService() throws Exception {
            // given
            String sessionId = "sess-1";
            String filename = "smb.conf.backup_20301015_143000";

            // when
            sambaConfigService.restoreBackup(sessionId, filename);

            // then
            var order = inOrder(commandExecutor);
            order.verify(commandExecutor)
                    .execute(sessionId, LinuxCommands.testparmSilent("/etc/samba/backups/" + filename));
            order.verify(commandExecutor).execute(sessionId, LinuxCommands.mkdir("/etc/samba/backups"));
            order.verify(commandExecutor).execute(eq(sessionId), startsWith("bash -c "));
            order.verify(commandExecutor)
                    .execute(sessionId, LinuxCommands.copy("/etc/samba/backups/" + filename, "/etc/samba/smb.conf"));
            order.verify(commandExecutor).execute(sessionId, LinuxCommands.cleanupOldBackups("/etc/samba/backups", 10));
            order.verify(commandExecutor).execute(sessionId, LinuxCommands.systemctl("restart", "smbd"));
        }

        @DisplayName("Restore backup with invalid name throws illegal argument exception")
        @Test
        void restoreBackup_withInvalidName_throwsIllegalArgumentException() {
            // given
            String invalidName = "smb.conf.backup_latest"; // lacks correct timestamp format

            // when / then
            assertThatThrownBy(() -> sambaConfigService.restoreBackup("session-1", invalidName))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid backup filename format.");
        }

        private static Stream<Arguments> unsafeBackupNames() {
            return Stream.of(
                    Arguments.of(named("path traversal", "../smb.conf.backup_20301015_143000")),
                    Arguments.of(named("absolute path", "/tmp/smb.conf.backup_20301015_143000")),
                    Arguments.of(named("shell separator", "smb.conf.backup_20301015_143000;id")),
                    Arguments.of(named("empty filename", "")),
                    Arguments.of(named("extra extension", "smb.conf.backup_20301015_143000.bak")),
                    Arguments.of(named("zero suffix", "smb.conf.backup_20301015_143000_0")),
                    Arguments.of(named("leading-zero suffix", "smb.conf.backup_20301015_143000_01")),
                    Arguments.of(named("out-of-range suffix", "smb.conf.backup_20301015_143000_100")),
                    Arguments.of(named("suffix with shell separator", "smb.conf.backup_20301015_143000_1;id")));
        }

        @DisplayName("Restore backup rejects unsafe names before executing commands")
        @ParameterizedTest(name = "{0}")
        @MethodSource("unsafeBackupNames")
        void restoreBackup_rejectsUnsafeNamesBeforeExecutingCommands(String filename) {
            assertThatThrownBy(() -> sambaConfigService.restoreBackup("session-1", filename))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(commandExecutor);
        }

        private static final String RESTORE_SESSION = "restore-session";
        private static final String RESTORE_NAME = "smb.conf.backup_20301015_143000";

        @DisplayName("Restore backup stops after failed safety backup")
        @Test
        void restoreBackup_stopsAfterFailedSafetyBackup() {
            when(commandExecutor.execute(eq(RESTORE_SESSION), anyString())).thenReturn("");
            var failure = new SambaCommandException("Synthetic failure");
            when(commandExecutor.execute(eq(RESTORE_SESSION), startsWith("bash -c ")))
                    .thenThrow(failure);
            assertThatThrownBy(() -> sambaConfigService.restoreBackup(RESTORE_SESSION, RESTORE_NAME))
                    .isSameAs(failure);
            var order = inOrder(commandExecutor);
            order.verify(commandExecutor)
                    .execute(RESTORE_SESSION, LinuxCommands.testparmSilent("/etc/samba/backups/" + RESTORE_NAME));
            order.verify(commandExecutor).execute(RESTORE_SESSION, LinuxCommands.mkdir("/etc/samba/backups"));
            order.verify(commandExecutor).execute(eq(RESTORE_SESSION), startsWith("bash -c "));
            order.verifyNoMoreInteractions();
            verify(commandExecutor, times(3)).execute(eq(RESTORE_SESSION), anyString());
            verifyNoMoreInteractions(commandExecutor);
        }

        @DisplayName("Restore backup stops after failed restore copy")
        @Test
        void restoreBackup_stopsAfterFailedRestoreCopy() {
            when(commandExecutor.execute(eq(RESTORE_SESSION), anyString())).thenReturn("");
            var failure = new SambaCommandException("Synthetic failure");
            when(commandExecutor.execute(
                            eq(RESTORE_SESSION),
                            eq(LinuxCommands.copy("/etc/samba/backups/" + RESTORE_NAME, "/etc/samba/smb.conf"))))
                    .thenThrow(failure);
            assertThatThrownBy(() -> sambaConfigService.restoreBackup(RESTORE_SESSION, RESTORE_NAME))
                    .isSameAs(failure);
            var order = inOrder(commandExecutor);
            order.verify(commandExecutor)
                    .execute(RESTORE_SESSION, LinuxCommands.testparmSilent("/etc/samba/backups/" + RESTORE_NAME));
            order.verify(commandExecutor).execute(RESTORE_SESSION, LinuxCommands.mkdir("/etc/samba/backups"));
            order.verify(commandExecutor).execute(eq(RESTORE_SESSION), startsWith("bash -c "));
            order.verify(commandExecutor)
                    .execute(
                            RESTORE_SESSION,
                            LinuxCommands.copy("/etc/samba/backups/" + RESTORE_NAME, "/etc/samba/smb.conf"));
            order.verifyNoMoreInteractions();
            verify(commandExecutor, times(4)).execute(eq(RESTORE_SESSION), anyString());
            verifyNoMoreInteractions(commandExecutor);
        }

        @DisplayName("Restore backup stops after failed restart")
        @Test
        void restoreBackup_stopsAfterFailedRestart() {
            when(commandExecutor.execute(eq(RESTORE_SESSION), anyString())).thenReturn("");
            var failure = new SambaCommandException("Synthetic failure");
            when(commandExecutor.execute(eq(RESTORE_SESSION), eq(LinuxCommands.systemctl("restart", "smbd"))))
                    .thenThrow(failure);
            assertThatThrownBy(() -> sambaConfigService.restoreBackup(RESTORE_SESSION, RESTORE_NAME))
                    .isSameAs(failure);
            var order = inOrder(commandExecutor);
            order.verify(commandExecutor)
                    .execute(RESTORE_SESSION, LinuxCommands.testparmSilent("/etc/samba/backups/" + RESTORE_NAME));
            order.verify(commandExecutor).execute(RESTORE_SESSION, LinuxCommands.mkdir("/etc/samba/backups"));
            order.verify(commandExecutor).execute(eq(RESTORE_SESSION), startsWith("bash -c "));
            order.verify(commandExecutor)
                    .execute(
                            RESTORE_SESSION,
                            LinuxCommands.copy("/etc/samba/backups/" + RESTORE_NAME, "/etc/samba/smb.conf"));
            order.verify(commandExecutor)
                    .execute(RESTORE_SESSION, LinuxCommands.cleanupOldBackups("/etc/samba/backups", 10));
            order.verify(commandExecutor).execute(RESTORE_SESSION, LinuxCommands.systemctl("restart", "smbd"));
            order.verifyNoMoreInteractions();
            verify(commandExecutor, times(6)).execute(eq(RESTORE_SESSION), anyString());
            verifyNoMoreInteractions(commandExecutor);
        }
    }

    @Nested
    class UpdateOperations {

        @DisplayName("Incompatible save permissions are reported before any mutation")
        @Test
        void incompatibleSavePermissionsAreReportedBeforeAnyMutation() {
            when(commandExecutor.executeCommand(eq("session"), anyString()))
                    .thenReturn(new mari.samba.infra.CommandResult(1, "", ""));
            assertThatThrownBy(() -> sambaConfigService.updateSmbConf("session", "[global]"))
                    .isInstanceOf(SambaCommandException.class)
                    .hasMessageContaining("Review configuration save sudo permissions");
            verify(commandExecutor, never()).execute(anyString(), anyString());
            verify(commandExecutor, never()).execute(anyString(), anyString(), anyString());
        }

        @DisplayName("Overlapping updates do not use shared legacy path")
        @Test
        void overlappingUpdatesDoNotUseSharedLegacyPath() {
            var writtenPaths = new java.util.ArrayList<String>();
            lenient()
                    .doAnswer(call -> {
                        writtenPaths.add(call.getArgument(1));
                        if (writtenPaths.size() == 1) sambaConfigService.updateSmbConf("second", "second content");
                        return "";
                    })
                    .when(commandExecutor)
                    .execute(anyString(), anyString(), anyString());

            sambaConfigService.updateSmbConf("first", "first content");

            assertThat(writtenPaths).hasSize(2);
            assertThat(writtenPaths).noneMatch(command -> command.contains("/tmp/smb.conf.tmp"));
        }

        @DisplayName("Update smb.conf writes checks and moves")
        @Test
        void updateSmbConf_writesChecksAndMoves() throws Exception {
            // given
            String sessionId = "sess-1";
            String newContent = "[global]\na=b";

            // when
            sambaConfigService.updateSmbConf(sessionId, newContent);

            // then
            InOrder order = inOrder(commandExecutor);
            order.verify(commandExecutor).executeCommand(sessionId, LinuxCommands.configSavePermissionProbe());
            order.verify(commandExecutor).execute(sessionId, LinuxCommands.mkdir("/etc/samba/backups"));
            order.verify(commandExecutor).execute(eq(sessionId), matches("bash -c .*"));
            order.verify(commandExecutor).execute(sessionId, LinuxCommands.cleanupOldBackups("/etc/samba/backups", 10));
            order.verify(commandExecutor)
                    .execute(
                            eq(sessionId),
                            eq(LinuxCommands.installSmbConfStdin("/etc/samba/smb.conf")),
                            eq(newContent));
            order.verify(commandExecutor).execute(eq(sessionId), eq(LinuxCommands.systemctl("restart", "smbd")));
            verifyNoMoreInteractions(commandExecutor);
        }

        private static final String UPDATE_SESSION = "session-failure";
        private static final String UPDATE_CONTENT = "[global]";

        @DisplayName("Update smb.conf stops after failed backup directory")
        @Test
        void updateSmbConf_stopsAfterFailedBackupDirectory() {
            var failure = new SambaCommandException("Synthetic failure");
            when(commandExecutor.execute(eq(UPDATE_SESSION), eq(LinuxCommands.mkdir("/etc/samba/backups"))))
                    .thenThrow(failure);
            assertThatThrownBy(() -> sambaConfigService.updateSmbConf(UPDATE_SESSION, UPDATE_CONTENT))
                    .isSameAs(failure);
            var order = inOrder(commandExecutor);
            order.verify(commandExecutor).executeCommand(UPDATE_SESSION, LinuxCommands.configSavePermissionProbe());
            order.verify(commandExecutor).execute(UPDATE_SESSION, LinuxCommands.mkdir("/etc/samba/backups"));
            order.verifyNoMoreInteractions();
            verify(commandExecutor, times(1)).execute(eq(UPDATE_SESSION), anyString());
            verify(commandExecutor, times(0)).execute(eq(UPDATE_SESSION), anyString(), anyString());
            verifyNoMoreInteractions(commandExecutor);
        }

        @DisplayName("Update smb.conf stops after failed safety backup")
        @Test
        void updateSmbConf_stopsAfterFailedSafetyBackup() {
            when(commandExecutor.execute(eq(UPDATE_SESSION), anyString())).thenReturn("");
            var failure = new SambaCommandException("Synthetic failure");
            when(commandExecutor.execute(eq(UPDATE_SESSION), startsWith("bash -c ")))
                    .thenThrow(failure);
            assertThatThrownBy(() -> sambaConfigService.updateSmbConf(UPDATE_SESSION, UPDATE_CONTENT))
                    .isSameAs(failure);
            var order = inOrder(commandExecutor);
            order.verify(commandExecutor).executeCommand(UPDATE_SESSION, LinuxCommands.configSavePermissionProbe());
            order.verify(commandExecutor).execute(UPDATE_SESSION, LinuxCommands.mkdir("/etc/samba/backups"));
            order.verify(commandExecutor).execute(eq(UPDATE_SESSION), startsWith("bash -c "));
            order.verifyNoMoreInteractions();
            verify(commandExecutor, times(2)).execute(eq(UPDATE_SESSION), anyString());
            verify(commandExecutor, times(0)).execute(eq(UPDATE_SESSION), anyString(), anyString());
            verifyNoMoreInteractions(commandExecutor);
        }

        @DisplayName("Update smb.conf stops after failed cleanup")
        @Test
        void updateSmbConf_stopsAfterFailedCleanup() {
            when(commandExecutor.execute(eq(UPDATE_SESSION), anyString())).thenReturn("");
            var failure = new SambaCommandException("Synthetic failure");
            when(commandExecutor.execute(
                            eq(UPDATE_SESSION), eq(LinuxCommands.cleanupOldBackups("/etc/samba/backups", 10))))
                    .thenThrow(failure);
            assertThatThrownBy(() -> sambaConfigService.updateSmbConf(UPDATE_SESSION, UPDATE_CONTENT))
                    .isSameAs(failure);
            var order = inOrder(commandExecutor);
            order.verify(commandExecutor).executeCommand(UPDATE_SESSION, LinuxCommands.configSavePermissionProbe());
            order.verify(commandExecutor).execute(UPDATE_SESSION, LinuxCommands.mkdir("/etc/samba/backups"));
            order.verify(commandExecutor).execute(eq(UPDATE_SESSION), startsWith("bash -c "));
            order.verify(commandExecutor)
                    .execute(UPDATE_SESSION, LinuxCommands.cleanupOldBackups("/etc/samba/backups", 10));
            order.verifyNoMoreInteractions();
            verify(commandExecutor, times(3)).execute(eq(UPDATE_SESSION), anyString());
            verify(commandExecutor, times(0)).execute(eq(UPDATE_SESSION), anyString(), anyString());
            verifyNoMoreInteractions(commandExecutor);
        }

        @DisplayName("Update smb.conf stops after failed install")
        @Test
        void updateSmbConf_stopsAfterFailedInstall() {
            when(commandExecutor.execute(eq(UPDATE_SESSION), anyString())).thenReturn("");
            var failure = new SambaCommandException("Synthetic failure");
            when(commandExecutor.execute(
                            UPDATE_SESSION, LinuxCommands.installSmbConfStdin("/etc/samba/smb.conf"), UPDATE_CONTENT))
                    .thenThrow(failure);
            assertThatThrownBy(() -> sambaConfigService.updateSmbConf(UPDATE_SESSION, UPDATE_CONTENT))
                    .isSameAs(failure);
            var order = inOrder(commandExecutor);
            order.verify(commandExecutor).executeCommand(UPDATE_SESSION, LinuxCommands.configSavePermissionProbe());
            order.verify(commandExecutor).execute(UPDATE_SESSION, LinuxCommands.mkdir("/etc/samba/backups"));
            order.verify(commandExecutor).execute(eq(UPDATE_SESSION), startsWith("bash -c "));
            order.verify(commandExecutor)
                    .execute(UPDATE_SESSION, LinuxCommands.cleanupOldBackups("/etc/samba/backups", 10));
            order.verify(commandExecutor)
                    .execute(UPDATE_SESSION, LinuxCommands.installSmbConfStdin("/etc/samba/smb.conf"), UPDATE_CONTENT);
            order.verifyNoMoreInteractions();
            verify(commandExecutor, times(3)).execute(eq(UPDATE_SESSION), anyString());
            verify(commandExecutor, times(1)).execute(eq(UPDATE_SESSION), anyString(), anyString());
            verifyNoMoreInteractions(commandExecutor);
        }

        @DisplayName("Update smb.conf stops after failed restart")
        @Test
        void updateSmbConf_stopsAfterFailedRestart() {
            when(commandExecutor.execute(
                            UPDATE_SESSION, LinuxCommands.installSmbConfStdin("/etc/samba/smb.conf"), UPDATE_CONTENT))
                    .thenReturn("");
            when(commandExecutor.execute(eq(UPDATE_SESSION), anyString())).thenReturn("");
            var failure = new SambaCommandException("Synthetic failure");
            when(commandExecutor.execute(eq(UPDATE_SESSION), eq(LinuxCommands.systemctl("restart", "smbd"))))
                    .thenThrow(failure);
            assertThatThrownBy(() -> sambaConfigService.updateSmbConf(UPDATE_SESSION, UPDATE_CONTENT))
                    .isSameAs(failure);
            var order = inOrder(commandExecutor);
            order.verify(commandExecutor).executeCommand(UPDATE_SESSION, LinuxCommands.configSavePermissionProbe());
            order.verify(commandExecutor).execute(UPDATE_SESSION, LinuxCommands.mkdir("/etc/samba/backups"));
            order.verify(commandExecutor).execute(eq(UPDATE_SESSION), startsWith("bash -c "));
            order.verify(commandExecutor)
                    .execute(UPDATE_SESSION, LinuxCommands.cleanupOldBackups("/etc/samba/backups", 10));
            order.verify(commandExecutor)
                    .execute(UPDATE_SESSION, LinuxCommands.installSmbConfStdin("/etc/samba/smb.conf"), UPDATE_CONTENT);
            order.verify(commandExecutor).execute(UPDATE_SESSION, LinuxCommands.systemctl("restart", "smbd"));
            order.verifyNoMoreInteractions();
            verify(commandExecutor, times(4)).execute(eq(UPDATE_SESSION), anyString());
            verify(commandExecutor, times(1)).execute(eq(UPDATE_SESSION), anyString(), anyString());
            verifyNoMoreInteractions(commandExecutor);
        }

        @DisplayName("Get global config parses and returns config DTO")
        @Test
        void getGlobalConfig_parsesAndReturnsConfigDto() throws Exception {
            // given
            String sessionId = "sess-1";
            String configContent = "[global]";
            when(commandExecutor.execute(sessionId, LinuxCommands.cat("/etc/samba/smb.conf")))
                    .thenReturn(configContent);
            SambaGlobalConfigDto dto = new SambaGlobalConfigDto();
            when(smbConfParser.parseGlobalConfig(configContent)).thenReturn(dto);

            // when
            SambaGlobalConfigDto result = sambaConfigService.getGlobalConfig(sessionId);

            // then
            assertThat(result).isSameAs(dto);
        }

        @DisplayName("Update global config updates and saves configuration")
        @Test
        void updateGlobalConfig_updatesAndSavesConfiguration() throws Exception {
            // given
            String sessionId = "sess-1";
            SambaGlobalConfigDto updateDto = new SambaGlobalConfigDto();
            when(commandExecutor.execute(sessionId, LinuxCommands.cat("/etc/samba/smb.conf")))
                    .thenReturn("[global]");
            when(smbConfParser.updateGlobalSection("[global]", updateDto)).thenReturn("[global]\nserver string=Hello");

            // when
            sambaConfigService.updateGlobalConfig(sessionId, updateDto);

            // then
            verify(commandExecutor)
                    .execute(
                            sessionId,
                            LinuxCommands.installSmbConfStdin("/etc/samba/smb.conf"),
                            "[global]\nserver string=Hello");
        }
    }
}
