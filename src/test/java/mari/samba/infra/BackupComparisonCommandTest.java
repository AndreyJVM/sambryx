package mari.samba.infra;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupComparisonCommandTest {
    @TempDir
    Path temporary;

    @Test
    void readsLiteralContentWithoutChangingFilesAndBoundsOutput() throws Exception {
        Path file = Files.writeString(temporary.resolve("config's file"), "include = /missing\n<html>\n");
        var modified = Files.getLastModifiedTime(file);
        var permissions = Files.getPosixFilePermissions(file);
        var process = run(file);
        assertThat(process.getInputStream().readAllBytes()).asString().isEqualTo("include = /missing\n<html>\n");
        assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
        assertThat(Files.getLastModifiedTime(file)).isEqualTo(modified);
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(permissions);
        Files.writeString(file, "x".repeat(400000));
        process = run(file);
        assertThat(process.getInputStream().readAllBytes().length).isLessThanOrEqualTo(262145);
        assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
        assertThat(Files.size(file)).isEqualTo(400000);
    }

    @Test
    void rejectsMissingFilesDirectoriesAndSymlinksWithoutReadingTargets() throws Exception {
        Path target = Files.writeString(temporary.resolve("target"), "private");
        Path link = Files.createSymbolicLink(temporary.resolve("link"), target);
        for (Path path : new Path[] {temporary.resolve("missing"), temporary, link}) {
            var process = run(path);
            assertThat(process.getInputStream().readAllBytes()).isEmpty();
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isNotZero();
        }
        assertThat(Files.readString(target)).isEqualTo("private");
    }

    private Process run(Path path) throws Exception {
        Path bin = Files.createDirectories(temporary.resolve("bin"));
        Path sudo = Files.writeString(
                bin.resolve("sudo"),
                "#!/bin/sh\n[ \"$1\" = '-n' ] && shift\n[ \"$1\" = 'cat' ] || exit 23\nexec \"$@\"\n");
        assertThat(sudo.toFile().setExecutable(true)).isTrue();
        var builder = new ProcessBuilder("bash", "-c", LinuxCommands.readComparisonFile(path.toString()));
        builder.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        return builder.start();
    }
}
