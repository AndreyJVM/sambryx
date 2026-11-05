package sambryx.smoke;

import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

final class DockerSmoke {
    @FunctionalInterface
    interface Commands {
        String run(String... arguments) throws Exception;
    }

    static void verify(String image, Path jar, Commands commands) throws Exception {
        if (image == null || image.isBlank() || image.startsWith("-") || !Files.isRegularFile(jar)) {
            throw new IllegalArgumentException("A Docker image and an existing application JAR are required");
        }
        String name = "sambryx-smoke-" + UUID.randomUUID();
        Thread cleanup = new Thread(() -> remove(commands, name), "smoke-container-cleanup");
        Runtime.getRuntime().addShutdownHook(cleanup);
        try {
            commands.run(
                    "run",
                    "--detach",
                    "--name",
                    name,
                    "--read-only",
                    "--tmpfs",
                    "/tmp:rw,noexec,nosuid,size=128m",
                    "--publish",
                    "127.0.0.1::8080",
                    image);
            String uid = commands.run("exec", name, "id", "-u").trim();
            if (!uid.matches("[1-9][0-9]*")) {
                throw new IllegalStateException("Container must run without root privileges");
            }
            String expected;
            try (var input = Files.newInputStream(jar)) {
                expected = HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
            }
            String actual = commands.run("exec", name, "sha256sum", "/app/app.jar")
                    .trim()
                    .split("\\s+")[0];
            if (!actual.equals(expected)) throw new IllegalStateException("Container contains a different JAR");
            URI base = URI.create(
                    "http://" + commands.run("port", name, "8080/tcp").trim());
            try (var client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build()) {
                await().atMost(Duration.ofSeconds(60))
                        .pollInterval(Duration.ofMillis(250))
                        .until(() -> {
                            if (!commands.run("inspect", "--format", "{{.State.Running}}", name)
                                    .trim()
                                    .equals("true")) {
                                throw new IllegalStateException("Smoke container exited before becoming ready");
                            }
                            try {
                                return client.send(
                                                        HttpRequest.newBuilder(base.resolve("/api/auth/environment"))
                                                                .timeout(Duration.ofSeconds(2))
                                                                .build(),
                                                        HttpResponse.BodyHandlers.discarding())
                                                .statusCode()
                                        == 200;
                            } catch (IOException error) {
                                return false;
                            }
                        });
            }
            ArtifactChecks.verifyHttp(base, ArtifactChecks.frontend(jar));
        } finally {
            Runtime.getRuntime().removeShutdownHook(cleanup);
            remove(commands, name);
        }
    }

    static String docker(String... arguments) throws IOException, InterruptedException {
        var command = new ArrayList<>(List.of("docker"));
        command.addAll(List.of(arguments));
        Path output = Files.createTempFile("sambryx-smoke-command-", ".log");
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile())
                    .start();
            try {
                if (!process.waitFor(60, TimeUnit.SECONDS))
                    throw new IOException("Docker command timed out: " + arguments[0]);
                if (process.exitValue() != 0) throw new IOException("Docker command failed: " + arguments[0]);
                return Files.readString(output);
            } finally {
                if (process.isAlive()) process.destroyForcibly();
            }
        } finally {
            Files.deleteIfExists(output);
        }
    }

    private static void remove(Commands commands, String name) {
        try {
            commands.run("rm", "--force", name);
        } catch (Exception error) {
            System.err.println("Smoke container cleanup failed: " + name);
        }
    }
}
