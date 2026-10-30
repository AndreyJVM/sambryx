package mari.samba.share;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import mari.samba.config.SambaProperties;
import mari.samba.filesystem.FileSystemService;
import mari.samba.infra.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ShareAccessServiceTest {
    private final CommandExecutor executor = mock(CommandExecutor.class);
    private final SambaShareService shares = mock(SambaShareService.class);
    private ShareAccessService service;

    @BeforeEach
    void setup() {
        var properties = new SambaProperties(
                null,
                new SambaProperties.Paths("/etc/samba/smb.conf", "", ""),
                new SambaProperties.Security(List.of("/srv"), 5, Duration.ofMinutes(3)),
                null);
        service = new ShareAccessService(
                shares, executor, new SmbConfParser(), new FileSystemService(executor, properties), properties);
        when(shares.getShareByName("session", "team")).thenReturn(new SambaShare("team", "/srv/team"));
        when(executor.executeCommand(eq("session"), anyString())).thenAnswer(invocation -> {
            String command = invocation.getArgument(1);
            if (command.startsWith("testparm"))
                return ok(
                        "[global]\nsecurity = user\n[team]\npath = /srv/team\navailable = yes\nvalid users = alice\ninvalid users =\n");
            if (command.startsWith("getent")) return ok("alice:x:1000:1000:Alice:/home/alice:/bin/sh\n");
            if (command.startsWith("realpath")) return ok("/srv/team\n");
            if (command.startsWith("LC_ALL=C stat")) return ok("directory\n");
            throw new AssertionError("Unexpected command: " + command);
        });
    }

    @Test
    void baselineReportsFactsWithoutPromisingAccess() {
        var report = service.check("session", "team", "alice");
        assertThat(report.share()).isEqualTo("team");
        assertThat(report.username()).isEqualTo("alice");
        assertThat(report.findings())
                .extracting(ShareAccessReport.Finding::code)
                .contains("LOCAL_IDENTITY", "DIRECTORY", "LIMITS");
        assertThat(report.findings()).noneMatch(f -> f.status().equals("BLOCKING"));
        assertThat(report.findings()).anyMatch(f -> f.status().equals("UNKNOWN"));
    }

    @Test
    void effectiveUnavailableIsAConfirmedBlocker() {
        when(executor.executeCommand("session", "testparm -sv -- '/etc/samba/smb.conf' 2>/dev/null"))
                .thenReturn(ok("[global]\nsecurity = user\n[team]\npath = /srv/team\navailable = no\n"));
        assertThat(service.check("session", "team", "alice").findings())
                .anyMatch(f -> f.code().equals("UNAVAILABLE") && f.status().equals("BLOCKING"));
    }

    @Test
    void literalExclusionBlocksButGroupExpressionsRemainUnknown() {
        for (String rule : List.of("bob", "@admins", "alice, @admins")) {
            when(executor.executeCommand("session", "testparm -sv -- '/etc/samba/smb.conf' 2>/dev/null"))
                    .thenReturn(
                            ok("[global]\nsecurity = user\n[team]\npath = /srv/team\nvalid users = " + rule + "\n"));
            var findings = service.check("session", "team", "alice").findings();
            assertThat(findings)
                    .anyMatch(f -> f.code().equals("VALID_USERS")
                            && f.status().equals(rule.equals("bob") ? "BLOCKING" : "UNKNOWN"));
        }
    }

    @Test
    void invalidUserIsBlockedAndNonDirectoryIsNotAMissingDirectoryGuess() {
        when(executor.executeCommand("session", "testparm -sv -- '/etc/samba/smb.conf' 2>/dev/null"))
                .thenReturn(ok("[global]\nsecurity = user\n[team]\npath = /srv/team\ninvalid users = alice\n"));
        when(executor.executeCommand("session", "LC_ALL=C stat -L -c '%F' -- '/srv/team'"))
                .thenReturn(ok("regular file\n"));
        assertThat(service.check("session", "team", "alice").findings())
                .anyMatch(f -> f.code().equals("INVALID_USERS") && f.status().equals("BLOCKING"))
                .anyMatch(f -> f.code().equals("NOT_DIRECTORY") && f.status().equals("BLOCKING"));
    }

    @Test
    void failedCommandsDoNotLeakOrProduceSuccess() {
        when(executor.executeCommand(eq("session"), anyString()))
                .thenReturn(new CommandResult(1, "", "sensitive remote output"));
        var findings = service.check("session", "team", "alice").findings();
        assertThat(findings).allMatch(f -> f.status().equals("UNKNOWN"));
        assertThat(findings.toString()).doesNotContain("sensitive remote output");
    }

    @Test
    void sshFailureRemainsUnknown() {
        when(executor.executeCommand(eq("session"), anyString()))
                .thenThrow(new mari.samba.core.SambaCommandException("secret"));
        assertThat(service.check("session", "team", "alice").findings())
                .allMatch(f -> f.status().equals("UNKNOWN"));
    }

    @Test
    void unsafeUsernamesNeverReachSsh() {
        for (String name : List.of("-root", "alice;id", "$(id)", "alice\nroot", "DOMAIN\\alice")) {
            assertThatThrownBy(() -> service.check("session", "team", name))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(executor, shares);
    }

    @Test
    void escapedSymlinkOutsideRootsIsNotInspected() {
        when(executor.executeCommand("session", "realpath -e -- '/srv/team'")).thenReturn(ok("/etc/private\n"));
        assertThat(service.check("session", "team", "alice").findings())
                .anyMatch(f -> f.code().equals("PATH") && f.status().equals("UNKNOWN"));
        verify(executor, never()).executeCommand(eq("session"), startsWith("LC_ALL=C stat"));
        verify(executor, never()).execute(anyString(), anyString());
    }

    @Test
    void canonicalPathWithTrailingSpaceIsInspectedWithoutChangingItsIdentity() {
        when(executor.executeCommand("session", "realpath -e -- '/srv/team'")).thenReturn(ok("/srv/team \n"));
        when(executor.executeCommand("session", "LC_ALL=C stat -L -c '%F' -- '/srv/team'"))
                .thenReturn(new CommandResult(1, "", "missing"));
        assertThat(service.check("session", "team", "alice").findings())
                .anyMatch(f -> f.code().equals("DIRECTORY"));
        verify(executor).executeCommand("session", "LC_ALL=C stat -L -c '%F' -- '/srv/team '");
    }

    @Test
    void domainOrMappedIdentityDoesNotProduceLiteralUserVerdicts() {
        for (String global : List.of("security = ads", "security = user\nusername map = /etc/samba/map")) {
            when(executor.executeCommand("session", "testparm -sv -- '/etc/samba/smb.conf' 2>/dev/null"))
                    .thenReturn(ok("[global]\n" + global
                            + "\n[team]\npath = /srv/team\nvalid users = bob\ninvalid users = alice\n"));
            assertThat(service.check("session", "team", "alice").findings())
                    .filteredOn(f -> f.code().equals("VALID_USERS") || f.code().equals("INVALID_USERS"))
                    .hasSize(2)
                    .allMatch(f -> f.status().equals("UNKNOWN"));
        }
    }

    @Test
    void unknownShareCannotTriggerDiagnosticCommands() {
        when(shares.getShareByName("session", "team")).thenThrow(new IllegalArgumentException("Share not found"));
        assertThatThrownBy(() -> service.check("session", "team", "alice"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(executor);
    }

    @Test
    void quotedPathRemainsASingleArgumentAndOnlyReadCommandsAreUsed() {
        when(executor.executeCommand("session", "realpath -e -- '/srv/team'")).thenReturn(ok("/srv/team's data\n"));
        assertThat(service.check("session", "team", "alice").findings())
                .anyMatch(f -> f.code().equals("DIRECTORY"));
        var commands = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(executor, atLeastOnce()).executeCommand(eq("session"), commands.capture());
        assertThat(commands.getAllValues())
                .allMatch(c -> c.startsWith("getent ")
                        || c.startsWith("testparm ")
                        || c.startsWith("realpath ")
                        || c.startsWith("LC_ALL=C stat "));
        assertThat(commands.getAllValues()).contains("LC_ALL=C stat -L -c '%F' -- '/srv/team'\\''s data'");
        verify(executor, never()).execute(anyString(), anyString());
        verify(executor, never()).execute(anyString(), anyString(), anyString());
    }

    @Test
    void failedOrOversizedEffectiveDumpCannotBecomeAConfirmedFinding() {
        for (CommandResult result : List.of(
                new CommandResult(1, "[team]\navailable = no\n", "sensitive"),
                ok("[team]\navailable = no\n" + "x".repeat(262144)))) {
            when(executor.executeCommand("session", "testparm -sv -- '/etc/samba/smb.conf' 2>/dev/null"))
                    .thenReturn(result);
            assertThat(service.check("session", "team", "alice").findings())
                    .anyMatch(f -> f.code().equals("CONFIG") && f.status().equals("UNKNOWN"))
                    .noneMatch(f -> f.status().equals("BLOCKING"));
        }
    }

    @Test
    void unsafeOrUnsupportedConfiguredPathsNeverReachFilesystemCommands() {
        for (String path :
                List.of("/srv/link/../team", "/srv/../../etc", "/srv/%U", "/etc/shadow", "/srv/unsafe\u0000path")) {
            when(executor.executeCommand("session", "testparm -sv -- '/etc/samba/smb.conf' 2>/dev/null"))
                    .thenReturn(ok("[global]\nsecurity = user\n[team]\npath = " + path + "\n"));
            assertThat(service.check("session", "team", "alice").findings())
                    .anyMatch(f -> f.code().equals("PATH") && f.status().equals("UNKNOWN"));
        }
        verify(executor, never()).executeCommand(eq("session"), startsWith("realpath"));
        verify(executor, never()).executeCommand(eq("session"), startsWith("LC_ALL=C stat"));
    }

    private static CommandResult ok(String output) {
        return new CommandResult(0, output, "");
    }
}
