package mari.samba.filesystem;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import mari.samba.config.SambaProperties;
import mari.samba.core.SambaCommandException;
import mari.samba.infra.CommandExecutor;
import mari.samba.infra.LinuxCommands;
import mari.samba.support.ShellCommandFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Epic("Filesystem")
@Feature("Local shell safety: real temporary entries with stub identity, trust metadata and privileges")
class DirectoryCreationSafetyTest {
    @TempDir
    Path root;

    ShellCommandFixture shell;
    FileSystemService service;
    CommandExecutor commands;

    @BeforeEach
    void setUp() throws Exception {
        shell = new ShellCommandFixture(root);
        shell.stub("sudo", "[ \"$1\" != -n ] || shift; exec \"$@\"");
        shell.stub("stat", "printf '0:755\\n'");
        shell.stub(
                "getent",
                "case \"$*\" in *passwd*) echo 'alice:x:1001:1001::/:/bin/sh';; *) echo 'private:x:1002:';; esac");
        shell.stub("chown", "touch '" + root.resolve("chown-called") + "'");
        commands = mock(CommandExecutor.class);
        when(commands.execute(anyString(), anyString())).thenAnswer(inv -> shell.runChecked(inv.getArgument(1)));
        var properties = mock(SambaProperties.class);
        var security = mock(SambaProperties.Security.class);
        when(properties.security()).thenReturn(security);
        when(security.allowedRoots()).thenReturn(List.of(root.toString()));
        service = new FileSystemService(commands, properties);
    }

