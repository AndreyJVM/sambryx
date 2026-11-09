package mari.samba.filesystem;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import mari.samba.config.SambaProperties;
import mari.samba.infra.CommandExecutor;
import mari.samba.infra.LinuxCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;

@Service
public class FileSystemService {

    private static final Logger log = LoggerFactory.getLogger(FileSystemService.class);
    private static final Pattern SAFE_FILE_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]+$");

    private final CommandExecutor commandExecutor;
    private final List<String> allowedRoots;

    public FileSystemService(CommandExecutor commandExecutor, SambaProperties properties) {
        this.commandExecutor = commandExecutor;
        this.allowedRoots = properties.security().allowedRoots();
    }

    public void copyItem(@NonNull String sessionId, @NonNull String source, @NonNull String destination) {
        String safeSrc = requireOperationPath(source);
        String safeDst = requireOperationPath(destination);
        commandExecutor.execute(sessionId, LinuxCommands.copyRecursive(safeSrc, safeDst));
        log.info("Copied {} -> {}", safeSrc, safeDst);
    }

    public void moveItem(@NonNull String sessionId, @NonNull String source, @NonNull String destination) {
        String safeSrc = requireOperationPath(source);
        String safeDst = requireOperationPath(destination);
        requireNotAllowedRoot(safeSrc);
        commandExecutor.execute(sessionId, LinuxCommands.move(safeSrc, safeDst));
        log.info("Moved {} -> {}", safeSrc, safeDst);
    }

    public void deleteItem(@NonNull String sessionId, @NonNull String target) {
        String safeTarget = requireOperationPath(target);
        requireNotAllowedRoot(safeTarget);
        commandExecutor.execute(sessionId, LinuxCommands.removeRecursive(safeTarget));
        log.info("Deleted {}", safeTarget);
    }

    public void uploadFile(
            @NonNull String sessionId,
            @NonNull String directory,
            @NonNull String filename,
            @NonNull String base64Content) {
        if (!isValidEntryName(filename)) {
            throw new IllegalArgumentException(
                    "Invalid file name. Use alphanumeric characters, dashes, underscores, and dots; '.' and '..' are not allowed.");
        }

        String safeParent = requireOperationPath(directory);
        String targetPath = safeParent.endsWith("/") ? safeParent + filename : safeParent + "/" + filename;
        requireAllowedPath(targetPath);

        String command = LinuxCommands.uploadBase64Stdin(targetPath);
        commandExecutor.execute(sessionId, command, base64Content);
        log.info("Uploaded file {}", targetPath);
    }

    public byte[] downloadFile(@NonNull String sessionId, @NonNull String filePath) {
        String safePath = requireOperationPath(filePath);

        String command = LinuxCommands.base64(safePath);
        String base64Output = commandExecutor.execute(sessionId, command);
        return Base64.getMimeDecoder().decode(base64Output.trim());
    }

    /** Lists subdirectories within a requested path, returning normalized structure. */
    public DirectoryBrowseResultDto listDirectories(@NonNull String sessionId, String requestedPath) {
        String safePath = normalizePath(requestedPath);
        if ("/".equals(safePath)) {
            List<DirectoryItemDto> rootDirs = allowedRoots.stream()
                    .map(root -> new DirectoryItemDto(root.replaceFirst("^/", ""), root, "dir", 0))
                    .toList();
            return new DirectoryBrowseResultDto("/", null, rootDirs);
        }

        requireAllowedPath(safePath);
        String parentPath = getParentPath(safePath);
        String rawOutput = commandExecutor.execute(sessionId, LinuxCommands.findDirectories(safePath));

        List<DirectoryItemDto> subDirs = Arrays.stream(rawOutput.split("\\r?\\n"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .map(this::parseDirectoryLine)
                .filter(item -> item != null)
                .toList();

        return new DirectoryBrowseResultDto(safePath, parentPath, subDirs);
    }

    /** Creates a directory under the given parent directory with secure permissions. */
    public void createDirectory(@NonNull String sessionId, @NonNull String parentPath, @NonNull String name) {
        if (!isValidEntryName(name)) {
            throw new IllegalArgumentException(
                    "Invalid directory name. Use alphanumeric characters, dashes, underscores, and dots; '.' and '..' are not allowed.");
        }

        String safeParent = requireOperationPath(parentPath);

        String newDirPath = safeParent.endsWith("/") ? safeParent + name : safeParent + "/" + name;
        requireAllowedPath(newDirPath);

        commandExecutor.execute(sessionId, LinuxCommands.mkdir(newDirPath));
        commandExecutor.execute(sessionId, LinuxCommands.chmod("0775", newDirPath));
        log.info("Created directory {} with permissions 0775", newDirPath);
    }

    /** Retrieves disk usage information for a specified path. */
    public DiskUsageDto getDiskUsage(@NonNull String sessionId, String requestedPath) {
        String safePath = normalizePath(requestedPath);
        if (!"/".equals(safePath)) {
            requireAllowedPath(safePath);
        }

        String rawOutput = commandExecutor.execute(sessionId, LinuxCommands.df(safePath));
        return DiskUsageParser.parse(safePath, rawOutput);
    }

    // ==========================================
    // Path validation
    // ==========================================

    /**
     * Validates that the given path falls within one of the configured allowed root directories.
     * Public because it is also used by {@code SambaShareService} for share path validation.
     */
    public void requireAllowedPath(String path) {
        if (path == null || path.isBlank() || !path.startsWith("/") || "/".equals(path)) {
            throw new SecurityException("An absolute non-root path is required.");
        }
        boolean allowed = allowedRoots.stream().anyMatch(root -> path.equals(root) || path.startsWith(root + "/"));
        if (!allowed) {
            throw new SecurityException("Access to path '" + path + "' is not permitted.");
        }
    }

    // ==========================================
    // Private helpers
    // ==========================================

    private static boolean isValidEntryName(String name) {
        return name != null
                && !name.equals(".")
                && !name.equals("..")
                && SAFE_FILE_NAME_PATTERN.matcher(name).matches();
    }

    private String requireOperationPath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Path is required for file operations.");
        }
        String normalized = normalizePath(path);
        requireAllowedPath(normalized);
        return normalized;
    }

    /**
     * Normalizes a path by resolving ".." segments and removing trailing slashes.
     * This is lexical normalization only; it does not resolve remote symbolic links.
     */
    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String cleaned = path.trim().replace('\\', '/');
        String normalized = Path.of(cleaned).normalize().toString();
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * Prevents deletion or relocation of top-level allowed root directories themselves.
     */
    private void requireNotAllowedRoot(String path) {
        if (allowedRoots.contains(path)) {
            throw new SecurityException("Deletion or relocation of root directory '" + path + "' is not permitted.");
        }
    }

    private DirectoryItemDto parseDirectoryLine(String line) {
        String[] parts = line.split("\\|", 3);
        if (parts.length < 3) {
            return null;
        }
        String typeCode = parts[0];
        long size = 0;
        try {
            size = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            log.debug("Failed to parse size from directory listing: '{}'", parts[1]);
        }
        String fullPath = parts[2];
        String type = "d".equals(typeCode) ? "dir" : ("f".equals(typeCode) ? "file" : "link");
        String name = fullPath.substring(fullPath.lastIndexOf('/') + 1);
        return new DirectoryItemDto(name, fullPath, type, size);
    }

    private String getParentPath(String path) {
        if ("/".equals(path)) {
            return "/";
        }
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash <= 0) {
            return "/";
        }
        return path.substring(0, lastSlash);
    }
}
