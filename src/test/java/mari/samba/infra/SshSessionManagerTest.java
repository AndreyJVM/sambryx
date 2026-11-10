package mari.samba.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import mari.samba.auth.ConnectionRequestDto;
import mari.samba.config.SambaProperties;
import mari.samba.core.SambaCommandException;
import mari.samba.core.SshSessionExpiredException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

class SshSessionManagerTest {

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path directory;

    private static final String SECRET = "synthetic-password-for-regression";
    private static final String COMMAND = LinuxCommands.netAdsJoin("admin", SECRET);
    private final Session session = mock(Session.class);
    private final ChannelExec channel = mock(ChannelExec.class);
    private final Logger logger = (Logger) LoggerFactory.getLogger(SshSessionManager.class);
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
    private final java.util.concurrent.atomic.AtomicReference<OutputStream> stdout =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicReference<OutputStream> stderr =
            new java.util.concurrent.atomic.AtomicReference<>();

    @BeforeEach
    void captureLogs() throws Exception {
        events.start();
        logger.addAppender(events);
        when(session.openChannel("exec")).thenReturn(channel);
        doAnswer(call -> {
                    stdout.set(call.getArgument(0));
                    return null;
                })
                .when(channel)
                .setOutputStream(any(OutputStream.class));
        doAnswer(call -> {
                    stderr.set(call.getArgument(0));
                    return null;
                })
                .when(channel)
                .setErrStream(any(OutputStream.class));
        useOutput("", "");
        when(channel.isConnected()).thenReturn(true);
    }

