package mari.samba.smbconfig;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import mari.samba.config.SambaProperties;
import mari.samba.core.SambaCommandException;
import mari.samba.infra.CommandExecutor;
import mari.samba.infra.LinuxCommands;
import mari.samba.infra.SmbConfParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class BackupComparisonTest {
    private static final String NAME = "smb.conf.backup_20301015_143000_1";
    private final CommandExecutor commands = mock(CommandExecutor.class);
    private final SambaConfigService service = new SambaConfigService(
            commands,
            new SmbConfParser(),
            new SambaProperties(
                    null,
                    new SambaProperties.Paths("/etc/samba/smb.conf", "/etc/samba/backups", "/var/log/samba/log.smbd"),
                    null,
                    null));

    @ParameterizedTest
    @ValueSource(strings = {"", "[global]\ninclude = /private/file\n", "x\r\ny\n", "x"})
    void reportsLiteralEqualityWithoutExpandingIncludes(String content) {
        when(commands.execute(eq("session"), anyString())).thenReturn(content);
        assertThat(service.compareBackup("session", NAME)).isEqualTo(new BackupComparison(NAME, true, List.of()));
        verifyReads();
    }

    @Test
    void distinguishesMissingFinalNewlineAndRepeatedLines() {
        when(commands.execute(eq("session"), anyString())).thenReturn("x\nx\n", "x\nx");
        assertThat(service.compareBackup("session", NAME).changes())
                .containsExactly(
                        new BackupComparison.Change("REMOVED", 2, "x\n"), new BackupComparison.Change("ADDED", 2, "x"));
        verifyReads();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "../smb.conf",
                "/etc/samba/smb.conf",
                "smb.conf.backup_20301015_143000;id",
                "smb.conf.backup_20301015_143000_0",
                ""
            })
    void rejectsInvalidNameBeforeAnyCommand(String filename) {
        assertThatThrownBy(() -> service.compareBackup("session", filename))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(commands);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unusableContents")
    void rejectsUnusableContentWithoutReturningPartialComparison(String description, String content) {
        when(commands.execute(anyString(), anyString())).thenReturn(content);
        assertComparisonUnavailable();
        verify(commands).execute("session", LinuxCommands.readComparisonFile("/etc/samba/smb.conf"));
        verifyNoMoreInteractions(commands);
    }

    private static Stream<Arguments> unusableContents() {
        return Stream.of(
                Arguments.of("byte limit exceeded", "a".repeat(262145)),
                Arguments.of("line limit exceeded", "x\n".repeat(1001)),
                Arguments.of("missing output", null),
                Arguments.of("binary content", "sensitive\0text"),
                Arguments.of("invalid encoding", "sensitive\uFFFDtext"));
    }

    @Test
    void hidesFailureFromCurrentConfigurationRead() {
        when(commands.execute(anyString(), anyString())).thenThrow(new SambaCommandException("sensitive"));
        assertComparisonUnavailable();
        verify(commands).execute("session", LinuxCommands.readComparisonFile("/etc/samba/smb.conf"));
        verifyNoMoreInteractions(commands);
    }

    @Test
    void hidesPartialContentWhenBackupReadFails() {
        when(commands.execute(anyString(), anyString()))
                .thenReturn("sensitive")
                .thenThrow(new SambaCommandException("sensitive"));
        assertComparisonUnavailable();
        verifyReads();
    }

    private void assertComparisonUnavailable() {
        assertThatThrownBy(() -> service.compareBackup("session", NAME))
                .isInstanceOf(SambaCommandException.class)
                .hasMessageContaining("comparison is unavailable")
                .hasMessageNotContaining("sensitive");
    }

    @Test
    void acceptsMaximumSizeWithoutTruncating() {
        String text = "a".repeat(262144);
        when(commands.execute(anyString(), anyString())).thenReturn(text);
        assertThat(service.compareBackup("session", NAME).equal()).isTrue();
        verifyReads();
    }

    @ParameterizedTest
    @ValueSource(strings = {"é", "😀"})
    void measuresUnicodeLimitInUtf8Bytes(String character) {
        int width = character.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        String maximum = character.repeat(262144 / width);
        when(commands.execute(anyString(), anyString())).thenReturn(maximum);
        assertThat(service.compareBackup("session", NAME).equal()).isTrue();
        verifyReads();
        reset(commands);
        when(commands.execute(anyString(), anyString())).thenReturn(maximum + "a");
        assertThatThrownBy(() -> service.compareBackup("session", NAME))
                .isInstanceOf(SambaCommandException.class)
                .hasMessageContaining("comparison is unavailable");
        verify(commands).execute("session", LinuxCommands.readComparisonFile("/etc/samba/smb.conf"));
        verifyNoMoreInteractions(commands);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void acceptsExactlyOneThousandLines(boolean finalNewline) {
        String content = "x\n".repeat(999) + (finalNewline ? "x\n" : "x");
        when(commands.execute(anyString(), anyString())).thenReturn(content);
        assertThat(service.compareBackup("session", NAME).equal()).isTrue();
        verifyReads();
    }

    @Test
    void releasesCapacityAfterRepeatedCurrentReadFailures() {
        when(commands.execute("session", LinuxCommands.readComparisonFile("/etc/samba/smb.conf")))
                .thenThrow(new SambaCommandException("private configuration"))
                .thenThrow(new SambaCommandException("private configuration"))
                .thenThrow(new SambaCommandException("private configuration"))
                .thenReturn("recovered");
        when(commands.execute("session", LinuxCommands.readComparisonFile("/etc/samba/backups/" + NAME)))
                .thenReturn("recovered");
        assertRepeatedFailuresThenRecovery(0);
    }

    @Test
    void releasesCapacityAfterRepeatedBackupReadFailures() {
        when(commands.execute("session", LinuxCommands.readComparisonFile("/etc/samba/smb.conf")))
                .thenReturn("private configuration", "private configuration", "private configuration", "recovered");
        when(commands.execute("session", LinuxCommands.readComparisonFile("/etc/samba/backups/" + NAME)))
                .thenThrow(new SambaCommandException("private configuration"))
                .thenThrow(new SambaCommandException("private configuration"))
                .thenThrow(new SambaCommandException("private configuration"))
                .thenReturn("recovered");
        assertRepeatedFailuresThenRecovery(1);
    }

    private void assertRepeatedFailuresThenRecovery(int backupReadsPerFailure) {
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> service.compareBackup("session", NAME))
                    .isInstanceOf(SambaCommandException.class)
                    .hasMessageNotContaining("private configuration");
            verify(commands, times(attempt + 1))
                    .execute("session", LinuxCommands.readComparisonFile("/etc/samba/smb.conf"));
            verify(commands, times((attempt + 1) * backupReadsPerFailure))
                    .execute("session", LinuxCommands.readComparisonFile("/etc/samba/backups/" + NAME));
            verifyNoMoreInteractions(commands);
        }
        assertThat(service.compareBackup("session", NAME).equal()).isTrue();
        verify(commands, times(4)).execute("session", LinuxCommands.readComparisonFile("/etc/samba/smb.conf"));
        verify(commands, times(3 * backupReadsPerFailure + 1))
                .execute("session", LinuxCommands.readComparisonFile("/etc/samba/backups/" + NAME));
        verifyNoMoreInteractions(commands);
    }

    @Test
    void boundsConcurrentComparisonsAndReleasesSlots() throws Exception {
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        when(commands.execute(anyString(), anyString())).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Read not released");
            return "same";
        });
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> service.compareBackup("session", NAME));
            var second = workers.submit(() -> service.compareBackup("session", NAME));
            try {
                assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        .isTrue();
                assertThatThrownBy(() -> service.compareBackup("session", NAME))
                        .isInstanceOf(SambaCommandException.class);
            } finally {
                release.countDown();
            }
            assertThat(first.get().equal()).isTrue();
            assertThat(second.get().equal()).isTrue();
        }
        assertThat(service.compareBackup("session", NAME).equal()).isTrue();
    }

    private void verifyReads() {
        verify(commands).execute("session", LinuxCommands.readComparisonFile("/etc/samba/smb.conf"));
        verify(commands).execute("session", LinuxCommands.readComparisonFile("/etc/samba/backups/" + NAME));
        verifyNoMoreInteractions(commands);
    }
}
