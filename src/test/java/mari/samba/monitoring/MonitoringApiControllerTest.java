package mari.samba.monitoring;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MonitoringApiController.class)
@AutoConfigureMockMvc(addFilters = false)
@Epic("Monitoring and readiness")
@Feature("HTTP without filters: monitoring endpoints (mock service)")
class MonitoringApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SambaMonitoringService monitoringService;

    @Test
    @DisplayName("A running dashboard serializes disk, connection and open-file statistics")
    void testGetDashboard_WhenRunning_ShouldReturnFullStats() throws Exception {
        when(monitoringService.isServiceRunning(anyString())).thenReturn(true);
        when(monitoringService.getDiskUsage(anyString())).thenReturn(List.of("/ 50% 100G"));
        when(monitoringService.getActiveConnections(anyString()))
                .thenReturn(List.of(Map.of("pid", "1234", "user", "root")));
        when(monitoringService.getOpenFiles(anyString())).thenReturn(List.of(Map.of("file", "/path/to/file")));

        mockMvc.perform(get("/api/monitoring/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.isRunning").value(true))
                .andExpect(jsonPath("$.data.diskUsage[0]").value("/ 50% 100G"))
                .andExpect(jsonPath("$.data.connections[0].pid").value("1234"))
                .andExpect(jsonPath("$.data.openFiles[0].file").value("/path/to/file"));
    }

    @Test
    @DisplayName("A stopped dashboard returns empty connections and open files")
    void testGetDashboard_WhenNotRunning_ShouldReturnEmptyData() throws Exception {
        when(monitoringService.isServiceRunning(anyString())).thenReturn(false);
        when(monitoringService.getDiskUsage(anyString())).thenReturn(List.of("/ 50% 100G"));

        mockMvc.perform(get("/api/monitoring/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.isRunning").value(false))
                .andExpect(jsonPath("$.data.connections").isEmpty())
                .andExpect(jsonPath("$.data.openFiles").isEmpty());
    }

    @Test
    @DisplayName("Service control delegates restart and returns a success message")
    void testControlService_ShouldReturnSuccess() throws Exception {
        mockMvc.perform(post("/api/monitoring/control").param("action", "restart"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").isNotEmpty());

        verify(monitoringService).controlService(anyString(), eq("restart"));
    }

    @Test
    @DisplayName("Session deletion delegates the process ID and returns a success message")
    void testKillSession_ShouldReturnSuccess() throws Exception {
        mockMvc.perform(delete("/api/monitoring/sessions/1234"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").isNotEmpty());

        verify(monitoringService).killSession(anyString(), eq("1234"));
    }
}