    @AfterEach
    void detachLogs() {
        logger.detachAppender(events);
        events.stop();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void failedCommandDoesNotExposeCommandOrRemoteOutput(boolean secretInStderr) throws Exception {
        when(channel.isClosed()).thenReturn(true);
        when(channel.getExitStatus()).thenReturn(7);
        useOutput(secretInStderr ? "" : SECRET, secretInStderr ? SECRET : "");

        assertThatThrownBy(() -> manager(Duration.ofSeconds(30)).execute("session", COMMAND))
                .isInstanceOf(SambaCommandException.class)
                .hasMessage("SSH command failed with code 7.")
                .satisfies(this::assertSafeException);

        assertSafeLogs();
        verify(channel).setCommand(COMMAND);
        verify(channel).disconnect();
    }

    @Test
    void channelFailureDoesNotRetainSensitiveExceptionCause() throws Exception {
        when(session.openChannel("exec")).thenThrow(new JSchException("failed " + COMMAND));

        assertThatThrownBy(() -> manager(Duration.ofSeconds(30)).execute("session", COMMAND))
                .isInstanceOf(SambaCommandException.class)
                .hasMessage("SSH command execution failed.")
                .hasNoCause()
                .satisfies(this::assertSafeException);

        assertSafeLogs();
    }

    @Test
    void channelConnectFailureDisconnectsAndDoesNotExposeSensitiveDetails() throws Exception {
        String originalMessage = "synthetic-connect-failure " + COMMAND;
        doThrow(new JSchException(originalMessage)).when(channel).connect(anyInt());

        assertThatThrownBy(() -> manager(Duration.ofSeconds(30)).execute("session", COMMAND, SECRET))
                .isInstanceOf(SambaCommandException.class)
                .hasMessage("SSH command execution failed.")
                .hasNoCause()
                .satisfies(this::assertSafeException);

        assertSafeLogs();
        assertThat(events.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
                .doesNotContain(originalMessage, "synthetic-connect-failure"));
        verify(channel).disconnect();
        verify(channel, never()).getOutputStream();
    }

    @ParameterizedTest
    @ValueSource(strings = {"execute", "executeWithInput", "executeCommand", "executeCommandWithInput"})
    void disconnectedSessionRejectsCommandsBeforeOpeningChannel(String variant) throws Exception {
        var file = java.nio.file.Files.writeString(directory.resolve("known_hosts"), "");
        SshSessionManager manager = new SshSessionManager(new SambaProperties(
                new SambaProperties.Ssh(
                        Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofSeconds(30), 3, file.toString()),
                null,
                null,
                null));
        try (var jsch = mockConstruction(JSch.class, (mock, context) -> {
            when(mock.getSession("admin", "fixture.test", 22)).thenReturn(session);
            when(mock.getHostKeyRepository()).thenReturn(mock(com.jcraft.jsch.HostKeyRepository.class));
        })) {
            manager.createSession(
                    "session", new ConnectionRequestDto("fixture.test", 22, "admin", "password", SECRET, null, null));
            when(session.isConnected()).thenReturn(false);

            assertThatThrownBy(() -> {
                        switch (variant) {
                            case "execute" -> manager.execute("session", COMMAND);
                            case "executeWithInput" -> manager.execute("session", COMMAND, SECRET);
                            case "executeCommand" -> manager.executeCommand("session", COMMAND);
                            case "executeCommandWithInput" -> manager.executeCommand("session", COMMAND, SECRET);
                            default -> throw new IllegalArgumentException(variant);
                        }
                    })
                    .isInstanceOf(SshSessionExpiredException.class);
            when(session.isConnected()).thenReturn(true);
            assertThatThrownBy(() -> manager.getSession("session")).isInstanceOf(SshSessionExpiredException.class);
            verify(session, never()).openChannel(anyString());
        } finally {
            manager.disconnect("session");
        }
    }

    @ParameterizedTest
    @CsvSource({"30000,3", "17000,5", "0,3"})
    void keepaliveSettingsReachTransportBeforeConnect(int intervalMillis, int countMax) throws Exception {
        var trust = java.nio.file.Files.writeString(directory.resolve("known_hosts"), "");
        var transport = spy(new JSch().getSession("admin", "fixture.test", 22));
        doAnswer(call -> {
                    assertThat(transport.getServerAliveInterval()).isEqualTo(intervalMillis);
                    assertThat(transport.getServerAliveCountMax()).isEqualTo(countMax);
                    return null;
                })
                .when(transport)
                .connect(anyInt());
        var properties = new SambaProperties(
                new SambaProperties.Ssh(
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(30),
                        Duration.ofMillis(intervalMillis),
                        countMax,
                        trust.toString()),
                null,
                null,
                null);
        var manager = new SshSessionManager(properties);
        try (var jsch = mockConstruction(JSch.class, (mock, context) -> {
            when(mock.getSession("admin", "fixture.test", 22)).thenReturn(transport);
            when(mock.getHostKeyRepository()).thenReturn(mock(com.jcraft.jsch.HostKeyRepository.class));
        })) {
            manager.createSession(
                    "session", new ConnectionRequestDto("fixture.test", 22, "admin", "password", SECRET, null, null));
        } finally {
            manager.disconnect("session");
        }
    }

    @Test
    void timeoutDoesNotExposeCommandAndDisconnectsChannel() {
        when(channel.isClosed()).thenReturn(false);

        assertThatThrownBy(() -> manager(Duration.ofMillis(-1)).execute("session", COMMAND))
                .isInstanceOf(SambaCommandException.class)
                .hasMessage("SSH command timed out after -1 ms.")
                .satisfies(this::assertSafeException);

        assertSafeLogs();
        verify(channel, org.mockito.Mockito.atLeastOnce()).disconnect();
    }

    @Test
    void blockedInputCannotBypassCommandTimeout() throws Exception {
        var release = new CountDownLatch(1);
        when(channel.isClosed()).thenReturn(false);
        when(channel.getOutputStream()).thenReturn(new OutputStream() {
            @Override
            public void write(int value) throws java.io.IOException {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Synthetic interrupted input");
                }
            }
        });
        try (var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var outcome = worker.submit(() -> {
                try {
                    manager(Duration.ofMillis(-1)).execute("session", COMMAND, SECRET);
                    return null;
                } catch (SambaCommandException error) {
                    return error;
                }
            });
            try {
                assertThat(outcome.get(5, TimeUnit.SECONDS))
                        .isInstanceOf(SambaCommandException.class)
                        .hasMessage("SSH command timed out after -1 ms.");
            } finally {
                release.countDown();
            }
        }
        verify(channel, atLeastOnce()).disconnect();
        assertSafeLogs();
    }

