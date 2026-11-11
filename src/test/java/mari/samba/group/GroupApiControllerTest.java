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

@WebMvcTest(GroupApiController.class)
@AutoConfigureMockMvc(addFilters = false)
@Epic("Users and groups")
@Feature("HTTP without filters: group endpoints (mock service)")
class GroupApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SambaGroupService groupService;

    @DisplayName("Group listing returns display name, system name and members")
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

    @DisplayName("Creating a group delegates its request and returns success")
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

    @DisplayName("Deleting a group delegates its name and returns success")
    @Test
    void testDeleteGroup_ShouldReturnSuccess() throws Exception {
        mockMvc.perform(delete("/api/groups/developers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").isNotEmpty());

        verify(groupService).deleteGroup(anyString(), eq("developers"));
    }

    @DisplayName("Adding a member delegates user and group names and returns success")
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

    @DisplayName("Removing a member delegates user and group names and returns success")
    @Test
    void testRemoveUserFromGroup_ShouldReturnSuccess() throws Exception {
        mockMvc.perform(delete("/api/groups/developers/users/alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").isNotEmpty());

        verify(groupService).removeUserFromGroup(anyString(), eq("alice"), eq("developers"));
    }
}
