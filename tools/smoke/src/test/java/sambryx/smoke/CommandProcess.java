package sambryx.smoke;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class CommandProcess {
    record Result(int exitCode, String output) {}

    static Result run(List<String> command, Path root, Map<String, String> environment, String input)
            throws IOException, InterruptedException {
        Path output = Files.createTempFile("sambryx-smoke-output-", ".log");
        try {
            var builder = new ProcessBuilder(command)
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile());
            builder.environment().clear();
            builder.environment().putAll(environment);
            Process process = builder.start();
            try {
                try (var stdin = process.getOutputStream()) {
                    stdin.write(input.getBytes(StandardCharsets.UTF_8));
                }
                if (!process.waitFor(240, TimeUnit.SECONDS)) {
                    throw new IOException("Smoke command timed out: " + command.getFirst());
                }
                return new Result(process.exitValue(), Files.readString(output));
            } finally {
                if (process.isAlive()) process.destroyForcibly();
            }
        } finally {
            Files.deleteIfExists(output);
        }
    }
}
