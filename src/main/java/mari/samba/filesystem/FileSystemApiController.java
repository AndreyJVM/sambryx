package mari.samba.filesystem;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import mari.samba.core.ApiResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/fs")
public class FileSystemApiController {
    @PostMapping("/copy")
    public ApiResponse<Void> copyItem(@NonNull HttpSession session, @RequestBody java.util.Map<String, String> body) {
        String sessionId = session.getId();
        String source = body.get("source");
        String destination = body.get("destination");
        fileSystemService.copyItem(sessionId, source, destination);
        return ApiResponse.ok("Copied successfully", null);
    }

    @PostMapping("/move")
    public ApiResponse<Void> moveItem(@NonNull HttpSession session, @RequestBody java.util.Map<String, String> body) {
        String sessionId = session.getId();
        String source = body.get("source");
        String destination = body.get("destination");
        fileSystemService.moveItem(sessionId, source, destination);
        return ApiResponse.ok("Moved successfully", null);
    }

    @PostMapping("/delete")
    public ApiResponse<Void> deleteItem(@NonNull HttpSession session, @RequestBody java.util.Map<String, String> body) {
        String sessionId = session.getId();
        String target = body.get("target");
        fileSystemService.deleteItem(sessionId, target);
        return ApiResponse.ok("Deleted successfully", null);
    }

    @PostMapping("/upload")
    public ApiResponse<Void> uploadFile(
            @NonNull HttpSession session, @RequestParam("path") String path, @RequestParam("file") MultipartFile file)
            throws Exception {
        String sessionId = session.getId();
        byte[] content = file.getBytes();
        String base64Content = Base64.getEncoder().encodeToString(content);
        fileSystemService.uploadFile(sessionId, path, file.getOriginalFilename(), base64Content);
        return ApiResponse.ok("File uploaded", null);
    }

    @GetMapping("/download")
    public ResponseEntity<byte[]> downloadFile(@NonNull HttpSession session, @RequestParam("path") String path) {
        String sessionId = session.getId();
        byte[] data = fileSystemService.downloadFile(sessionId, path);
        String filename = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        if (filename.isBlank()) {
            filename = "download";
        }
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(data);
    }

    private final FileSystemService fileSystemService;

    public FileSystemApiController(FileSystemService fileSystemService) {
        this.fileSystemService = fileSystemService;
    }

    @GetMapping("/browse")
    public ApiResponse<DirectoryBrowseResultDto> browse(
            @NonNull HttpSession session, @RequestParam(value = "path", defaultValue = "/") String path) {
        String sessionId = session.getId();
        DirectoryBrowseResultDto result = fileSystemService.listDirectories(sessionId, path);
        return ApiResponse.ok(result);
    }

    @PostMapping("/mkdir")
    public ApiResponse<Void> createDirectory(
            @NonNull HttpSession session,
            @RequestParam(value = "parentPath", required = false) String parentPathParam,
            @RequestParam(value = "name", required = false) String nameParam,
            @RequestParam(value = "dirName", required = false) String dirNameParam,
            @Valid @RequestBody(required = false) CreateDirectoryDto bodyDto) {
        String sessionId = session.getId();
        String parent = bodyDto != null ? bodyDto.parentPath() : parentPathParam;
        String name = bodyDto != null ? bodyDto.dirName() : (nameParam != null ? nameParam : dirNameParam);

        if (parent == null || name == null) {
            throw new IllegalArgumentException("Parent path and directory name are required.");
        }

        fileSystemService.createDirectory(sessionId, parent, name);
        return ApiResponse.ok("Directory created successfully", null);
    }

    @GetMapping({"/disk-usage", "/usage"})
    public ApiResponse<DiskUsageDto> getDiskUsage(@NonNull HttpSession session, @RequestParam("path") String path) {
        String sessionId = session.getId();
        DiskUsageDto usage = fileSystemService.getDiskUsage(sessionId, path);
        return ApiResponse.ok(usage);
    }
}
