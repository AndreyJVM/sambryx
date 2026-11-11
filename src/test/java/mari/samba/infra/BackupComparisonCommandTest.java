package mari.samba.infra;

import static org.assertj.core.api.Assertions.*;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import mari.samba.support.ShellCommandFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Epic("Configuration and backups")
@Feature("Local shell component: bounded literal file reads (stub sudo)")
class BackupComparisonCommandTest {
    @TempDir
    Path temporary;

    private ShellCommandFixture shell;

    @BeforeEach
    void setUp() throws Exception {
        shell = new ShellCommandFixture(temporary);
        shell.stub("sudo", "[ \"$1\" = '-n' ] && shift\n[ \"$1\" = 'cat' ] || exit 23\nexec \"$@\"");
    }

    @DisplayName("Reads literal content without changing files and bounds output")
    @Test
    void readsLiteralContentWithoutChangingFilesAndBoundsOutput() throws Exception {
        Path file = Files.writeString(temporary.resolve("config's file"), "include = /missing\n<html>\n");
        var modified = Files.getLastModifiedTime(file);
        var permissions = Files.getPosixFilePermissions(file);
        var result = read(file);
        assertThat(result.output()).isEqualTo("include = /missing\n<html>\n");
        assertThat(result.exitCode()).isZero();
        assertThat(Files.getLastModifiedTime(file)).isEqualTo(modified);
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(permissions);
        Files.writeString(file, "x".repeat(400000));
        result = read(file);
        assertThat(result.output().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(262145);
        assertThat(Files.size(file)).isEqualTo(400000);
    }

    @DisplayName("Rejects missing files directories and symlinks without reading targets")
    @Test
    void rejectsMissingFilesDirectoriesAndSymlinksWithoutReadingTargets() throws Exception {
        Path target = Files.writeString(temporary.resolve("target"), "private");
        Path link = Files.createSymbolicLink(temporary.resolve("link"), target);
        for (Path path : new Path[] {temporary.resolve("missing"), temporary, link}) {
            var result = read(path);
            assertThat(result.output()).isEmpty();
            assertThat(result.exitCode()).isNotZero();
        }
        assertThat(Files.readString(target)).isEqualTo("private");
    }

    private ShellCommandFixture.Result read(Path path) throws Exception {
        return shell.run(LinuxCommands.readComparisonFile(path.toString()), "");
    }
}
