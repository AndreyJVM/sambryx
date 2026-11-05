package sambryx.smoke;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandProcessTest {
    @TempDir
    Path directory;

    @Test
    void deliversInputOnStdinAndRetainsExitStatus() throws Exception {
        var result = CommandProcess.run(List.of("sh", "-c", "cat; exit 7"), directory, Map.of(), "synthetic-input");
        assertEquals(7, result.exitCode());
        assertEquals("synthetic-input", result.output());
    }

    @Test
    void appliesExplicitWorkingDirectoryAndEnvironment() throws Exception {
        var result = CommandProcess.run(
                List.of("sh", "-c", "printf '%s\\n' \"$SMOKE_FIXTURE\"; pwd"),
                directory,
                Map.of("SMOKE_FIXTURE", "synthetic-fixture"),
                "");
        assertEquals(0, result.exitCode());
        assertEquals("synthetic-fixture\n" + directory + "\n", result.output());
    }

    @Test
    void doesNotReintroduceHostVariablesExcludedFromTheExplicitEnvironment() throws Exception {
        var result = CommandProcess.run(List.of("sh", "-c", "test -z \"${HOME+x}\""), directory, Map.of(), "");
        assertEquals(0, result.exitCode());
    }
}
