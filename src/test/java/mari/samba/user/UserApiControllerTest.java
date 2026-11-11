package mari.samba.user;

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
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(UserApiController.class)
@AutoConfigureMockMvc(addFilters = false)
@Epic("Users and groups")
@Feature("HTTP without filters: user endpoints (mock service)")
class UserApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SambaUserService sambaUserService;

    @DisplayName("User listing returns username and full name from the mock service")
    @Test
    void testGetAllUsers_ShouldReturnUsersList() throws Exception {
        SambaUser testUser = new SambaUser("johndoe", "John Doe", true, "/home/johndoe", "/bin/bash");
        when(sambaUserService.getAllUsers(anyString())).thenReturn(List.of(testUser));

        mockMvc.perform(get("/api/users").sessionAttr("dummy", "dummy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].username").value("johndoe"))
                .andExpect(jsonPath("$.data[0].fullName").value("John Doe"));
    }

    @DisplayName("Creating a user delegates account fields and returns success")
    @Test
    void testCreateUser_ShouldReturnSuccessMsg() throws Exception {
        SambaUserCreateDto dto = new SambaUserCreateDto("johndoe", "John Doe", "Secret@123");

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").isNotEmpty());

        verify(sambaUserService).createUser(anyString(), eq("johndoe"), eq("Secret@123"), eq("John Doe"));
    }

    @DisplayName("Deleting a user delegates its username and returns success")
    @Test
    void testDeleteUser_ShouldReturnSuccessMsg() throws Exception {
        mockMvc.perform(delete("/api/users/johndoe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").isNotEmpty());

        verify(sambaUserService).deleteUser(anyString(), eq("johndoe"));
    }

    @DisplayName("Changing a password delegates its value and returns success")
    @Test
    void testChangePassword_ShouldReturnSuccessMsg() throws Exception {
        Map<String, String> body = Map.of("newPassword", "NewSecret@123");

        mockMvc.perform(put("/api/users/johndoe/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").isNotEmpty());

        verify(sambaUserService).changePassword(anyString(), eq("johndoe"), eq("NewSecret@123"));
    }
}
