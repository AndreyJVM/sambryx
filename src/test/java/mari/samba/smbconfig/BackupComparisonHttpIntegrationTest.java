package mari.samba.smbconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.time.Clock;
import java.time.Instant;
import java.util.stream.Stream;
import mari.samba.auth.AuthApiController;
import mari.samba.auth.AuthServiceImpl;
import mari.samba.auth.BruteForceProtectionServiceImpl;
import mari.samba.config.SambaProperties;
import mari.samba.config.SecurityConfig;
import mari.samba.core.SambaCommandException;
import mari.samba.core.SshSessionExpiredException;
import mari.samba.infra.ExecutionSessionManager;
import mari.samba.infra.LinuxCommands;
import mari.samba.infra.SmbConfParser;
import mari.samba.infra.SshSessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({AuthApiController.class, ConfigApiController.class})
@Import({
    SecurityConfig.class,
    AuthServiceImpl.class,
    BruteForceProtectionServiceImpl.class,
    ExecutionSessionManager.class,
    SambaConfigService.class,
    SmbConfParser.class
})
@EnableConfigurationProperties(SambaProperties.class)
@Epic("Configuration and backups")
@Feature("HTTP security chain: literal backup comparison (mock SSH)")
class BackupComparisonHttpIntegrationTest {
    private static final String FILENAME = "smb.conf.backup_20301015_143000_2";
    private static final String ROUTE = "/api/config/backups/" + FILENAME + "/compare";
    private static final String CURRENT = LinuxCommands.readComparisonFile("/etc/samba/smb.conf");
    private static final String BACKUP = LinuxCommands.readComparisonFile("/etc/samba/backups/" + FILENAME);
    private static final Instant NOW = Instant.parse("2030-10-15T14:30:00Z");
    private static final String UNAVAILABLE = "Samba command failed: Configuration backup comparison is unavailable "
            + "(read failure, limit exceeded or busy). Try again later.";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ExecutionSessionManager execution;

    @MockitoBean
    private SshSessionManager ssh;

    @MockitoBean(name = "sessionClock")
    private Clock clock;

