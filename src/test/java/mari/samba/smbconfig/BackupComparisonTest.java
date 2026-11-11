package mari.samba.smbconfig;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import mari.samba.config.SambaProperties;
import mari.samba.core.SambaCommandException;
import mari.samba.infra.CommandExecutor;
import mari.samba.infra.LinuxCommands;
import mari.samba.infra.SmbConfParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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

    @ParameterizedTest
    @ValueSource(strings = {"bytes", "lines", "null", "binary", "encoding", "ssh", "secondRead"})
    void failsSafelyWithoutReturningPartialComparison(String mode) {
        String content =
                switch (mode) {
                    case "bytes" -> "a".repeat(262145);
                    case "lines" -> "x\n".repeat(1001);
                    case "binary" -> "sensitive\0text";
                    case "encoding" -> "sensitive\uFFFDtext";
                    default -> null;
                };
        if (mode.equals("ssh"))
            when(commands.execute(anyString(), anyString())).thenThrow(new SambaCommandException("sensitive"));
        else if (mode.equals("secondRead"))
            when(commands.execute(anyString(), anyString()))
                    .thenReturn("valid")
                    .thenThrow(new SambaCommandException("sensitive"));
        else when(commands.execute(anyString(), anyString())).thenReturn(content);
        assertThatThrownBy(() -> service.compareBackup("session", NAME))
                .isInstanceOf(SambaCommandException.class)
                .hasMessageContaining("comparison is unavailable")
                .hasMessageNotContaining("sensitive");
        verify(commands, atMost(2)).execute(eq("session"), anyString());
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

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void releasesCapacityAfterRepeatedReadFailures(int failedRead) {
        for (int attempt = 0; attempt < 3; attempt++) {
            reset(commands);
            if (failedRead == 1) {
                when(commands.execute(anyString(), anyString()))
                        .thenThrow(new SambaCommandException("private configuration"));
            } else {
                when(commands.execute(anyString(), anyString()))
                        .thenReturn("private configuration")
                        .thenThrow(new SambaCommandException("private configuration"));
            }
            assertThatThrownBy(() -> service.compareBackup("session", NAME))
                    .isInstanceOf(SambaCommandException.class)
                    .hasMessageNotContaining("private configuration");
            verify(commands, times(failedRead)).execute(eq("session"), anyString());
            verifyNoMoreInteractions(commands);
        }
        reset(commands);
        when(commands.execute(anyString(), anyString())).thenReturn("recovered");
        assertThat(service.compareBackup("session", NAME).equal()).isTrue();
        verifyReads();
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
