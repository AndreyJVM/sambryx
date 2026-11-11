package mari.samba.filesystem;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;
import mari.samba.config.SambaProperties;
import mari.samba.infra.CommandExecutor;
import mari.samba.infra.LinuxCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@Epic("Filesystem")
@Feature("Unit: filesystem validation and command results (mock commands)")
class FileSystemServiceTest {

    @Mock
    private CommandExecutor commandExecutor;

    @Mock
    private SambaProperties properties;

    @Mock
    private SambaProperties.Security mockSecurity;

    private FileSystemService fileSystemService;

    private static final String SESSION_ID = "test-session";

    @BeforeEach
    void setUp() {
        when(properties.security()).thenReturn(mockSecurity);
        when(mockSecurity.allowedRoots()).thenReturn(List.of("/srv", "/data", "/mnt"));

        fileSystemService = new FileSystemService(commandExecutor, properties);
    }

    // ==========================================
    // Tests for listDirectories
    // ==========================================

    @DisplayName("Directory listing parses mock command output and returns parent and child paths")
    @Test
    void testListDirectories_ShouldParseGivenOutput() throws Exception {
        String path = "/srv/samba";
        String mockOutput = "d|4096|/srv/samba/public\nd|4096|/srv/samba/private";

        when(commandExecutor.execute(eq(SESSION_ID), anyString())).thenReturn(mockOutput);

        DirectoryBrowseResultDto result = fileSystemService.listDirectories(SESSION_ID, path);

        assertNotNull(result);
        assertEquals("/srv/samba", result.currentPath());
        assertEquals("/srv", result.parentPath());
        assertEquals(2, result.directories().size());
        assertEquals("public", result.directories().get(0).name());
        assertEquals("/srv/samba/public", result.directories().get(0).fullPath());
    }

    @DisplayName("Virtual root browsing returns allowed roots without commands")
    @Test
    void testListDirectories_RootPath_ShouldReturnAllowedRoots() {
        DirectoryBrowseResultDto result = fileSystemService.listDirectories(SESSION_ID, "/");

        assertNotNull(result);
        assertEquals("/", result.currentPath());
        assertEquals(3, result.directories().size());
        assertTrue(result.directories().stream().anyMatch(i -> i.fullPath().equals("/srv")));
        assertTrue(result.directories().stream().anyMatch(i -> i.fullPath().equals("/data")));
        verifyNoInteractions(commandExecutor);
    }

    @DisplayName("Directory browsing outside allowed roots throws a security exception")
    @Test
    void testListDirectories_UnauthorizedPath_ShouldThrowSecurityException() {
        assertThrows(SecurityException.class, () -> fileSystemService.listDirectories(SESSION_ID, "/etc/shadow"));
    }

    // ==========================================
    // Tests for createDirectory
    // ==========================================

    @DisplayName("Valid directory creation issues mkdir and chmod commands")
    @Test
    void testCreateDirectory_ValidName_ShouldExecuteMkdirAndChmod() throws Exception {
        fileSystemService.createDirectory(SESSION_ID, "/srv/samba", "new_folder");

        verify(commandExecutor).execute(eq(SESSION_ID), eq(LinuxCommands.mkdir("/srv/samba/new_folder")));
        verify(commandExecutor).execute(eq(SESSION_ID), eq(LinuxCommands.chmod("0775", "/srv/samba/new_folder")));
    }

