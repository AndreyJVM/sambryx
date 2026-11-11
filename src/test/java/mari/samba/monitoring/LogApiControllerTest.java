package mari.samba.monitoring;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(LogApiController.class)
@AutoConfigureMockMvc(addFilters = false)
@Epic("Monitoring and readiness")
@Feature("HTTP without filters: log endpoint (mock service)")
class LogApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SambaLogService logService;

    @Test
    void testFetchRawLogs_ShouldReturnString() throws Exception {
        when(logService.getRecentLogs(anyString(), eq(50))).thenReturn("syslog line 1\nsyslog line 2");

        mockMvc.perform(get("/api/logs/raw").param("lines", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value("syslog line 1\nsyslog line 2"));
    }
}