    @Test
    void interruptedCommandPreservesInterruptFlagAndReleasesResources() {
        when(channel.isClosed()).thenReturn(false);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> manager(Duration.ofSeconds(30)).execute("session", COMMAND))
                    .isInstanceOf(SambaCommandException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(channel).disconnect();
            assertSafeLogs();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void expiredDeadlineStillRejectsBufferedOutput() throws Exception {
        useOutput(SECRET, "");
        when(channel.isClosed()).thenReturn(false);
        assertThatThrownBy(() -> manager(Duration.ofMillis(-1)).execute("session", COMMAND))
                .isInstanceOf(SambaCommandException.class)
                .hasMessage("SSH command timed out after -1 ms.");
        assertSafeLogs();
    }

    @Test
    void inputIsConfiguredBeforeConnectAndPreservesUtf8() throws Exception {
        String input = "synthetic-input-\u00e9\n";
        var supplied = new java.util.concurrent.atomic.AtomicReference<InputStream>();
        doAnswer(call -> {
                    supplied.set(call.getArgument(0));
                    return null;
                })
                .when(channel)
                .setInputStream(any(InputStream.class));
        doAnswer(call -> {
                    assertThat(supplied.get()).isNotNull();
                    assertThat(supplied.get().readAllBytes()).isEqualTo(input.getBytes(StandardCharsets.UTF_8));
                    return null;
                })
                .when(channel)
                .connect(anyInt());
        when(channel.isClosed()).thenReturn(true);
        when(channel.getExitStatus()).thenReturn(0);

        assertThat(manager(Duration.ofSeconds(30)).execute("session", COMMAND, input))
                .isEmpty();
        verify(channel, never()).getOutputStream();
        verify(channel).disconnect();
        assertSafeLogs();
    }

    @Test
    void successfulCommandPreservesOutputWithoutLoggingIt() throws Exception {
        when(channel.isClosed()).thenReturn(true);
        when(channel.getExitStatus()).thenReturn(0);
        useOutput(SECRET, "");

        assertThat(manager(Duration.ofSeconds(30)).execute("session", COMMAND)).isEqualTo(SECRET);

        assertSafeLogs();
        verify(channel).disconnect();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"synthetic-http-session-token"})
    void missingSessionDoesNotExposeSessionToken(String sessionId) {
        SshSessionManager manager = new SshSessionManager(null);

        assertThatThrownBy(() -> manager.getSession(sessionId))
                .isInstanceOf(SshSessionExpiredException.class)
                .hasMessage("SSH session not found or disconnected. Please log in again.");
    }

    private SshSessionManager manager(Duration timeout) {
        SambaProperties properties = new SambaProperties(
                new SambaProperties.Ssh(
                        Duration.ofSeconds(1), timeout, Duration.ofSeconds(30), 3, "/etc/sambryx/known_hosts"),
                null,
                null,
                null);
        return new SshSessionManager(properties) {
            @Override
            public Session getSession(String sessionId) {
                return session;
            }
        };
    }

    private void assertSafeException(Throwable error) {
        StringWriter stackTrace = new StringWriter();
        error.printStackTrace(new PrintWriter(stackTrace));
        assertThat(stackTrace.toString()).doesNotContain(SECRET, COMMAND);
    }

    private void assertSafeLogs() {
        assertThat(events.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(SECRET, COMMAND);
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    private void useOutput(String output, String error) throws Exception {
        doAnswer(call -> {
                    stdout.get().write(output.getBytes(StandardCharsets.UTF_8));
                    stderr.get().write(error.getBytes(StandardCharsets.UTF_8));
                    return null;
                })
                .when(channel)
                .connect(anyInt());
    }
}
