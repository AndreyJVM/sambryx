package mari.samba.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import mari.samba.core.SambaCommandException;

/** Runs generated shell commands with test-local utility stubs and captured output. */
public final class ShellCommandFixture {
    private final Path root;
    private final Path bin;

    public ShellCommandFixture(Path root) throws IOException {
        this.root = root;
        this.bin = Files.createDirectory(root.resolve("bin"));
    }

    public void stub(String name, String body) throws IOException {
        Path executable = Files.writeString(bin.resolve(name), "#!/bin/sh\n" + body + "\n");
        assertThat(executable.toFile().setExecutable(true))
                .as("Utility stub must be executable: %s", name)
                .isTrue();
    }

    public Result run(String command, String input) throws Exception {
        Path output = Files.createTempFile(root, "command-", ".log");
        var builder = new ProcessBuilder("/bin/sh", "-c", command)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile());
        builder.environment().put("PATH", bin + ":/usr/bin:/bin");
        Process process = null;
        try {
            process = builder.start();
            try (var stdin = process.getOutputStream()) {
                stdin.write(input.getBytes(StandardCharsets.UTF_8));
            }
            assertThat(process.waitFor(15, TimeUnit.SECONDS))
                    .as("Command must terminate: %s; output: %s", command, Files.readString(output))
                    .isTrue();
            return new Result(process.exitValue(), Files.readString(output));
        } finally {
            if (process != null) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                assertThat(process.waitFor(5, TimeUnit.SECONDS))
                        .as("Command must terminate after forced cleanup: %s", command)
                        .isTrue();
            }
            Files.deleteIfExists(output);
        }
    }

    public String runChecked(String command) throws Exception {
        return runChecked(command, "");
    }

    public String runChecked(String command, String input) throws Exception {
        Result result = run(command, input);
        if (result.exitCode() != 0) {
            throw new SambaCommandException("Synthetic remote command failed: " + result.output());
        }
        return result.output();
    }

    public record Result(int exitCode, String output) {}
}
