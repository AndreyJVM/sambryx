package sambryx.smoke;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProductionLauncherTest {
    @TempDir
    Path directory;

    @Test
    void stopsBeforeStartingContainersWhenImageResolutionFails() throws Exception {
        var result = launch("andreyvorobevaqa/sambryx:0.12.0\n", 23);
        assertNotEquals(0, result.exitCode());
        assertFalse(started());
    }

    @Test
    void rejectsAnEmptyImageListBeforeStartingContainers() throws Exception {
        var result = launch("", 0);
        assertNotEquals(0, result.exitCode());
        assertFalse(started());
    }

    @ParameterizedTest
    @ValueSource(strings = {"sambryx:latest", "sambryx", "sambryx:REPLACE_WITH_RELEASE_TAG"})
    void rejectsUnpinnedImagesBeforeStartingContainers(String image) throws Exception {
        var result = launch(image + "\n", 0);
        assertNotEquals(0, result.exitCode());
        assertFalse(started());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "andreyvorobevaqa/sambryx:0.12.0",
                "registry.example.test:5000/sambryx:0.12.0",
                "andreyvorobevaqa/sambryx@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            })
    void startsContainersAfterSuccessfulImageValidation(String image) throws Exception {
        var result = launch(image + "\nnginx:1.30.6-alpine\n", 0);
        assertEquals(0, result.exitCode(), result.output());
        assertTrue(started());
    }

    private boolean started() throws Exception {
        return Files.readString(directory.resolve("calls")).contains("up --detach --wait");
    }

    private CommandProcess.Result launch(String images, int imageExit) throws Exception {
        Path docker = directory.resolve("docker");
        Files.writeString(
                docker,
                """
                #!/bin/bash
                printf '%s\\n' "$*" >> "$FIXTURE_CALLS"
                case "$*" in
                  *'config --quiet') exit 0 ;;
                  *'config --images') printf '%s' "$FIXTURE_IMAGES"; exit "$FIXTURE_IMAGE_EXIT" ;;
                  *'up --detach --wait'*) exit 0 ;;
                  *) exit 99 ;;
                esac
                """);
        Files.setPosixFilePermissions(docker, PosixFilePermissions.fromString("rwx------"));
        Path config = Files.writeString(directory.resolve("fixture.env"), "");
        var environment = new HashMap<>(System.getenv());
        environment.put("PATH", directory + ":/usr/bin:/bin");
        environment.put("SAMBRYX_ENV_FILE", config.toString());
        environment.put("FIXTURE_CALLS", directory.resolve("calls").toString());
        environment.put("FIXTURE_IMAGES", images);
        environment.put("FIXTURE_IMAGE_EXIT", Integer.toString(imageExit));
        Path root = Path.of(System.getProperty("user.dir")).resolve("../..").normalize();
        return CommandProcess.run(
                List.of("bash", root.resolve("scripts/production/run.sh").toString()), root, environment, "");
    }
}
