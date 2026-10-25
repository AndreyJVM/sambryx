package mari.samba.smbconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import mari.samba.config.SambaProperties;
import mari.samba.core.SambaCommandException;
import mari.samba.infra.CommandExecutor;
import mari.samba.infra.LinuxCommands;
import mari.samba.infra.SmbConfParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
@WebMvcTest(ConfigApiController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({SambaConfigService.class, SmbConfParser.class})
@EnableConfigurationProperties(SambaProperties.class)
class ConfigWorkflowIntegrationTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private CommandExecutor commands;

    @Test
    void updatingGlobalSettingsPreservesSharesAndValidatesBeforeReplacingConfig() throws Exception {
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
                        eq(LinuxCommands.writeToFileStdin("/tmp/smb.conf.tmp")),
                        written.capture());
        order.verify(commands).execute(session.getId(), LinuxCommands.testparmSilent("/tmp/smb.conf.tmp"));
        order.verify(commands).execute(session.getId(), LinuxCommands.move("/tmp/smb.conf.tmp", "/etc/samba/smb.conf"));
        order.verify(commands).execute(session.getId(), LinuxCommands.systemctl("restart", "smbd"));
        assertThat(written.getValue())
                .contains("workgroup = NEW", "[team]", "path = /srv/team", "valid users = @team")
                .doesNotContain("workgroup = OLD");
    }

    @Test
    void rejectedConfigurationReturnsErrorWithoutReplacingFileOrRestartingSamba() throws Exception {
        MockHttpSession session = new MockHttpSession();
        when(commands.execute(session.getId(), LinuxCommands.testparmSilent("/tmp/smb.conf.tmp")))
                .thenThrow(new SambaCommandException("Invalid configuration"));

        mvc.perform(put("/api/config/raw")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new SambaRawConfigDto("[global]\ninvalid setting = yes"))))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false));

        verify(commands)
                .execute(eq(session.getId()), eq(LinuxCommands.writeToFileStdin("/tmp/smb.conf.tmp")), anyString());
        verify(commands, never())
                .execute(session.getId(), LinuxCommands.move("/tmp/smb.conf.tmp", "/etc/samba/smb.conf"));
        verify(commands, never()).execute(session.getId(), LinuxCommands.systemctl("restart", "smbd"));
    }

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

    @Test
    void restoreRejectsPathTraversalBeforeExecutingCommands() throws Exception {
        mvc.perform(post("/api/config/backups/restore").param("filename", "../smb.conf.backup_20301015_143000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(commands);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"content\":\"\"}", "{\"content\":\"   \"}"})
    void emptyConfigurationIsRejectedBeforeAnySystemCommand(String body) throws Exception {
        mvc.perform(put("/api/config/raw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(commands);
    }
}
