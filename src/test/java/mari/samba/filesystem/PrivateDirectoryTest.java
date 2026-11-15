package mari.samba.filesystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import mari.samba.config.SambaProperties;
import mari.samba.infra.CommandExecutor;
import mari.samba.support.ShellCommandFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpSession;

@Epic("Filesystem")
@Feature("Component and local shell: explicit private directory creation (stub OS privileges)")
class PrivateDirectoryTest {
    @TempDir
    Path root;

    @Test
    void explicitPreparationCreatesPrivateDirectoryInsteadOfRootOwnedWritableDirectory() throws Exception {
        var shell = new ShellCommandFixture(root);
        shell.stub("sudo", "[ \"$1\" != -n ] || shift; exec \"$@\"");
        shell.stub("chown", "printf '%s\\n' \"$*\" > '" + root.resolve("ownership") + "'");
        shell.stub("stat", "printf '0:755\\n'");
        shell.stub(
                "getent",
                "case \"$*\" in *passwd*) echo 'alice:x:1001:1001::/:/bin/sh';; *) echo 'private:x:1002:';; esac");
        CommandExecutor commands = mock(CommandExecutor.class);
        when(commands.execute(anyString(), anyString())).thenAnswer(inv -> shell.runChecked(inv.getArgument(1)));
        SambaProperties properties = mock(SambaProperties.class);
        SambaProperties.Security security = mock(SambaProperties.Security.class);
        when(properties.security()).thenReturn(security);
        when(security.allowedRoots()).thenReturn(List.of(root.toString()));
        var controller = new FileSystemApiController(new FileSystemService(commands, properties));
        var dto = new ObjectMapper()
                .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(
                        """
                {"parentPath":"%s","dirName":"private","preparation":
                 {"owner":"alice","group":"private","mode":"0700","confirmed":true}}
                """
                                .formatted(root),
                        CreateDirectoryDto.class);

        controller.createDirectory(new MockHttpSession(), null, null, null, dto);

        assertThat(Files.getPosixFilePermissions(root.resolve("private")))
                .isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.readString(root.resolve("ownership"))).contains("1001:1002");
    }
}
