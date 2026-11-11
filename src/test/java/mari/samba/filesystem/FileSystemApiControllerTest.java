package mari.samba.filesystem;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FileSystemApiController.class)
@AutoConfigureMockMvc(addFilters = false)
@Epic("Filesystem")
@Feature("HTTP without filters: filesystem endpoints (mock service)")
class FileSystemApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private FileSystemService fileSystemService;

    @DisplayName("Directory browsing returns the current path from the mock service")
    @Test
    void testBrowseDirectories_ShouldReturnResult() throws Exception {
        DirectoryBrowseResultDto result = new DirectoryBrowseResultDto("/srv/samba", "/srv", List.of());
        when(fileSystemService.listDirectories(anyString(), anyString())).thenReturn(result);

        mockMvc.perform(get("/api/fs/browse").param("path", "/srv/samba"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.currentPath").value("/srv/samba"));
    }

    @DisplayName("Directory creation accepts query parameters and returns success")
    @Test
    void testMakeDirectoryParams_ShouldReturnSuccess() throws Exception {
        mockMvc.perform(post("/api/fs/mkdir").param("parentPath", "/srv/samba").param("name", "newfolder"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").isNotEmpty());

        verify(fileSystemService).createDirectory(anyString(), eq("/srv/samba"), eq("newfolder"));
    }

    @DisplayName("Directory creation accepts a JSON request and returns success")
    @Test
    void testMakeDirectoryBody_ShouldReturnSuccess() throws Exception {
        CreateDirectoryDto dto = new CreateDirectoryDto("/srv/samba", "newfolder");

        mockMvc.perform(post("/api/fs/mkdir")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").isNotEmpty());

        verify(fileSystemService).createDirectory(anyString(), eq("/srv/samba"), eq("newfolder"));
    }

    @DisplayName("Directory creation accepts the JSON name alias and returns success")
    @Test
    void testMakeDirectoryBodyWithNameAlias_ShouldReturnSuccess() throws Exception {
        String jsonPayload = "{\"parentPath\":\"/srv/samba\",\"name\":\"aliasfolder\"}";

        mockMvc.perform(post("/api/fs/mkdir")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").isNotEmpty());

        verify(fileSystemService).createDirectory(anyString(), eq("/srv/samba"), eq("aliasfolder"));
    }

    @DisplayName("Disk usage endpoint returns usage from the mock service")
    @Test
    void testGetDiskUsage_ShouldReturnUsage() throws Exception {
        DiskUsageDto dto = new DiskUsageDto("/", "100G", "50G", "50G", 50, "/");
        when(fileSystemService.getDiskUsage(anyString(), anyString())).thenReturn(dto);

        mockMvc.perform(get("/api/fs/disk-usage").param("path", "/"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.total").value("100G"))
                .andExpect(jsonPath("$.data.usePercent").value(50));
    }

    @DisplayName("File download returns service bytes with attachment disposition")
    @Test
    void testDownloadFile_ShouldReturnOctetStream() throws Exception {
        byte[] content = "file data".getBytes();
        when(fileSystemService.downloadFile(anyString(), eq("/srv/samba/file.txt")))
                .thenReturn(content);

        String expectedHeader = ContentDisposition.attachment()
                .filename("file.txt", StandardCharsets.UTF_8)
                .build()
                .toString();

        mockMvc.perform(get("/api/fs/download").param("path", "/srv/samba/file.txt"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, expectedHeader))
                .andExpect(content().bytes(content));
    }
}
