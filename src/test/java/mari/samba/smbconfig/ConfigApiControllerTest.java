package mari.samba.smbconfig;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ConfigApiController.class)
@AutoConfigureMockMvc(addFilters = false)
@Epic("Configuration and backups")
@Feature("HTTP without filters: configuration endpoints (mock service)")
class ConfigApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SambaConfigService configService;

    @DisplayName("Get global config should return DTO")
    @Test
    void testGetGlobalConfig_ShouldReturnDto() throws Exception {
        SambaGlobalConfigDto configDto =
                SambaGlobalConfigDto.builder().workgroup("WORKGROUP").build();
        when(configService.getGlobalConfig(anyString())).thenReturn(configDto);

        mockMvc.perform(get("/api/config/global"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.workgroup").value("WORKGROUP"));
    }

    @DisplayName("Update global config should return success")
    @Test
    void testUpdateGlobalConfig_ShouldReturnSuccess() throws Exception {
        SambaGlobalConfigDto payload =
                SambaGlobalConfigDto.builder().workgroup("WORKGROUP").build();

        mockMvc.perform(put("/api/config/global")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").isNotEmpty());

        verify(configService).updateGlobalConfig(anyString(), any(SambaGlobalConfigDto.class));
    }

    @DisplayName("Get raw config should return content")
    @Test
    void testGetRawConfig_ShouldReturnContent() throws Exception {
        when(configService.getSmbConfContent(anyString())).thenReturn("[global]\nworkgroup = WORKGROUP");

        mockMvc.perform(get("/api/config/raw"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value("[global]\nworkgroup = WORKGROUP"));
    }

    @DisplayName("Update raw config should return success")
    @Test
    void testUpdateRawConfig_ShouldReturnSuccess() throws Exception {
        SambaRawConfigDto dto = new SambaRawConfigDto("[global]\nworkgroup = WORKGROUP");

        mockMvc.perform(put("/api/config/raw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Configuration file updated and service restarted"));

        verify(configService).updateSmbConf(anyString(), eq("[global]\nworkgroup = WORKGROUP"));
    }

    @DisplayName("List backups should return list")
    @Test
    void testListBackups_ShouldReturnList() throws Exception {
        SambaBackupDto backup = new SambaBackupDto("smb.conf.backup", "2023-10-10", "1KB");
        when(configService.listBackups(anyString())).thenReturn(List.of(backup));

        mockMvc.perform(get("/api/config/backups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].filename").value("smb.conf.backup"));
    }

    @DisplayName("Create backup should return success")
    @Test
    void testCreateBackup_ShouldReturnSuccess() throws Exception {
        mockMvc.perform(post("/api/config/backups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Backup created successfully"));

        verify(configService).createBackup(anyString());
    }

    @DisplayName("Restore backup by path should return success")
    @Test
    void testRestoreBackupByPath_ShouldReturnSuccess() throws Exception {
        mockMvc.perform(post("/api/config/backups/smb.conf.bak/restore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Configuration restored from smb.conf.bak"));

        verify(configService).restoreBackup(anyString(), eq("smb.conf.bak"));
    }

    @DisplayName("Restore backup by param should return success")
    @Test
    void testRestoreBackupByParam_ShouldReturnSuccess() throws Exception {
        mockMvc.perform(post("/api/config/backups/restore").param("filename", "smb.conf.old"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Configuration restored from smb.conf.old"));

        verify(configService).restoreBackup(anyString(), eq("smb.conf.old"));
    }
}