    @BeforeEach
    void setClock() {
        when(clock.instant()).thenReturn(NOW);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @DisplayName("Anonymous comparison cannot reach SSH")
    @Test
    void anonymousComparisonCannotReachSsh() throws Exception {
        mvc.perform(get(ROUTE)).andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Location"));
        verifyNoInteractions(ssh);
    }

    private static Stream<Arguments> comparisonOutcomes() {
        return Stream.of(
                Arguments.of(named("identical literal content", true)),
                Arguments.of(named("different literal content", false)));
    }

    @DisplayName("Authenticated comparison returns literal contract without writes")
    @ParameterizedTest(name = "{0}")
    @MethodSource("comparisonOutcomes")
    void authenticatedComparisonReturnsLiteralContractWithoutWrites(boolean equal) throws Exception {
        MockHttpSession session = login();
        when(ssh.execute(session.getId(), CURRENT)).thenReturn("[global]\nname = current\n");
        when(ssh.execute(session.getId(), BACKUP))
                .thenReturn(equal ? "[global]\nname = current\n" : "[global]\nname = <backup>\n");
        var result = mvc.perform(get(ROUTE).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.filename").value(FILENAME))
                .andExpect(jsonPath("$.data.equal").value(equal));
        if (equal) {
            result.andExpect(jsonPath("$.data.changes").isEmpty());
        } else {
            result.andExpect(jsonPath("$.data.changes.length()").value(2))
                    .andExpect(jsonPath("$.data.changes[0].type").value("REMOVED"))
                    .andExpect(jsonPath("$.data.changes[0].lineNumber").value(2))
                    .andExpect(jsonPath("$.data.changes[0].text").value("name = current\n"))
                    .andExpect(jsonPath("$.data.changes[1].type").value("ADDED"))
                    .andExpect(jsonPath("$.data.changes[1].lineNumber").value(2))
                    .andExpect(jsonPath("$.data.changes[1].text").value("name = <backup>\n"));
        }
        verifyReads(session, true);
    }

    @DisplayName("Authenticated invalid filename is rejected before SSH")
    @Test
    void authenticatedInvalidFilenameIsRejectedBeforeSsh() throws Exception {
        MockHttpSession session = login();
        mvc.perform(get("/api/config/backups/smb.conf.backup_20301015_143000_0/compare")
                        .session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Invalid backup filename format."))
                .andExpect(jsonPath("$.data").doesNotExist());
        verifyNoInteractions(ssh);
    }

    @DisplayName("Expired administrator session rejects comparison before reads")
    @Test
    void expiredAdministratorSessionRejectsComparisonBeforeReads() throws Exception {
        MockHttpSession session = login();
        when(clock.instant()).thenReturn(NOW.plusSeconds(900));
        mvc.perform(get(ROUTE).session(session)).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
        verify(ssh).disconnect(session.getId());
        verifyNoMoreInteractions(ssh);
    }

    @DisplayName("Disconnected execution session rejects comparison before reads")
    @Test
    void disconnectedExecutionSessionRejectsComparisonBeforeReads() throws Exception {
        MockHttpSession session = login();
        execution.disconnect(session.getId());
        clearInvocations(ssh);
        mvc.perform(get(ROUTE).session(session)).andExpect(status().isUnauthorized());
        verifyNoInteractions(ssh);
    }

    @DisplayName("SSH expiry during read uses unauthorized handler and invalidates session")
    @Test
    void sshExpiryDuringReadUsesUnauthorizedHandlerAndInvalidatesSession() throws Exception {
        MockHttpSession session = login();
        when(ssh.execute(session.getId(), CURRENT))
                .thenThrow(
                        new SshSessionExpiredException("SSH session not found or disconnected. Please log in again."));
        mvc.perform(get(ROUTE).session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("SSH session not found or disconnected. Please log in again."))
                .andExpect(jsonPath("$.data").doesNotExist());
        assertThat(session.isInvalid()).isTrue();
        verifyReads(session, false);
    }

    private static Stream<Arguments> backupFailures() {
        return Stream.of(
                Arguments.of(named("binary backup content", true)),
                Arguments.of(named("backup read exception", false)));
    }

    @DisplayName("Backup failure does not expose current content or raw failure")
    @ParameterizedTest(name = "{0}")
    @MethodSource("backupFailures")
    void backupFailureDoesNotExposeCurrentContentOrRawFailure(boolean malformed) throws Exception {
        MockHttpSession session = login();
        when(ssh.execute(session.getId(), CURRENT)).thenReturn("[global]\nsecret = current-sensitive\n");
        if (malformed) {
            when(ssh.execute(session.getId(), BACKUP)).thenReturn("backup-sensitive\0");
        } else {
            when(ssh.execute(session.getId(), BACKUP)).thenThrow(new SambaCommandException("backup-sensitive"));
        }
        String body = mvc.perform(get(ROUTE).session(session))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(UNAVAILABLE))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).doesNotContain("current-sensitive", "backup-sensitive", "SambaCommandException");
        verifyReads(session, true);
    }

    private MockHttpSession login() throws Exception {
        var token = mvc.perform(get("/api/auth/environment"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie("XSRF-TOKEN");
        assertThat(token).isNotNull();
        var result = mvc.perform(
                        post("/api/auth/login")
                                .cookie(token)
                                .header("X-XSRF-TOKEN", token.getValue())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                        {"host":"samba-node","port":22,"username":"qa-user","password":"test-only"}
                        """))
                .andExpect(status().isOk())
                .andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        verify(ssh).createSession(eq(session.getId()), any());
        clearInvocations(ssh);
        return session;
    }

    private void verifyReads(MockHttpSession session, boolean backupRead) {
        var order = inOrder(ssh);
        order.verify(ssh).execute(session.getId(), CURRENT);
        if (backupRead) order.verify(ssh).execute(session.getId(), BACKUP);
        verifyNoMoreInteractions(ssh);
    }
}