    @DisplayName("Unsafe directory name throws an argument exception")
    @Test
    void testCreateDirectory_InvalidName_ShouldThrowIllegalArgumentException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> fileSystemService.createDirectory(SESSION_ID, "/srv/samba", "bad;folder*name"));
    }

    @DisplayName("Invalid entry names prevent directory creation and upload commands")
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidEntryNames")
    void invalidEntryNamesAreRejectedBeforeAnyCommand(String name) {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> fileSystemService.createDirectory(SESSION_ID, "/srv", name)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> fileSystemService.uploadFile(SESSION_ID, "/srv", name, "ZmlsZQ==")));
        verifyNoInteractions(commandExecutor);
    }

    @DisplayName("Regular entry names remain valid for directory creation and upload commands")
    @ParameterizedTest(name = "{argumentsWithNames}")
    @ValueSource(strings = {".hidden", "archive.tar.gz", "team_2026-10"})
    void regularEntryNamesRemainAllowed(String name) {
        String path = "/srv/" + name;
        fileSystemService.createDirectory(SESSION_ID, "/srv", name);
        fileSystemService.uploadFile(SESSION_ID, "/srv", name, "ZmlsZQ==");

        verify(commandExecutor).execute(SESSION_ID, LinuxCommands.mkdir(path));
        verify(commandExecutor).execute(SESSION_ID, LinuxCommands.chmod("0775", path));
        verify(commandExecutor).execute(SESSION_ID, LinuxCommands.uploadBase64Stdin(path), "ZmlsZQ==");
        verifyNoMoreInteractions(commandExecutor);
    }

    @DisplayName("Directory creation outside allowed parents throws a security exception")
    @Test
    void testCreateDirectory_UnauthorizedParent_ShouldThrowSecurityException() {
        assertThrows(
                SecurityException.class, () -> fileSystemService.createDirectory(SESSION_ID, "/root", "my_folder"));
    }

    // ==========================================
    // Tests for getDiskUsage
    // ==========================================

    @DisplayName("Disk usage parses mock df output into percentage, mount point and formatted size")
    @Test
    void testGetDiskUsage_ValidDfOutput_ShouldParseCorrectly() throws Exception {
        String path = "/srv/samba";
        String mockDfOutput = "Filesystem     1024-blocks      Used Available Capacity Mounted on\n"
                + "/dev/sda1        104857600  41943040  62914560      40% /srv";

        when(commandExecutor.execute(eq(SESSION_ID), anyString())).thenReturn(mockDfOutput);

        DiskUsageDto result = fileSystemService.getDiskUsage(SESSION_ID, path);

        assertNotNull(result);
        assertEquals(path, result.path());
        assertEquals(40, result.usePercent());
        assertEquals("/srv", result.mountPoint());
        assertTrue(result.total().contains("GB"));
    }

    @DisplayName("Disk usage normalizes the path and parses only the first data row")
    @ParameterizedTest(name = "{0}")
    @MethodSource("diskUsageRows")
    void diskUsagePreservesSizesAndMountPoint(String row) {
        when(commandExecutor.execute(SESSION_ID, LinuxCommands.df("/srv")))
                .thenReturn("Filesystem 1024-blocks Used Available Capacity Mounted on\r\n/dev/test " + row
                        + "\r\n/dev/ignored 9 9 0 100% /ignored");

        DiskUsageDto result = fileSystemService.getDiskUsage(SESSION_ID, "/srv/child/..");

        DiskUsageDto expected = row.startsWith("0 ")
                ? new DiskUsageDto("/srv", "0 B", "1.0 KB", "1.0 MB", 0, "/srv")
                : new DiskUsageDto("/srv", "1.0 MB", "0 B", "1.0 KB", 100, "/data");
        assertEquals(expected, result);
        verify(commandExecutor).execute(SESSION_ID, LinuxCommands.df("/srv"));
        verifyNoMoreInteractions(commandExecutor);
    }

    @DisplayName("Malformed disk usage output preserves the expected failure and cause")
    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedDiskUsageOutputs")
    void diskUsagePreservesMalformedOutputFailures(String output) {
        when(commandExecutor.execute(SESSION_ID, LinuxCommands.df("/srv"))).thenReturn(output);

        RuntimeException failure =
                assertThrows(RuntimeException.class, () -> fileSystemService.getDiskUsage(SESSION_ID, "/srv"));

        String expected = output == null || output.isBlank()
                ? "Empty output from df command for path: /srv"
                : !output.contains("\n")
                        ? "Invalid df output format: " + output
                        : output.endsWith("short row")
                                ? "Unexpected df line structure: short row"
                                : "Failed to parse disk usage numbers from: /dev/test bad 1 2 3% /srv";
        assertEquals(RuntimeException.class, failure.getClass());
        assertEquals(expected, failure.getMessage());
        if (output != null && output.contains("bad")) {
            assertInstanceOf(NumberFormatException.class, failure.getCause());
        } else {
            assertNull(failure.getCause());
        }
        verifyNoMoreInteractions(commandExecutor);
    }

    @DisplayName("Disk usage outside allowed roots throws a security exception")
    @Test
    void testGetDiskUsage_UnauthorizedPath_ShouldThrowSecurityException() {
        assertThrows(SecurityException.class, () -> fileSystemService.getDiskUsage(SESSION_ID, "/etc"));
    }

    // ==========================================
    // Tests for downloadFile
    // ==========================================

    @DisplayName("File download decodes mock base64 output into original bytes")
    @Test
    void testDownloadFile_ValidPath_ShouldReturnDecodedBytes() throws Exception {
        String path = "/srv/samba/test.txt";
        String content = "Hello Samba Download!";
        String base64Output = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));

        when(commandExecutor.execute(eq(SESSION_ID), eq(LinuxCommands.base64(path))))
                .thenReturn(base64Output);

        byte[] result = fileSystemService.downloadFile(SESSION_ID, path);

        assertNotNull(result);
        assertArrayEquals(content.getBytes(StandardCharsets.UTF_8), result);
    }

    @DisplayName("File download outside allowed roots throws a security exception")
    @Test
    void testDownloadFile_UnauthorizedPath_ShouldThrowSecurityException() {
        assertThrows(SecurityException.class, () -> fileSystemService.downloadFile(SESSION_ID, "/etc/shadow"));
    }

    @DisplayName("Invalid paths reject file operations before commands")
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidOperationPaths")
    void fileOperationsRejectInvalidPathsBeforeExecutingCommands(String path) {
        Class<? extends RuntimeException> expected =
                path == null || path.isBlank() ? IllegalArgumentException.class : SecurityException.class;

        assertAll(
                () -> assertThrows(expected, () -> fileSystemService.deleteItem(SESSION_ID, path)),
                () -> assertThrows(expected, () -> fileSystemService.copyItem(SESSION_ID, path, "/data/file")),
                () -> assertThrows(expected, () -> fileSystemService.copyItem(SESSION_ID, "/srv/file", path)),
                () -> assertThrows(expected, () -> fileSystemService.moveItem(SESSION_ID, path, "/data/file")),
                () -> assertThrows(expected, () -> fileSystemService.moveItem(SESSION_ID, "/srv/file", path)),
                () -> assertThrows(expected, () -> fileSystemService.downloadFile(SESSION_ID, path)),
                () -> assertThrows(expected, () -> fileSystemService.uploadFile(SESSION_ID, path, "file.txt", "")),
                () -> assertThrows(expected, () -> fileSystemService.createDirectory(SESSION_ID, path, "folder")));
        verifyNoInteractions(commandExecutor);
    }

    @DisplayName("Allowed root directories cannot be deleted or moved")
    @ParameterizedTest(name = "{argumentsWithNames}")
    @ValueSource(strings = {"/srv", "/srv/", "/srv/child/..", "/data", "/mnt"})
    void allowedRootsCannotBeDeletedOrMoved(String path) {
        assertThrows(SecurityException.class, () -> fileSystemService.deleteItem(SESSION_ID, path));
        assertThrows(SecurityException.class, () -> fileSystemService.moveItem(SESSION_ID, path, "/data/archive"));
        verifyNoInteractions(commandExecutor);
    }

    @DisplayName("File operations normalize paths and accept allowed roots as destinations")
    @Test
    void validOperationsNormalizePathsAndAllowRootAsDestinationDirectory() {
        fileSystemService.copyItem(SESSION_ID, "/srv/folder/../file.txt", "/data/");
        fileSystemService.moveItem(SESSION_ID, "/srv/folder/../file.txt", "/data/");
        fileSystemService.deleteItem(SESSION_ID, "/srv/folder/../file.txt");
        fileSystemService.uploadFile(SESSION_ID, "/srv/", "file.txt", "ZmlsZQ==");

        verify(commandExecutor).execute(SESSION_ID, LinuxCommands.copyRecursive("/srv/file.txt", "/data"));
        verify(commandExecutor).execute(SESSION_ID, LinuxCommands.move("/srv/file.txt", "/data"));
        verify(commandExecutor).execute(SESSION_ID, LinuxCommands.removeRecursive("/srv/file.txt"));
        verify(commandExecutor).execute(SESSION_ID, LinuxCommands.uploadBase64Stdin("/srv/file.txt"), "ZmlsZQ==");
        verifyNoMoreInteractions(commandExecutor);
    }

    @DisplayName("Filesystem root disk usage remains available through the mock command")
    @Test
    void rootDiskUsageRemainsAvailable() {
        when(commandExecutor.execute(SESSION_ID, LinuxCommands.df("/")))
                .thenReturn("Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/test 100 40 60 40% /");

        DiskUsageDto result = fileSystemService.getDiskUsage(SESSION_ID, "/");

        assertEquals("/", result.path());
        assertEquals(40, result.usePercent());
        verify(commandExecutor).execute(SESSION_ID, LinuxCommands.df("/"));
        verifyNoMoreInteractions(commandExecutor);
    }

    @DisplayName("General directory guard rejects null and virtual root without commands")
    @Test
    void generalPathGuardDoesNotTreatVirtualRootAsAnAllowedDirectory() {
        assertThrows(SecurityException.class, () -> fileSystemService.requireAllowedPath(null));
        assertThrows(SecurityException.class, () -> fileSystemService.requireAllowedPath("/"));
        verifyNoInteractions(commandExecutor);
    }

    private static Stream<Named<String>> invalidEntryNames() {
        return Stream.of(
                Named.of("null name", null),
                Named.of("empty name", ""),
                Named.of("blank name", " "),
                Named.of("current directory", "."),
                Named.of("parent directory", ".."),
                Named.of("forward slash", "nested/folder"),
                Named.of("backslash", "nested\\folder"),
                Named.of("shell separator", "bad;name"),
                Named.of("newline", "bad\nname"));
    }

    private static Stream<Named<String>> malformedDiskUsageOutputs() {
        return Stream.of(
                Named.of("null output", null),
                Named.of("empty output", ""),
                Named.of("blank output", "  "),
                Named.of("missing data row", "header"),
                Named.of("short data row", "header\nshort row"),
                Named.of("invalid numeric field", "header\n/dev/test bad 1 2 3% /srv"));
    }

    private static Stream<Named<String>> diskUsageRows() {
        return Stream.of(
                Named.of("zero total and zero percent", "0 1 1024 0% /srv"),
                Named.of("zero used and full percentage", "1024 0 1 100% /data"));
    }

    private static Stream<Named<String>> invalidOperationPaths() {
        return Stream.of(
                Named.of("null path", null),
                Named.of("empty path", ""),
                Named.of("blank path", "  "),
                Named.of("virtual root", "/"),
                Named.of("repeated root separator", "//"),
                Named.of("root dot segment", "/./"),
                Named.of("parent resolves to root", "/srv/.."),
                Named.of("relative path", "relative"),
                Named.of("outside allowed roots", "/etc"),
                Named.of("allowed root prefix collision", "/srv-other/file"));
    }
}
