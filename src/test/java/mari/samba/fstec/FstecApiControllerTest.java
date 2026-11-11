package mari.samba.fstec;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FstecApiController.class)
@AutoConfigureMockMvc(addFilters = false)
@WithMockUser
@Epic("Compliance")
@Feature("HTTP without filters: compliance endpoint (mock service)")
class FstecApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FstecComplianceService fstecComplianceService;

    @Test
    @DisplayName("GET /api/fstec/check should return 200 and FstecReportDto")
    void testCheckComplianceReturnsReport() throws Exception {
        FstecReportDto mockReport = new FstecReportDto(
                Instant.now(),
                85,
                12,
                2,
                3,
                1,
                List.of(new FstecRuleResult(
                        "2.1-share-guest-ok",
                        "2.1",
                        "Запрет гостевого доступа",
                        "HIGH",
                        FstecStatus.OK,
                        "no",
                        "no",
                        "guest ok = no",
                        null,
                        List.of())));

        when(fstecComplianceService.check(any())).thenReturn(mockReport);

        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(get("/api/fstec/check").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.compliancePercent").value(85))
                .andExpect(jsonPath("$.data.okCount").value(12))
                .andExpect(jsonPath("$.data.failedCount").value(2))
                .andExpect(jsonPath("$.data.manualCount").value(3))
                .andExpect(jsonPath("$.data.notApplicableCount").value(1))
                .andExpect(jsonPath("$.data.rules[0].id").value("2.1-share-guest-ok"))
                .andExpect(jsonPath("$.data.rules[0].status").value("OK"));
    }
}