    @Test
    void ordinaryCreationCannotChangeExistingPrivateDirectory() throws Exception {
        Path existing = Files.createDirectory(root.resolve("private"));
        Files.setPosixFilePermissions(existing, PosixFilePermissions.fromString("rwx------"));
        assertThatThrownBy(() -> service.createDirectory("session", root.toString(), "private"))
                .isInstanceOf(SambaCommandException.class);
        assertThat(Files.getPosixFilePermissions(existing)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"directory", "file", "symlink", "dangling"})
    void existingEntriesNeverReachOwnershipChange(String kind) throws Exception {
        Path target = Files.createDirectory(root.resolve("target"));
        Files.writeString(target.resolve("content"), "untouched");
        Path entry = root.resolve("private");
        switch (kind) {
            case "directory" -> Files.createDirectory(entry);
            case "file" -> Files.writeString(entry, "existing");
            case "symlink" -> Files.createSymbolicLink(entry, target);
            default -> Files.createSymbolicLink(entry, root.resolve("absent"));
        }
        assertThatThrownBy(this::createPrivate).isInstanceOf(SambaCommandException.class);
        assertThat(root.resolve("chown-called")).doesNotExist();
        assertThat(Files.readString(target.resolve("content"))).isEqualTo("untouched");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1001:755", "0:775", "0:1777", "malformed"})
    void untrustedMetadataRejectsBeforeCreation(String metadata) throws Exception {
        shell.stub("stat", "printf '%s\\n' '" + metadata + "'");
        assertThatThrownBy(this::createPrivate).isInstanceOf(SambaCommandException.class);
        assertThat(root.resolve("private")).doesNotExist();
        assertThat(root.resolve("chown-called")).doesNotExist();
    }

    @Test
    void physicalParentOutsideAllowedRootsIsRejected() throws Exception {
        Path link = root.resolve("outside");
        Files.createSymbolicLink(link, root.getParent());
        assertThatThrownBy(() -> service.createPrivateDirectory("session", link.toString(), "private", preparation()))
                .isInstanceOf(SambaCommandException.class);
        assertThat(root.resolve("chown-called")).doesNotExist();
    }

    @Test
    void internalParentSymlinkUsesAllowedPhysicalParent() throws Exception {
        Path parent = Files.createDirectory(root.resolve("parent"));
        Path link = Files.createSymbolicLink(root.resolve("inside"), parent);
        service.createPrivateDirectory("session", link.toString(), "private", preparation());
        assertThat(parent.resolve("private")).isDirectory();
        assertThat(Files.getPosixFilePermissions(parent.resolve("private")))
                .isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "root:x:0:0::/:/bin/sh", "alice:x:0:1001::/:/bin/sh", "alice:x:bad:1001::/:/bin/sh"})
    void missingRootOrMalformedLocalIdentityPreventsCreation(String record) throws Exception {
        shell.stub("getent", "echo '" + record + "'");
        assertThatThrownBy(this::createPrivate).isInstanceOf(SambaCommandException.class);
        assertThat(root.resolve("private")).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"chmod", "chown"})
    void failedPreparationReportsPossiblePartialStateAndDoesNotDeleteDirectory(String command) throws Exception {
        shell.stub(command, "exit 1");
        assertThatThrownBy(this::createPrivate)
                .isInstanceOf(SambaCommandException.class)
                .hasMessageContaining("may remain");
        assertThat(root.resolve("private")).isDirectory();
        assertThat(Files.getPosixFilePermissions(root.resolve("private")))
                .isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-option", "../escape", "a/b", ".", "..", "$(id)"})
    void unsafeLeafNeverExecutesCommands(String leaf) {
        clearInvocations(commands);
        assertThatThrownBy(() -> service.createPrivateDirectory("session", root.toString(), leaf, preparation()))
                .isInstanceOf(RuntimeException.class);
        verifyNoInteractions(commands);
    }

    @Test
    void unsafeIdentityAndUnconfirmedRequestsNeverExecuteCommands() {
        clearInvocations(commands);
        for (var request : List.of(
                new CreateDirectoryDto.PrivatePreparation("alice", "private", "0700", false),
                new CreateDirectoryDto.PrivatePreparation("alice", "private", "0777", true),
                new CreateDirectoryDto.PrivatePreparation("@team", "private", "0700", true),
                new CreateDirectoryDto.PrivatePreparation("alice", "domain\\group", "0700", true))) {
            assertThatThrownBy(() -> service.createPrivateDirectory("session", root.toString(), "private", request))
                    .isInstanceOf(RuntimeException.class);
        }
        verifyNoInteractions(commands);
    }

    @Test
    void inheritedSetgidDoesNotSurviveExplicitPrivateMode() throws Exception {
        Path parent = Files.createDirectory(root.resolve("setgid-parent"));
        Files.setAttribute(parent, "unix:mode", 02755);
        service.createPrivateDirectory("session", parent.toString(), "private", preparation());
        assertThat((int) Files.getAttribute(parent.resolve("private"), "unix:mode") & 07777)
                .isEqualTo(0700);
    }

    @Test
    void competingCreationsPrepareExactlyOneNewDirectory() throws Exception {
        shell.stub("chown", "printf 'prepared\\n' >> '" + root.resolve("prepared") + "'");
        String command = LinuxCommands.createPrivateDirectory(
                root.toString(), "private", "alice", "private", List.of(root.toString()));
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<ShellCommandFixture.Result> action = () -> {
                ready.countDown();
                assertThat(start.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        .isTrue();
                return shell.run(command, "");
            };
            var first = executor.submit(action);
            var second = executor.submit(action);
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get().exitCode(), second.get().exitCode())).containsExactlyInAnyOrder(0, 1);
        }
        assertThat(Files.readAllLines(root.resolve("prepared"))).hasSize(1);
    }

    @Test
    void racedMkdirSuccessWithoutCreationReceiptCannotPrepareExistingDirectory() throws Exception {
        shell.stub("mkdir", "for leaf do :; done; /bin/mkdir -m 0750 -- \"$leaf\"; exit 0");
        assertThatThrownBy(this::createPrivate).isInstanceOf(SambaCommandException.class);
        assertThat(root.resolve("chown-called")).doesNotExist();
        assertThat(Files.getPosixFilePermissions(root.resolve("private")))
                .isEqualTo(PosixFilePermissions.fromString("rwxr-x---"));
    }

    @Test
    void plantedLeafBeforeMkdirCannotChangeSymlinkTarget() throws Exception {
        Path target = Files.createDirectory(root.resolve("protected-target"));
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-x---"));
        shell.stub("mkdir", "for leaf do :; done; /bin/ln -s '" + target + "' \"$leaf\"; exec /bin/mkdir \"$@\"");
        assertThatThrownBy(this::createPrivate).isInstanceOf(SambaCommandException.class);
        assertThat(root.resolve("chown-called")).doesNotExist();
        assertThat(Files.getPosixFilePermissions(target)).isEqualTo(PosixFilePermissions.fromString("rwxr-x---"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "private:x:0:", "private:x:invalid:"})
    void missingRootOrMalformedLocalGroupPreventsCreation(String groupRecord) throws Exception {
        shell.stub(
                "getent",
                "case \"$*\" in *passwd*) echo 'alice:x:1001:1001::/:/bin/sh';; *) echo '" + groupRecord + "';; esac");
        assertThatThrownBy(this::createPrivate).isInstanceOf(SambaCommandException.class);
        assertThat(root.resolve("private")).doesNotExist();
    }

    private void createPrivate() {
        service.createPrivateDirectory("session", root.toString(), "private", preparation());
    }

    private CreateDirectoryDto.PrivatePreparation preparation() {
        return new CreateDirectoryDto.PrivatePreparation("alice", "private", "0700", true);
    }
}
