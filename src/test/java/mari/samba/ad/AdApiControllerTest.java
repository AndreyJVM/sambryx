package mari.samba.ad;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdApiController.class)
@AutoConfigureMockMvc(addFilters = false)
@WithMockUser
@Epic("Active Directory")
@Feature("HTTP without filters: domain endpoints (mock service)")
class AdApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AdIntegrationService adIntegrationService;

    @Test
    void testGetStatus_ReturnsStatus() throws Exception {
        AdStatusDto mockStatus = new AdStatusDto(true, "mari.local", "SAMBA-SERVER", true, true, true);
        when(adIntegrationService.getStatus(any(String.class))).thenReturn(mockStatus);

        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(get("/api/ad/status").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.isJoined").value(true))
                .andExpect(jsonPath("$.data.statusMessage").value("mari.local"));
    }

    @Test
    void testJoinDomain_Success() throws Exception {
        AdJoinRequestDto requestDto = new AdJoinRequestDto("mari.local", "Administrator", "password123", "192.168.1.1");
        AdStatusDto mockStatus = new AdStatusDto(true, "mari.local", "SAMBA-SERVER", true, true, true);
        when(adIntegrationService.joinDomain(any(String.class), eq(requestDto))).thenReturn(mockStatus);

        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/api/ad/join")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Successfully joined domain"))
                .andExpect(jsonPath("$.data.isJoined").value(true));
    }

    @Test
    void testLeaveDomain_Success() throws Exception {
        AdJoinRequestDto requestDto = new AdJoinRequestDto("mari.local", "Administrator", "password123", "192.168.1.1");
        AdStatusDto mockStatus = new AdStatusDto(false, null, null, false, false, false);
        when(adIntegrationService.leaveDomain(any(String.class), eq(requestDto)))
                .thenReturn(mockStatus);

        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/api/ad/leave")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Successfully left domain"))
                .andExpect(jsonPath("$.data.isJoined").value(false));
    }
}
