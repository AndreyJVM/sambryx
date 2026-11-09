package sambryx.smoke;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SandboxRunnerTest {
    @TempDir
    Path root;

    @Test
    void generatedPasswordNeverFollowsPlantedSymlink() throws Exception {
        Path victim = root.resolve("victim");
        Files.createSymbolicLink(root.resolve("password"), victim);
        var result = run(true);
        assertNotEquals(0, result.exitCode());
        assertFalse(Files.exists(victim), "Password generation must not write through a symlink");
    }

    @Test
    void incompatibleNodeStopsStartupBeforeSuccessBanner() throws Exception {
        var result = run(false);
        assertNotEquals(0, result.exitCode());
        assertTrue(result.output().contains("incompatible sudo policy"));
        assertFalse(result.output().contains("Docker Sandbox started successfully"));
    }

    @Test
    void compatibleStartupPullsPublishedNodeWithoutBuildingIt() throws Exception {
        var result = run(true);
        assertEquals(0, result.exitCode(), result.output());
        String commands = Files.readString(root.resolve("commands"));
        assertTrue(commands.contains("compose pull samba-node"));
        assertFalse(commands.contains("compose build samba-node"));
        assertTrue(commands.contains("compose exec -T --user admin samba-node"));
        assertTrue(result.output().contains("Password file:"));
        assertFalse(result.output().contains("Password:     admin"));
    }

    private CommandProcess.Result run(boolean compatible) throws Exception {
        Path source = Path.of("../..").toAbsolutePath().normalize();
        Path script = root.resolve("scripts/sandbox/run-sandbox.sh");
        Files.createDirectories(script.getParent());
        Files.copy(source.resolve("scripts/sandbox/run-sandbox.sh"), script);
        Files.writeString(root.resolve("docker-compose.yml"), "services: {}\n");
        Files.createDirectories(root.resolve("target"));
        Files.writeString(root.resolve("target/fixture.jar"), "fixture");
        Files.createDirectories(root.resolve("frontend/node"));
        executable(root.resolve("frontend/node/npm"), "exit 0");
        executable(root.resolve("mvnw"), "exit 0");
        Path bin = Files.createDirectory(root.resolve("bin"));
        executable(bin.resolve("python3"), "exit 0");
        executable(
                bin.resolve("docker"),
                """
                printf '%s\n' "$*" >> "$RUNNER_COMMAND_LOG"
                case "$*" in
                  inspect*) printf '\"healthy\"\n';;
                  *'--user admin'*) test "$RUNNER_NODE_COMPATIBLE" = true;;
                  'compose exec -T samba-node'*) printf 'samba-node synthetic-host-key\n';;
                  *) exit 0;;
                esac
                """);
        var environment = new HashMap<>(System.getenv());
        environment.put("PATH", bin + ":" + environment.get("PATH"));
        environment.put("RUNNER_COMMAND_LOG", root.resolve("commands").toString());
        environment.put("RUNNER_NODE_COMPATIBLE", Boolean.toString(compatible));
        environment.put("SANDBOX_ADMIN_PASSWORD_FILE", root.resolve("password").toString());
        return CommandProcess.run(List.of("bash", script.toString()), root, environment, "2\nstop\n");
    }

    private void executable(Path file, String body) throws Exception {
        Files.writeString(file, "#!/bin/sh\n" + body + "\n");
        assertTrue(file.toFile().setExecutable(true));
    }
}
