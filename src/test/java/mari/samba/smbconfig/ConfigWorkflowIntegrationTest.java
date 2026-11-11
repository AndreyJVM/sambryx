package mari.samba.smbconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// Exercises HTTP validation, the real service/parser, and error mapping; security is tested separately.
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
@WebMvcTest(ConfigApiController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({SambaConfigService.class, SmbConfParser.class})
@EnableConfigurationProperties(SambaProperties.class)
@Epic("Configuration and backups")
@Feature("HTTP without filters: configuration workflows (real service, mock commands)")
class ConfigWorkflowIntegrationTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private CommandExecutor commands;

    @DisplayName("Comparison read failure returns safe error and no partial content")
    @Test
    void comparisonReadFailureReturnsSafeErrorAndNoPartialContent(
            org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        MockHttpSession session = new MockHttpSession();
        when(commands.execute(eq(session.getId()), anyString()))
                .thenThrow(new SambaCommandException("sensitive-config-content"));
        mvc.perform(get("/api/config/backups/smb.conf.backup_20301015_143000/compare")
                        .session(session))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(content()
                        .string(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("sensitive-config-content"))));
        verify(commands).execute(session.getId(), LinuxCommands.readComparisonFile("/etc/samba/smb.conf"));
        verifyNoMoreInteractions(commands);
        assertThat(output.getAll()).doesNotContain("sensitive-config-content");
    }

    @DisplayName("Comparison rejects invalid name without commands")
    @Test
    void comparisonRejectsInvalidNameWithoutCommands() throws Exception {
        mvc.perform(get("/api/config/backups/not-a-backup/compare"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(commands);
    }

    @DisplayName("Comparison reports changes from current file to selected backup without mutations")
    @Test
    void comparisonReportsChangesFromCurrentFileToSelectedBackupWithoutMutations() throws Exception {
        MockHttpSession session = new MockHttpSession();
        when(commands.execute(eq(session.getId()), anyString()))
                .thenReturn("[global]\nname = current\n", "[global]\nname = <backup>\n");
        mvc.perform(get("/api/config/backups/smb.conf.backup_20301015_143000/compare")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.filename").value("smb.conf.backup_20301015_143000"))
                .andExpect(jsonPath("$.data.equal").value(false))
                .andExpect(jsonPath("$.data.changes[0].type").value("REMOVED"))
                .andExpect(jsonPath("$.data.changes[0].text").value("name = current\n"))
                .andExpect(jsonPath("$.data.changes[1].type").value("ADDED"))
                .andExpect(jsonPath("$.data.changes[1].text").value("name = <backup>\n"));
        var captured = ArgumentCaptor.forClass(String.class);
        verify(commands, times(2)).execute(eq(session.getId()), captured.capture());
        assertThat(captured.getAllValues()).allSatisfy(command -> {
            assertThat(command)
                    .contains("head", "262145", "timeout")
                    .doesNotContain("cp ", "chmod", "systemctl", "testparm", "include");
        });
        verifyNoMoreInteractions(commands);
    }

    @DisplayName("Listing failure is safe and retry can confirm an empty directory")
    @Test
    void listingFailureIsSafeAndRetryCanConfirmAnEmptyDirectory() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String command = LinuxCommands.listBackupsDetailed("/etc/samba/backups");
        when(commands.execute(session.getId(), command))
                .thenThrow(new SambaCommandException("synthetic-sensitive-command-output"))
                .thenReturn("");

        mvc.perform(get("/api/config/backups").session(session))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(
                        jsonPath("$.message").value("Samba command failed: Configuration backup list is unavailable."))
                .andExpect(jsonPath("$.data").doesNotExist());
        mvc.perform(get("/api/config/backups").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isEmpty());
        verify(commands, times(2)).execute(session.getId(), command);
        verifyNoMoreInteractions(commands);
    }

    @DisplayName("Updating global settings preserves shares and validates before replacing config")
    @Test
    void updatingGlobalSettingsPreservesSharesAndValidatesBeforeReplacingConfig() throws Exception {
        when(commands.executeCommand(anyString(), anyString()))
                .thenReturn(new mari.samba.infra.CommandResult(0, "", ""));
        MockHttpSession session = new MockHttpSession();
        when(commands.execute(session.getId(), LinuxCommands.cat("/etc/samba/smb.conf")))
                .thenReturn("[global]\nworkgroup = OLD\n[team]\npath = /srv/team\nvalid users = @team\n");

        mvc.perform(put("/api/config/global")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                SambaGlobalConfigDto.builder().workgroup("NEW").build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
        InOrder order = inOrder(commands);
        order.verify(commands)
                .execute(
                        eq(session.getId()),
                        eq(LinuxCommands.installSmbConfStdin("/etc/samba/smb.conf")),
                        written.capture());
        order.verify(commands).execute(session.getId(), LinuxCommands.systemctl("restart", "smbd"));
        assertThat(written.getValue())
                .contains("workgroup = NEW", "[team]", "path = /srv/team", "valid users = @team")
                .doesNotContain("workgroup = OLD");
    }

    @DisplayName("Rejected configuration returns error without replacing file or restarting Samba")
    @Test
    void rejectedConfigurationReturnsErrorWithoutReplacingFileOrRestartingSamba() throws Exception {
        when(commands.executeCommand(anyString(), anyString()))
                .thenReturn(new mari.samba.infra.CommandResult(0, "", ""));
        MockHttpSession session = new MockHttpSession();
        when(commands.execute(
                        eq(session.getId()), eq(LinuxCommands.installSmbConfStdin("/etc/samba/smb.conf")), anyString()))
                .thenThrow(new SambaCommandException("Invalid configuration"));

        mvc.perform(put("/api/config/raw")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new SambaRawConfigDto("[global]\ninvalid setting = yes"))))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false));

        verify(commands)
                .execute(
                        eq(session.getId()), eq(LinuxCommands.installSmbConfStdin("/etc/samba/smb.conf")), anyString());
        verify(commands, never()).execute(session.getId(), LinuxCommands.systemctl("restart", "smbd"));
    }

    @DisplayName("Invalid backup returns error without replacing file or restarting Samba")
    @Test
    void invalidBackupReturnsErrorWithoutReplacingFileOrRestartingSamba() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String filename = "smb.conf.backup_20301015_143000";
        String backup = "/etc/samba/backups/" + filename;
        when(commands.execute(session.getId(), LinuxCommands.testparmSilent(backup)))
                .thenThrow(new SambaCommandException("Invalid backup configuration"));

        mvc.perform(post("/api/config/backups/{filename}/restore", filename).session(session))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false));

        verify(commands, never()).execute(session.getId(), LinuxCommands.copy(backup, "/etc/samba/smb.conf"));
        verify(commands, never()).execute(session.getId(), LinuxCommands.systemctl("restart", "smbd"));
    }

    @DisplayName("Restore copy failure returns error and does not restart Samba")
    @Test
    void restoreCopyFailureReturnsErrorAndDoesNotRestartSamba() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String filename = "smb.conf.backup_20301015_143000";
        when(commands.execute(
                        session.getId(), LinuxCommands.copy("/etc/samba/backups/" + filename, "/etc/samba/smb.conf")))
                .thenThrow(new SambaCommandException("Backup unavailable"));

        mvc.perform(post("/api/config/backups/restore").session(session).param("filename", filename))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false));

        verify(commands, never()).execute(session.getId(), LinuxCommands.systemctl("restart", "smbd"));
    }

    @DisplayName("Restore rejects path traversal before executing commands")
    @Test
    void restoreRejectsPathTraversalBeforeExecutingCommands() throws Exception {
        mvc.perform(post("/api/config/backups/restore").param("filename", "../smb.conf.backup_20301015_143000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(commands);
    }

    private static Stream<Arguments> emptyConfigurations() {
        return Stream.of(
                Arguments.of(named("missing content property", "{}")),
                Arguments.of(named("empty content", "{\"content\":\"\"}")),
                Arguments.of(named("blank content", "{\"content\":\"   \"}")));
    }

    @DisplayName("Empty configuration is rejected before any system command")
    @ParameterizedTest(name = "{0}")
    @MethodSource("emptyConfigurations")
    void emptyConfigurationIsRejectedBeforeAnySystemCommand(String body) throws Exception {
        mvc.perform(put("/api/config/raw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(commands);
    }
}
