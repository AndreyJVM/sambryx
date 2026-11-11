package mari.samba.share;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ShareApiController.class)
@AutoConfigureMockMvc(addFilters = false)
@Epic("Shares and access")
@Feature("HTTP without filters: share endpoints (mock service)")
class ShareApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SambaShareService shareService;

    @Test
    void testGetAllShares_ShouldReturnShares() throws Exception {
        SambaShare share =
                SambaShare.builder().name("public").path("/srv/samba/public").build();

        when(shareService.getAllShares(anyString())).thenReturn(List.of(share));

        mockMvc.perform(get("/api/shares"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].name").value("public"))
                .andExpect(jsonPath("$.data[0].path").value("/srv/samba/public"));
    }

    @Test
    void testGetShareByName_ShouldReturnShare() throws Exception {
        SambaShare share =
                SambaShare.builder().name("private").path("/srv/samba/private").build();

        when(shareService.getShareByName(anyString(), eq("private"))).thenReturn(share);

        mockMvc.perform(get("/api/shares/private"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("private"));
    }

    @Test
    void testGetShareSize_ShouldReturnSize() throws Exception {
        when(shareService.getShareSize(anyString(), eq("private"))).thenReturn("15GB");

        mockMvc.perform(get("/api/shares/private/size"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Share size calculated successfully"))
                .andExpect(jsonPath("$.data").value("15GB"));
    }

    @Test
    void testCreateShare_ShouldReturnSuccess() throws Exception {
        SambaShareCreateDto dto = SambaShareCreateDto.builder()
                .name("new-share")
                .path("/srv/samba/new")
                .comment("New Share")
                .build();

        mockMvc.perform(post("/api/shares")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").isNotEmpty());

        verify(shareService).createShare(anyString(), any(SambaShareCreateDto.class));
    }

    @Test
    void testUpdateShare_ShouldReturnSuccess() throws Exception {
        SambaShareCreateDto dto = SambaShareCreateDto.builder()
                .name("data-share")
                .path("/srv/samba/data")
                .build();

        mockMvc.perform(put("/api/shares/data-share")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").isNotEmpty());

        verify(shareService).updateShare(anyString(), eq("data-share"), any(SambaShareCreateDto.class));
    }

    @Test
    void testDeleteShare_ShouldReturnSuccess() throws Exception {
        mockMvc.perform(delete("/api/shares/test-delete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Share 'test-delete' deleted successfully"));

        verify(shareService).deleteShare(anyString(), eq("test-delete"));
    }
}
