package mari.samba.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.util.stream.Stream;
import mari.samba.config.SambaProperties;
import mari.samba.core.SambaCommandException;
import mari.samba.infra.CommandExecutor;
import mari.samba.infra.CommandResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Epic("Monitoring and readiness")
@Feature("Unit: readiness decisions (mock commands)")
class ServerReadinessServiceTest {
    private final CommandExecutor executor = mock(CommandExecutor.class);
    private final SambaProperties properties = new SambaProperties(
            null, new SambaProperties.Paths("/custom/samba's.conf", "/backups", "/log"), null, null);
    private final ServerReadinessService service = new ServerReadinessService(executor, properties);

    @Test
    @DisplayName("Successful read probes retain unknown administrative readiness and hide remote output")
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

    @ParameterizedTest(name = "exit status={0}")
    @ValueSource(ints = {1, 126, 127, -1})
    @DisplayName("Rejected probes never become successful checks or expose remote output")
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
    @DisplayName("Transport failure stops probing and marks remaining checks unknown")
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

    @ParameterizedTest(name = "failed probe={0}")
    @MethodSource("failedProbes")
    @DisplayName("An isolated probe failure preserves three confirmed checks")
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

    private static Stream<Named<Integer>> failedProbes() {
        return Stream.of(
                Named.of("required tools", 0),
                Named.of("configuration read", 1),
                Named.of("configuration validation", 2),
                Named.of("account database read", 3));
    }

    @Test
    @DisplayName("A missing command result stops probing and leaves checks unknown")
    void absentCommandResultStopsFurtherProbes() {
        when(executor.executeCommand(eq("session"), anyString())).thenReturn(null);
        var report = service.inspect("session");
        assertThat(report.checks()).hasSize(5).allSatisfy(check -> assertThat(check.status())
                .isEqualTo(ReadinessReport.Status.UNKNOWN));
        verify(executor).executeCommand(eq("session"), anyString());
        verifyNoMoreInteractions(executor);
    }
}
