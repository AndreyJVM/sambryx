package mari.samba.smbconfig;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Named.named;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import mari.samba.config.SambaProperties;
import mari.samba.core.SambaCommandException;
import mari.samba.infra.CommandExecutor;
import mari.samba.infra.LinuxCommands;
import mari.samba.infra.SmbConfParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Epic("Configuration and backups")
@Feature("Unit: literal backup comparison (mock commands)")
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

    @DisplayName("Reports literal equality without expanding includes")
    @ParameterizedTest(name = "{0}")
    @MethodSource("literalContents")
    void reportsLiteralEqualityWithoutExpandingIncludes(String content) {
        when(commands.execute(eq("session"), anyString())).thenReturn(content);
        assertThat(service.compareBackup("session", NAME)).isEqualTo(new BackupComparison(NAME, true, List.of()));
        verifyReads();
    }

    private static Stream<Arguments> literalContents() {
        return Stream.of(
                Arguments.of(named("empty files", "")),
                Arguments.of(named("include directive stays literal", "[global]\ninclude = /private/file\n")),
                Arguments.of(named("mixed CRLF and LF", "x\r\ny\n")),
                Arguments.of(named("no final newline", "x")));
    }

    @DisplayName("Distinguishes missing final newline and repeated lines")
    @Test
    void distinguishesMissingFinalNewlineAndRepeatedLines() {
        when(commands.execute(eq("session"), anyString())).thenReturn("x\nx\n", "x\nx");
        assertThat(service.compareBackup("session", NAME).changes())
                .containsExactly(
                        new BackupComparison.Change("REMOVED", 2, "x\n"), new BackupComparison.Change("ADDED", 2, "x"));
        verifyReads();
    }

    @DisplayName("Rejects invalid backup names before executing commands")
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidNames")
    void rejectsInvalidNameBeforeAnyCommand(String filename) {
        assertThatThrownBy(() -> service.compareBackup("session", filename))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(commands);
    }

    private static Stream<Arguments> invalidNames() {
        return Stream.of(
                Arguments.of(named("path traversal", "../smb.conf")),
                Arguments.of(named("absolute path", "/etc/samba/smb.conf")),
                Arguments.of(named("shell separator", "smb.conf.backup_20301015_143000;id")),
                Arguments.of(named("zero collision suffix", "smb.conf.backup_20301015_143000_0")),
                Arguments.of(named("empty name", "")));
    }

    @DisplayName("Rejects unusable content without returning partial comparison")
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

    @DisplayName("Hides failure from current configuration read")
    @Test
    void hidesFailureFromCurrentConfigurationRead() {
        when(commands.execute(anyString(), anyString())).thenThrow(new SambaCommandException("sensitive"));
        assertComparisonUnavailable();
        verify(commands).execute("session", LinuxCommands.readComparisonFile("/etc/samba/smb.conf"));
        verifyNoMoreInteractions(commands);
    }

    @DisplayName("Hides partial content when backup read fails")
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

    @DisplayName("Accepts maximum size without truncating")
    @Test
    void acceptsMaximumSizeWithoutTruncating() {
        String text = "a".repeat(262144);
        when(commands.execute(anyString(), anyString())).thenReturn(text);
        assertThat(service.compareBackup("session", NAME).equal()).isTrue();
        verifyReads();
    }

    @DisplayName("Measures unicode limit in UTF-8 bytes")
    @ParameterizedTest(name = "UTF-8 character: {0}")
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

    @DisplayName("Accepts exactly one thousand lines")
    @ParameterizedTest(name = "{0}")
    @MethodSource("lineEndings")
    void acceptsExactlyOneThousandLines(boolean finalNewline) {
        String content = "x\n".repeat(999) + (finalNewline ? "x\n" : "x");
        when(commands.execute(anyString(), anyString())).thenReturn(content);
        assertThat(service.compareBackup("session", NAME).equal()).isTrue();
        verifyReads();
    }

    @DisplayName("Releases capacity after repeated current read failures")
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

    @DisplayName("Releases capacity after repeated backup read failures")
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

    @DisplayName("Bounds concurrent comparisons and releases slots")
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

    private static Stream<Arguments> lineEndings() {
        return Stream.of(
                Arguments.of(named("final newline present", true)), Arguments.of(named("final newline absent", false)));
    }

    private void verifyReads() {
        verify(commands).execute("session", LinuxCommands.readComparisonFile("/etc/samba/smb.conf"));
        verify(commands).execute("session", LinuxCommands.readComparisonFile("/etc/samba/backups/" + NAME));
        verifyNoMoreInteractions(commands);
    }
}
