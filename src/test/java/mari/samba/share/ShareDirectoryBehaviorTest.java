package mari.samba.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mari.samba.core.SambaCommandException;
import mari.samba.filesystem.FileSystemService;
import mari.samba.infra.CommandExecutor;
import mari.samba.infra.LinuxCommands;
import mari.samba.smbconfig.SambaConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ShareDirectoryBehaviorTest {
    private static final String SESSION = "directory-fixture";
    private static final String PATH = "/srv/samba/team";

    @Mock
    private SambaConfigService config;

    @Mock
    private CommandExecutor commands;

    @Mock
    private FileSystemService filesystem;

    private SambaShareService service;

    @BeforeEach
    void setUp() {
        service = new SambaShareService(config, commands, filesystem);
    }

    @ParameterizedTest
    @CsvSource({"alice,smb_team,bob", ",smb_team,@other", ",,@smb_team", ",,bob", ",,"})
    void editingSambaOptionsDoesNotChangeFilesystemPermissions(
            String forcedUser, String forcedGroup, String validUsers) {
        var request = SambaShareCreateDto.builder()
                .name("different")
                .path(" " + PATH + " ")
                .comment("Updated comment")
                .directoryMask("0770")
                .forceUser(forcedUser)
                .forceGroup(forcedGroup)
                .validUsers(validUsers)
                .build();
        prepareConfigurationUpdate();

        service.updateShare(SESSION, "team", request);

        var order = inOrder(filesystem, commands, config);
        order.verify(filesystem).requireAllowedPath(PATH);
        order.verify(commands).execute(SESSION, LinuxCommands.mkdir(PATH));
        order.verify(config).getSmbConfContent(SESSION);
        order.verify(config).removeShareSection("current", "team");
        order.verify(config).buildShareSection(request.withName("team"));
        order.verify(config).updateSmbConf(SESSION, "remaining\n[team]");
        verifyNoMoreInteractions(commands);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void preparationFailureStopsConfigurationChanges(boolean deniedPath, boolean createShare) {
        var failure = new SambaCommandException("Synthetic directory failure");
        var request = SambaShareCreateDto.builder().name("team").path(PATH).build();
        if (deniedPath) doThrow(failure).when(filesystem).requireAllowedPath(PATH);
        else when(commands.execute(SESSION, LinuxCommands.mkdir(PATH))).thenThrow(failure);

        assertThatThrownBy(() -> {
                    if (createShare) service.createShare(SESSION, request);
                    else service.updateShare(SESSION, "team", request);
                })
                .isSameAs(failure);

        if (deniedPath) verifyNoInteractions(commands);
        else {
            verify(commands).execute(SESSION, LinuxCommands.mkdir(PATH));
            verifyNoMoreInteractions(commands);
        }
        if (createShare) {
            verify(config).getSmbConfContent(SESSION);
            verify(config).parseShares(null);
            verifyNoMoreInteractions(config);
        } else verifyNoInteractions(config);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void savingExistingTreePreservesContentsModesAndOwners(boolean createShare, @TempDir Path temporary)
            throws Exception {
        var target = Files.createDirectory(temporary.resolve("other-existing"));
        var child = Files.createDirectory(target.resolve("nested"));
        var file = Files.writeString(child.resolve("document"), "Original content");
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwx------"));
        var before = new HashMap<Path, Map<String, Object>>();
        for (var path : List.of(target, child, file)) {
            before.put(path, Files.readAttributes(path, "unix:mode,uid,gid"));
        }
        var request = SambaShareCreateDto.builder()
                .name("team")
                .path(target.toString())
                .comment("Updated comment")
                .directoryMask("0777")
                .forceUser("alice")
                .forceGroup("smb_team")
                .validUsers("bob")
                .build();
        when(commands.execute(eq(SESSION), anyString())).thenAnswer(call -> {
            String command = call.getArgument(1);
            assertThat(command).isEqualTo(LinuxCommands.mkdir(target.toString()));
            var process = new ProcessBuilder("/bin/sh", "-c", command.substring("sudo ".length()))
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.waitFor()).as(output).isZero();
            return output;
        });
        when(config.getSmbConfContent(SESSION)).thenReturn("current");
        when(config.buildShareSection(any())).thenReturn("[team]");
        if (createShare) service.createShare(SESSION, request);
        else {
            when(config.removeShareSection("current", "team")).thenReturn("remaining");
            service.updateShare(SESSION, "team", request);
        }

        assertThat(Files.readString(file)).isEqualTo("Original content");
        for (var path : before.keySet()) {
            assertThat(Files.readAttributes(path, "unix:mode,uid,gid")).isEqualTo(before.get(path));
        }
        verify(commands).execute(SESSION, LinuxCommands.mkdir(target.toString()));
        verifyNoMoreInteractions(commands);
    }

    @Test
    void selectingMissingPathCreatesDirectoryWithoutPermissionCommands(@TempDir Path temporary) throws Exception {
        var target = temporary.resolve("new-parent/new-share");
        var request = SambaShareCreateDto.builder()
                .name("team")
                .path(target.toString())
                .build();
        prepareConfigurationUpdate();
        when(commands.execute(SESSION, LinuxCommands.mkdir(target.toString()))).thenAnswer(call -> {
            var process = new ProcessBuilder("mkdir", "-p", "--", target.toString()).start();
            assertThat(process.waitFor()).isZero();
            return "";
        });

        service.updateShare(SESSION, "team", request);

        assertThat(target).isDirectory();
        verify(commands).execute(SESSION, LinuxCommands.mkdir(target.toString()));
        verifyNoMoreInteractions(commands);
    }

    private void prepareConfigurationUpdate() {
        when(config.getSmbConfContent(SESSION)).thenReturn("current");
        when(config.removeShareSection("current", "team")).thenReturn("remaining");
        when(config.buildShareSection(any())).thenReturn("[team]");
    }
}
