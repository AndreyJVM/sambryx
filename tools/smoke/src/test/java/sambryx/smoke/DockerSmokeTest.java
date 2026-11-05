package sambryx.smoke;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DockerSmokeTest {
    @TempDir
    Path directory;

    @Test
    void missingJarDoesNotCreateContainer() {
        var calls = new ArrayList<List<String>>();
        assertThrows(
                IllegalArgumentException.class,
                () -> DockerSmoke.verify("fixture:image", directory.resolve("missing.jar"), arguments -> {
                    calls.add(List.of(arguments));
                    return "";
                }));
        assertTrue(calls.isEmpty());
    }

    @Test
    void rootContainerIsRejectedAndRemoved() throws Exception {
        var calls = new ArrayList<List<String>>();
        var jar = java.nio.file.Files.writeString(directory.resolve("fixture.jar"), "fixture");
        assertThrows(
                IllegalStateException.class,
                () -> DockerSmoke.verify("fixture:image", jar, arguments -> {
                    calls.add(List.of(arguments));
                    return arguments[0].equals("exec") ? "0" : "fixture-container";
                }));
        assertEquals("rm", calls.getLast().getFirst());
        assertEquals("--force", calls.getLast().get(1));
        assertTrue(calls.getFirst().contains("--read-only"));
        assertTrue(calls.getFirst().contains("127.0.0.1::8080"));
    }

    @Test
    void differentJarIsRejectedAndContainerIsRemoved() throws Exception {
        var calls = new ArrayList<List<String>>();
        var jar = java.nio.file.Files.writeString(directory.resolve("fixture.jar"), "fixture");
        assertThrows(
                IllegalStateException.class,
                () -> DockerSmoke.verify("fixture:image", jar, arguments -> {
                    calls.add(List.of(arguments));
                    return arguments[0].equals("exec") && List.of(arguments).contains("id") ? "10001" : "different";
                }));
        assertEquals("rm", calls.getLast().getFirst());
        assertFalse(calls.stream().anyMatch(call -> call.getFirst().equals("port")));
    }

    @Test
    void failedStartStillAttemptsRemovalOfOnlyItsOwnNamedContainer() throws Exception {
        var calls = new ArrayList<List<String>>();
        var jar = java.nio.file.Files.writeString(directory.resolve("fixture.jar"), "fixture");
        assertThrows(
                IllegalStateException.class,
                () -> DockerSmoke.verify("fixture:image", jar, arguments -> {
                    calls.add(List.of(arguments));
                    if (arguments[0].equals("run")) throw new IllegalStateException("Synthetic start failure");
                    return "";
                }));
        var run = calls.getFirst();
        String name = run.get(run.indexOf("--name") + 1);
        assertEquals(List.of("rm", "--force", name), calls.getLast());
    }
}
