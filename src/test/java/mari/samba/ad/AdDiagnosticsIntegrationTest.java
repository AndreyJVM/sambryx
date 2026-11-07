package mari.samba.ad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.Session;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import mari.samba.core.GlobalExceptionHandler;
import mari.samba.infra.CommandResult;
import mari.samba.infra.LinuxCommands;
import mari.samba.infra.SshSessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Component test for diagnostics; HTTP authentication filters are covered separately. */
class AdDiagnosticsIntegrationTest {

    private static final String SECRET = "synthetic-ad-password-for-regression";
    private final Session ssh = mock(Session.class);
    private final List<String> commands = new ArrayList<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
    private MockMvc mvc;
    private CommandResult statusResult = new CommandResult(0, "Join is OK", "");

    @BeforeEach
    void setUp() {
        SshSessionManager executor = new SshSessionManager(null) {
            @Override
            public Session getSession(String sessionId) {
                return ssh;
            }
        };
        mvc = MockMvcBuilders.standaloneSetup(new AdApiController(new AdIntegrationService(executor)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        events.start();
        logger.addAppender(events);
    }

    @AfterEach
    void detachLogs() {
        logger.detachAppender(events);
        events.stop();
    }

    @ParameterizedTest
    @CsvSource({"join, true", "join, false", "leave, true", "leave, false"})
    void sshFailureDoesNotExposeOutputOrContinueWorkflow(String action, boolean secretInStderr) throws Exception {
        respondToDomainCommand(new CommandResult(7, secretInStderr ? "" : SECRET, secretInStderr ? SECRET : ""));

        String body = request(action, 500);

        assertThat(body).contains("SSH command failed with code 7.");
        assertSafeDiagnostics(body);
        assertStoppedAfterDomainCommand(action);
    }

    @ParameterizedTest
    @CsvSource({"failed", "ERROR"})
    void rejectedJoinOutputDoesNotExposeCredentialsOrRestartService(String marker) throws Exception {
        respondToDomainCommand(new CommandResult(0, marker + ": " + SECRET, ""));

        String body = request("join", 500);

        assertThat(body).contains("Domain join failed.");
        assertSafeDiagnostics(body);
        assertStoppedAfterDomainCommand("join");
    }

    @Test
    void successfulJoinStillRestartsServiceThenReadsStatus() throws Exception {
        respondToDomainCommand(new CommandResult(0, "Joined domain", ""));

        String body = request("join", 200);

        assertThat(body).contains("\"isJoined\":true");
        assertThat(commands)
                .containsExactly(
                        LinuxCommands.writeKrb5Conf(),
                        LinuxCommands.netAdsJoin("Administrator", SECRET),
                        LinuxCommands.systemctl("restart", "winbind"),
                        LinuxCommands.netAdsTestJoin());
        assertSafeDiagnostics(body);
    }

    @Test
    void successfulLeaveReadsStatusWithoutClaimingConfirmedNonMembership() throws Exception {
        statusResult = new CommandResult(0, "", "");
        respondToDomainCommand(new CommandResult(0, "Left domain", ""));

        var data = new ObjectMapper().readTree(request("leave", 200)).get("data");

        assertThat(data.get("isJoined").isNull()).isTrue();
        assertThat(commands)
                .containsExactly(LinuxCommands.netAdsLeave("Administrator", SECRET), LinuxCommands.netAdsTestJoin());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"join", "leave"})
    void statusFailureAfterDomainOperationDoesNotReturnSuccess(String action) throws Exception {
        statusResult = new CommandResult(1, "Join is OK", SECRET);
        respondToDomainCommand(new CommandResult(0, "Domain operation completed", ""));

        String body = request(action, 500);

        assertSafeDiagnostics(body);
        assertThat(body).doesNotContain("\"success\":true");
        assertThat(commands).endsWith(LinuxCommands.netAdsTestJoin());
    }

    @Test
    void confirmedMembershipDoesNotConfirmOtherChecksOrDomainName() throws Exception {
        respondToDomainCommand(new CommandResult(0, "", ""));
        var data = new ObjectMapper()
                .readTree(mvc.perform(get("/api/ad/status"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("data");
        assertThat(data.get("isJoined").booleanValue()).isTrue();
        for (String field : List.of("domainName", "dnsReachable", "kerberosWorking", "winbindWorking")) {
            assertThat(data.get(field).isNull()).as(field).isTrue();
        }
        assertThat(commands).containsExactly(LinuxCommands.netAdsTestJoin());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"unrecognized response", "Not joined", "Error: Join is OK", ""})
    void unrecognizedSuccessfulOutputDoesNotConfirmNonMembership(String output) throws Exception {
        statusResult = new CommandResult(0, output, "");
        respondToDomainCommand(new CommandResult(0, "", ""));
        var data = new ObjectMapper()
                .readTree(mvc.perform(get("/api/ad/status"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("data");
        assertThat(data.get("isJoined").isNull()).isTrue();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 7, 127})
    void failedStatusCannotBecomeMembershipEvenWhenOutputContainsSuccessMarker(int exitCode) throws Exception {
        statusResult = new CommandResult(exitCode, "Join is OK", SECRET);
        respondToDomainCommand(new CommandResult(0, "", ""));
        String body = mvc.perform(get("/api/ad/status"))
                .andExpect(status().isInternalServerError())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertSafeDiagnostics(body);
        assertThat(commands).containsExactly(LinuxCommands.netAdsTestJoin());
    }

    private void respondToDomainCommand(CommandResult result) throws Exception {
        when(ssh.openChannel("exec")).thenAnswer(invocation -> {
            ChannelExec channel = mock(ChannelExec.class);
            AtomicReference<String> command = new AtomicReference<>();
            org.mockito.Mockito.doAnswer(call -> {
                        String value = call.getArgument(0);
                        command.set(value);
                        commands.add(value);
                        return null;
                    })
                    .when(channel)
                    .setCommand(any(String.class));
            java.util.function.Supplier<CommandResult> response = () -> {
                if (LinuxCommands.netAdsTestJoin().equals(command.get())) {
                    return statusResult;
                }
                if (command.get().startsWith("sudo net ads ")) return result;
                return new CommandResult(0, "", "");
            };
            when(channel.getInputStream())
                    .thenAnswer(call ->
                            new ByteArrayInputStream(response.get().stdout().getBytes(StandardCharsets.UTF_8)));
            when(channel.getErrStream())
                    .thenAnswer(call ->
                            new ByteArrayInputStream(response.get().stderr().getBytes(StandardCharsets.UTF_8)));
            when(channel.getOutputStream()).thenReturn(new ByteArrayOutputStream());
            when(channel.isClosed()).thenReturn(true);
            when(channel.isConnected()).thenReturn(true);
            when(channel.getExitStatus()).thenAnswer(call -> response.get().exitCode());
            return channel;
        });
    }

    private String request(String action, int expectedStatus) throws Exception {
        String payload = new ObjectMapper()
                .writeValueAsString(new AdJoinRequestDto("example.test", "Administrator", SECRET, null));
        return mvc.perform(post("/api/ad/" + action)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().is(expectedStatus))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private void assertStoppedAfterDomainCommand(String action) {
        String domain = action.equals("join")
                ? LinuxCommands.netAdsJoin("Administrator", SECRET)
                : LinuxCommands.netAdsLeave("Administrator", SECRET);
        assertThat(commands)
                .containsExactlyElementsOf(
                        action.equals("join") ? List.of(LinuxCommands.writeKrb5Conf(), domain) : List.of(domain));
    }

    private void assertSafeDiagnostics(String body) {
        assertThat(body).doesNotContain(SECRET);
        assertThat(events.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(SECRET);
            if (event.getThrowableProxy() != null) {
                assertThat(ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(event.getThrowableProxy()))
                        .doesNotContain(SECRET);
            }
        });
    }
}
