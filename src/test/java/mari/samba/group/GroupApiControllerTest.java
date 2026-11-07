package mari.samba.group;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(GroupApiController.class)
@AutoConfigureMockMvc(addFilters = false)
class GroupApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SambaGroupService groupService;

    @Test
    void testGetAllGroups_ShouldReturnGroups() throws Exception {
        SambaGroup group = new SambaGroup("developers", List.of("alice", "bob"), "smb_developers");
        when(groupService.getAllGroups(anyString())).thenReturn(List.of(group));

        mockMvc.perform(get("/api/groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].name").value("developers"))
                .andExpect(jsonPath("$.data[0].systemName").value("smb_developers"))
                .andExpect(jsonPath("$.data[0].members[0]").value("alice"));
    }

    @Test
    void testCreateGroup_ShouldReturnSuccess() throws Exception {
        SambaGroupCreateDto dto = new SambaGroupCreateDto("developers", "Description");

        mockMvc.perform(post("/api/groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").isNotEmpty());

        verify(groupService).createGroup(anyString(), any(SambaGroupCreateDto.class));
    }

    @Test
    void testDeleteGroup_ShouldReturnSuccess() throws Exception {
        mockMvc.perform(delete("/api/groups/developers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").isNotEmpty());

        verify(groupService).deleteGroup(anyString(), eq("developers"));
    }

    @Test
    void testAddUserToGroup_ShouldReturnSuccess() throws Exception {
        mockMvc.perform(post("/api/groups/developers/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").isNotEmpty());

        verify(groupService).addUserToGroup(anyString(), eq("alice"), eq("developers"));
    }

    @Test
    void testRemoveUserFromGroup_ShouldReturnSuccess() throws Exception {
        mockMvc.perform(delete("/api/groups/developers/users/alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").isNotEmpty());

        verify(groupService).removeUserFromGroup(anyString(), eq("alice"), eq("developers"));
    }
}
