package mari.samba.infra;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import mari.samba.auth.ConnectionRequestDto;
import mari.samba.core.SambaCommandException;
import mari.samba.core.SshSessionExpiredException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ExecutionSessionManagerTest {
    private final SshSessionManager ssh = mock(SshSessionManager.class);
    private final ExecutionSessionManager manager = new ExecutionSessionManager(ssh);
    private final ConnectionRequestDto request =
            new ConnectionRequestDto("server", 22, "admin", "password", "secret", null, null);

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"synthetic-http-session-token"})
    void unknownSessionCannotExecuteCommands(String sessionId) {
        assertCommandsRejected(sessionId);
        verifyNoInteractions(ssh);
    }

    @Test
    void failedSshLoginDoesNotAllowCommands() throws Exception {
        doThrow(new SambaCommandException("Connection rejected")).when(ssh).createSession("session", request);
        assertThatThrownBy(() -> manager.createSession("session", request)).isInstanceOf(SambaCommandException.class);
        assertCommandsRejected("session");
        verifyNoCommandExecution();
    }

    @Test
    void failedReplacementClosesOldConnectionAndRevokesAccess() throws Exception {
        manager.createSession("session", request);
        doThrow(new SambaCommandException("Connection rejected")).when(ssh).createSession("session", request);
        assertThatThrownBy(() -> manager.createSession("session", request)).isInstanceOf(SambaCommandException.class);
        var order = inOrder(ssh);
        order.verify(ssh).createSession("session", request);
        order.verify(ssh).disconnect("session");
        order.verify(ssh).createSession("session", request);
        assertCommandsRejected("session");
        verifyNoCommandExecution();
    }

    @Test
    void logoutClosesConnectionOnlyOnceAndRevokesAccess() throws Exception {
        manager.createSession("session", request);
        manager.disconnect("session");
        manager.disconnect("session");
        manager.disconnect(null);
        verify(ssh).disconnect("session");
        assertCommandsRejected("session");
        verifyNoCommandExecution();
    }

    @Test
    void disconnectDuringAcceptedCommandDefersSshClosureUntilCommandReturns() throws Exception {
        manager.createSession("session", request);
        when(ssh.execute("session", "accepted")).thenAnswer(invocation -> {
            manager.disconnect("session");
            verify(ssh, never()).disconnect("session");
            return "completed";
        });
        org.assertj.core.api.Assertions.assertThat(manager.execute("session", "accepted"))
                .isEqualTo("completed");
        verify(ssh).disconnect("session");
        assertCommandsRejected("session");
    }

    @Test
    void acceptedWorkflowCompletesMultipleCommandsButAnotherThreadCannotEnterAfterRevocation() throws Exception {
        manager.createSession("session", request);
        try (var operation = manager.beginOperation("session")) {
            manager.execute("session", "first");
            manager.disconnect("session");
            java.util.concurrent.CompletableFuture.runAsync(
                            () -> assertThatThrownBy(() -> manager.beginOperation("session"))
                                    .isInstanceOf(SshSessionExpiredException.class))
                    .join();
            manager.execute("session", "second");
            verify(ssh, never()).disconnect("session");
        }
        verify(ssh).execute("session", "second");
        verify(ssh).disconnect("session");
    }

    @Test
    void revokedWorkflowCannotStartAnotherCommandAtDrainLimitEvenBeforeCleanupTick() throws Exception {
        var clock = new AdministratorSessionIntegrationTest.TestClock();
        var timedManager = new ExecutionSessionManager(ssh, clock);
        timedManager.createSession("session", request);
        try (var operation = timedManager.beginOperation("session")) {
            timedManager.disconnect("session");
            clock.now.set(clock.instant().plusSeconds(300));
            assertThatThrownBy(() -> timedManager.execute("session", "late"))
                    .isInstanceOf(SshSessionExpiredException.class);
        }
        verify(ssh, never()).execute("session", "late");
        verify(ssh).disconnect("session");
    }

    @Test
    void cleanupClosesHungWorkflowAtDrainLimitAndReleaseDoesNotDisconnectTwice() throws Exception {
        var clock = new AdministratorSessionIntegrationTest.TestClock();
        var timedManager = new ExecutionSessionManager(ssh, clock);
        timedManager.createSession("session", request);
        try (var operation = timedManager.beginOperation("session")) {
            timedManager.disconnect("session");
            clock.now.set(clock.instant().plusSeconds(299));
            timedManager.cleanupDrainingSessions();
            verify(ssh, never()).disconnect("session");
            clock.now.set(clock.instant().plusSeconds(1));
            timedManager.cleanupDrainingSessions();
            verify(ssh).disconnect("session");
        }
        verify(ssh, times(1)).disconnect("session");
    }

    private void assertCommandsRejected(String sessionId) {
        assertThatThrownBy(() -> manager.execute(sessionId, "command")).isInstanceOf(SshSessionExpiredException.class);
        assertThatThrownBy(() -> manager.execute(sessionId, "command", "input"))
                .isInstanceOf(SshSessionExpiredException.class);
        assertThatThrownBy(() -> manager.executeCommand(sessionId, "command"))
                .isInstanceOf(SshSessionExpiredException.class);
        assertThatThrownBy(() -> manager.executeCommand(sessionId, "command", "input"))
                .isInstanceOf(SshSessionExpiredException.class);
    }

    private void verifyNoCommandExecution() {
        verify(ssh, never()).execute(anyString(), anyString());
        verify(ssh, never()).execute(anyString(), anyString(), any());
        verify(ssh, never()).executeCommand(anyString(), anyString());
        verify(ssh, never()).executeCommand(anyString(), anyString(), any());
    }
}
