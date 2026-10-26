package mari.samba.smbconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.*;

import java.util.List;
import mari.samba.config.SambaProperties;
import mari.samba.core.SambaCommandException;
import mari.samba.infra.CommandExecutor;
import mari.samba.infra.LinuxCommands;
import mari.samba.infra.SmbConfParser;
import mari.samba.share.SambaShare;
import mari.samba.share.SambaShareCreateDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
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

        sambaConfigService = new SambaConfigService(commandExecutor, smbConfParser, properties);
    }

    @Nested
    class ContentOperations {

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

            assertThat(executedCommands.get(1)).startsWith("sudo bash -c ").contains(".smb.conf.staging.XXXXXX");
            assertThat(executedCommands.get(2)).isEqualTo(LinuxCommands.cleanupOldBackups("/etc/samba/backups", 10));
        }

        @Test
        void listBackups_returnsParsedBackupList() throws Exception {
            // given
            String sessionId = "sess-1";
            String fakeLsOutput =
                    "-rw-r--r-- 1 root root 1200 2024-10-15 14:30:00 /etc/samba/backups/smb.conf.backup_20241015_143000\n"
                            + "-rw-r--r-- 1 root root 1150 2024-10-14 10:20:00 /etc/samba/backups/smb.conf.backup_20241014_102000";
            when(commandExecutor.execute(eq(sessionId), matches("ls -lh --time-style.*")))
                    .thenReturn(fakeLsOutput);

            // when
            List<SambaBackupDto> backups = sambaConfigService.listBackups(sessionId);

            // then
            assertThat(backups).hasSize(2);
            assertThat(backups.get(0).filename()).isEqualTo("smb.conf.backup_20241015_143000");
            assertThat(backups.get(0).createdAt()).isEqualTo("2024-10-15 14:30:00");
            assertThat(backups.get(0).size()).isEqualTo("1200");
        }

        @Test
        void listBackups_whenCommandFails_returnsEmptyList() throws Exception {
            // given
            String sessionId = "sess-1";
            when(commandExecutor.execute(eq(sessionId), anyString())).thenThrow(new RuntimeException("Command failed"));

            // when
            List<SambaBackupDto> backups = sambaConfigService.listBackups(sessionId);

            // then
            assertThat(backups).isEmpty();
        }

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
            order.verify(commandExecutor)
                    .execute(sessionId, LinuxCommands.copy("/etc/samba/backups/" + filename, "/etc/samba/smb.conf"));
            order.verify(commandExecutor).execute(sessionId, LinuxCommands.systemctl("restart", "smbd"));
        }

        @Test
        void restoreBackup_withInvalidName_throwsIllegalArgumentException() {
            // given
            String invalidName = "smb.conf.backup_latest"; // lacks correct timestamp format

            // when / then
            assertThatThrownBy(() -> sambaConfigService.restoreBackup("session-1", invalidName))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid backup filename format.");
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "../smb.conf.backup_20301015_143000",
                    "/tmp/smb.conf.backup_20301015_143000",
                    "smb.conf.backup_20301015_143000;id",
                    "",
                    "smb.conf.backup_20301015_143000.bak",
                    "smb.conf.backup_20301015_143000_0",
                    "smb.conf.backup_20301015_143000_01",
                    "smb.conf.backup_20301015_143000_100",
                    "smb.conf.backup_20301015_143000_1;id"
                })
        void restoreBackup_rejectsUnsafeNamesBeforeExecutingCommands(String filename) {
            assertThatThrownBy(() -> sambaConfigService.restoreBackup("session-1", filename))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(commandExecutor);
        }

        @ParameterizedTest
        @ValueSource(strings = {"safety-copy", "restore-copy", "restart"})
        void restoreBackup_stopsOnFailureWithoutContinuingDestructiveSteps(String stage) {
            String sessionId = "restore-session";
            String filename = "smb.conf.backup_20301015_143000";
            String restore = LinuxCommands.copy("/etc/samba/backups/" + filename, "/etc/samba/smb.conf");
            String restart = LinuxCommands.systemctl("restart", "smbd");
            SambaCommandException failure = new SambaCommandException("Synthetic " + stage + " failure");
            when(commandExecutor.execute(eq(sessionId), anyString())).thenAnswer(invocation -> {
                String command = invocation.getArgument(1);
                boolean shouldFail =
                        switch (stage) {
                            case "safety-copy" -> command.startsWith("sudo bash -c ");
                            case "restore-copy" -> command.equals(restore);
                            case "restart" -> command.equals(restart);
                            default -> false;
                        };
                if (shouldFail) {
                    throw failure;
                }
                return "";
            });

            assertThatThrownBy(() -> sambaConfigService.restoreBackup(sessionId, filename))
                    .isSameAs(failure);

            if (stage.equals("safety-copy")) {
                verify(commandExecutor, never()).execute(sessionId, restore);
                verify(commandExecutor, never())
                        .execute(sessionId, LinuxCommands.cleanupOldBackups("/etc/samba/backups", 10));
            } else {
                verify(commandExecutor).execute(sessionId, restore);
            }
            if (!stage.equals("restart")) {
                verify(commandExecutor, never()).execute(sessionId, restart);
            } else {
                verify(commandExecutor).execute(sessionId, restart);
            }
        }
    }

    @Nested
    class UpdateOperations {

        @Test
        void updateSmbConf_writesChecksAndMoves() throws Exception {
            // given
            String sessionId = "sess-1";
            String newContent = "[global]\na=b";

            // when
            sambaConfigService.updateSmbConf(sessionId, newContent);

            // then
            InOrder order = inOrder(commandExecutor);
            order.verify(commandExecutor).execute(sessionId, LinuxCommands.mkdir("/etc/samba/backups"));
            order.verify(commandExecutor).execute(eq(sessionId), matches("sudo bash -c .*"));
            order.verify(commandExecutor).execute(sessionId, LinuxCommands.cleanupOldBackups("/etc/samba/backups", 10));
            order.verify(commandExecutor)
                    .execute(eq(sessionId), eq(LinuxCommands.writeToFileStdin("/tmp/smb.conf.tmp")), eq(newContent));
            order.verify(commandExecutor).execute(eq(sessionId), eq(LinuxCommands.testparmSilent("/tmp/smb.conf.tmp")));
            order.verify(commandExecutor)
                    .execute(eq(sessionId), eq(LinuxCommands.move("/tmp/smb.conf.tmp", "/etc/samba/smb.conf")));
            order.verify(commandExecutor).execute(eq(sessionId), eq(LinuxCommands.systemctl("restart", "smbd")));
            verifyNoMoreInteractions(commandExecutor);
        }

        @ParameterizedTest
        @ValueSource(strings = {"backup", "backup-copy", "cleanup", "write", "validate", "move", "restart"})
        void updateSmbConf_stopsAfterFailedStage(String failedStage) {
            String sessionId = "session-failure";
            String content = "[global]\nworkgroup = TEST";
            String write = LinuxCommands.writeToFileStdin("/tmp/smb.conf.tmp");
            String validate = LinuxCommands.testparmSilent("/tmp/smb.conf.tmp");
            String move = LinuxCommands.move("/tmp/smb.conf.tmp", "/etc/samba/smb.conf");
            String restart = LinuxCommands.systemctl("restart", "smbd");
            SambaCommandException failure = new SambaCommandException("Synthetic " + failedStage + " failure");

            String failedCommand =
                    switch (failedStage) {
                        case "backup" -> LinuxCommands.mkdir("/etc/samba/backups");
                        case "cleanup" -> LinuxCommands.cleanupOldBackups("/etc/samba/backups", 10);
                        case "validate" -> validate;
                        case "move" -> move;
                        case "restart" -> restart;
                        case "backup-copy", "write" -> null;
                        default -> throw new IllegalArgumentException(failedStage);
                    };
            when(commandExecutor.execute(eq(sessionId), anyString())).thenAnswer(invocation -> {
                String command = invocation.getArgument(1);
                if (command.equals(failedCommand)
                        || (failedStage.equals("backup-copy") && command.startsWith("sudo bash -c "))) {
                    throw failure;
                }
                return "";
            });
            if (failedStage.equals("write")) {
                when(commandExecutor.execute(sessionId, write, content)).thenThrow(failure);
            }

            assertThatThrownBy(() -> sambaConfigService.updateSmbConf(sessionId, content))
                    .isSameAs(failure);

            if (failedStage.equals("backup")) {
                verify(commandExecutor).execute(sessionId, LinuxCommands.mkdir("/etc/samba/backups"));
                verifyNoMoreInteractions(commandExecutor);
            }
            if (List.of("backup", "backup-copy").contains(failedStage)) {
                verify(commandExecutor, never()).execute(sessionId, write, content);
                verify(commandExecutor, never())
                        .execute(sessionId, LinuxCommands.cleanupOldBackups("/etc/samba/backups", 10));
            }
            if (failedStage.equals("cleanup")) {
                verify(commandExecutor, never()).execute(sessionId, write, content);
            }
            if (List.of("backup", "backup-copy", "cleanup", "write").contains(failedStage)) {
                verify(commandExecutor, never()).execute(sessionId, validate);
            }
            if (List.of("backup", "backup-copy", "cleanup", "write", "validate").contains(failedStage)) {
                verify(commandExecutor, never()).execute(sessionId, move);
            }
            if (!failedStage.equals("restart")) {
                verify(commandExecutor, never()).execute(sessionId, restart);
            }
            if (failedStage.equals("restart")) {
                verify(commandExecutor).execute(sessionId, restart);
            }
        }

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
                            LinuxCommands.writeToFileStdin("/tmp/smb.conf.tmp"),
                            "[global]\nserver string=Hello");
        }
    }
}
