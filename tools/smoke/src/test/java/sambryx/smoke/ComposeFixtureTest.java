package sambryx.smoke;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ComposeFixtureTest {
    @TempDir
    Path directory;

    @Test
    void usesUniqueProjectsAndRandomLoopbackPortsWithoutLoadingUserEnvironmentFile() throws Exception {
        try (var first = new ComposeFixture("fixture:image", directory, directory);
                var second = new ComposeFixture("fixture:image", directory, directory)) {
            int project = first.compose.indexOf("--project-name") + 1;
            assertNotEquals(first.compose.get(project), second.compose.get(project));
            assertTrue(first.compose.get(project).startsWith("sg-smoke-"));
            assertEquals("/dev/null", first.compose.get(first.compose.indexOf("--env-file") + 1));
            assertEquals("127.0.0.1", first.environment.get("HTTPS_BIND_IP"));
            assertEquals("127.0.0.1", first.environment.get("SAMBA_BIND_IP"));
            assertEquals("0", first.environment.get("HTTPS_PORT"));
            assertEquals("0", first.environment.get("SAMBA_PORT"));
        }
    }

    @Test
    void rejectsMissingImageBeforePreparingOrStartingTheFixture() {
        assertThrows(IllegalArgumentException.class, () -> new ComposeFixture("", directory, directory));
        assertThrows(IllegalArgumentException.class, () -> new ComposeFixture("${smoke.image}", directory, directory));
    }
}
