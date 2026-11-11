package mari.samba.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import mari.samba.config.SambaProperties;
import mari.samba.core.SambaCommandException;
import mari.samba.infra.CommandExecutor;
import mari.samba.infra.CommandResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Epic("Monitoring and readiness")
@Feature("Unit: readiness decisions (mock commands)")
class ServerReadinessServiceTest {
    private final CommandExecutor executor = mock(CommandExecutor.class);
    private final SambaProperties properties = new SambaProperties(
            null, new SambaProperties.Paths("/custom/samba's.conf", "/backups", "/log"), null, null);
    private final ServerReadinessService service = new ServerReadinessService(executor, properties);

    @Test
    void successfulReadChecksDoNotClaimAdministrativeReadiness() {
        when(executor.executeCommand(eq("session"), anyString())).thenReturn(new CommandResult(0, "secret", "secret"));
        var report = service.inspect("session");
        assertThat(report.ready()).isFalse();
        assertThat(report.checks())
                .extracting(ReadinessReport.Check::status)
                .containsExactly(
                        ReadinessReport.Status.PASSED,
                        ReadinessReport.Status.PASSED,
                        ReadinessReport.Status.PASSED,
                        ReadinessReport.Status.PASSED,
                        ReadinessReport.Status.UNKNOWN);
        assertThat(report.toString()).doesNotContain("secret", "/custom/");
        var commands = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(executor, times(4)).executeCommand(eq("session"), commands.capture());
        assertThat(commands.getAllValues()).allSatisfy(command -> {
            assertThat(command).endsWith("> /dev/null 2>&1");
            assertThat(command).doesNotContain("sudo -n true", "restart", "tee", "cp ", "useradd", "chmod");
        });
        assertThat(commands.getAllValues().get(1)).contains("sudo -n cat -- '/custom/samba'\\''s.conf'");
        assertThat(commands.getAllValues().get(2)).contains("testparm -s -- '/custom/samba'\\''s.conf'");
        verifyNoMoreInteractions(executor);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 126, 127, -1})
    void rejectedProbesNeverBecomeSuccessOrExposeRemoteOutput(int code) {
        when(executor.executeCommand(eq("session"), anyString()))
                .thenReturn(new CommandResult(code, "secret", "secret"));
        var report = service.inspect("session");
        assertThat(report.ready()).isFalse();
        assertThat(report.checks()).hasSize(5).allSatisfy(check -> {
            assertThat(check.status()).isNotEqualTo(ReadinessReport.Status.PASSED);
            assertThat(check.reason()).isNotBlank();
            assertThat(check.nextAction()).isNotBlank();
        });
        assertThat(report.toString()).doesNotContain("secret");
    }

    @Test
    void transportFailureStopsFurtherRemoteProbesAndMarksRemainingUnknown() {
        when(executor.executeCommand(eq("session"), anyString())).thenThrow(new SambaCommandException("secret"));
        var report = service.inspect("session");
        assertThat(report.ready()).isFalse();
        assertThat(report.checks()).hasSize(5).allSatisfy(check -> assertThat(check.status())
                .isEqualTo(ReadinessReport.Status.UNKNOWN));
        verify(executor).executeCommand(eq("session"), anyString());
        verifyNoMoreInteractions(executor);
        assertThat(report.toString()).doesNotContain("secret");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void isolatedFailureDoesNotEraseOtherConfirmedChecks(int failedProbe) {
        var counter = new java.util.concurrent.atomic.AtomicInteger();
        when(executor.executeCommand(eq("session"), anyString()))
                .thenAnswer(invocation -> new CommandResult(counter.getAndIncrement() == failedProbe ? 1 : 0, "", ""));
        var report = service.inspect("session");
        assertThat(report.checks().subList(0, 4))
                .extracting(ReadinessReport.Check::status)
                .containsOnly(ReadinessReport.Status.PASSED, ReadinessReport.Status.FAILED);
        assertThat(report.checks().get(failedProbe).status()).isEqualTo(ReadinessReport.Status.FAILED);
        assertThat(report.checks().stream().filter(check -> check.status() == ReadinessReport.Status.PASSED))
                .hasSize(3);
        assertThat(report.ready()).isFalse();
    }

    @Test
    void absentCommandResultStopsFurtherProbes() {
        when(executor.executeCommand(eq("session"), anyString())).thenReturn(null);
        var report = service.inspect("session");
        assertThat(report.checks()).hasSize(5).allSatisfy(check -> assertThat(check.status())
                .isEqualTo(ReadinessReport.Status.UNKNOWN));
        verify(executor).executeCommand(eq("session"), anyString());
        verifyNoMoreInteractions(executor);
    }
}
