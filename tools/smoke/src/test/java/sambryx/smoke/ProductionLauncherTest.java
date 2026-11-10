package sambryx.smoke;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ProductionLauncherTest {
    @TempDir
    Path directory;

    private String modelOverride;
    private int modelExit;
    private boolean omitJq;
    private boolean launchFromRepository;

    @Test
    void stopsBeforeStartingContainersWhenImageResolutionFails() throws Exception {
        var result = launch("andreyvorobevaqa/sambryx:0.12.0\n", 23);
        assertFalse(result.output().contains("fixture-private-content"));
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

    @ParameterizedTest
    @CsvSource({
        "known_hosts,missing", "known_hosts,empty", "known_hosts,directory", "known_hosts,unreadable",
        "certificate.pem,missing", "certificate.pem,empty", "certificate.pem,directory", "certificate.pem,unreadable",
        "private.key,missing", "private.key,empty", "private.key,directory", "private.key,unreadable"
    })
    void rejectsInvalidInputWithoutStartingOrMutatingFiles(String name, String state) throws Exception {
        prepareInputs();
        Path input = directory.resolve("input files").resolve(name);
        switch (state) {
            case "missing" -> Files.delete(input);
            case "empty" -> Files.writeString(input, "");
            case "directory" -> {
                Files.delete(input);
                Files.createDirectory(input);
            }
            case "unreadable" -> {
                Files.setPosixFilePermissions(input, PosixFilePermissions.fromString("---------"));
                assumeFalse(Files.isReadable(input), "This user can read files without permission bits");
            }
            default -> throw new IllegalArgumentException(state);
        }
        var permissions = Files.exists(input) ? Files.getPosixFilePermissions(input) : null;
        var result = launch("sambryx:0.15.0\n", 0);
        assertNotEquals(0, result.exitCode(), result.output());
        assertFalse(started());
        assertTrue(result.output().contains(name.equals("known_hosts") ? "known_hosts" : "TLS"), result.output());
        if (name.equals("known_hosts") && state.equals("empty")) {
            assertTrue(result.output().contains("docs/ssh-host-keys.md"), result.output());
        }
        assertFalse(result.output().contains("fixture-private-content"));
        assertFalse(Files.exists(directory.resolve("should-not-exist")));
        for (String other : List.of("known_hosts", "certificate.pem", "private.key")) {
            if (!other.equals(name))
                assertEquals(
                        "fixture-private-content",
                        Files.readString(directory.resolve("input files").resolve(other)));
        }
        if (permissions == null) assertFalse(Files.exists(input));
        else {
            assertEquals(permissions, Files.getPosixFilePermissions(input));
            if (state.equals("directory")) assertTrue(Files.isDirectory(input));
            else if (!state.equals("unreadable"))
                assertEquals(state.equals("empty") ? "" : "fixture-private-content", Files.readString(input));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acceptsDeploymentSymlinksFromDifferentWorkingDirectories(boolean fromRepository) throws Exception {
        launchFromRepository = fromRepository;
        prepareInputs();
        Path key = directory.resolve("input files/private.key");
        Path target = directory.resolve("rotated key");
        Files.move(key, target);
        Files.createSymbolicLink(key, target);
        var result = launch("sambryx:0.15.0\n", 0);
        assertEquals(0, result.exitCode(), result.output());
        assertTrue(started());
        assertTrue(Files.isSymbolicLink(key));
        assertEquals("fixture-private-content", Files.readString(target));
    }

    @Test
    void rejectsMissingJqBeforeStartingContainers() throws Exception {
        omitJq = true;
        Files.createSymbolicLink(directory.resolve("dirname"), Path.of("/usr/bin/dirname"));
        var result = launch("sambryx:0.15.0\n", 0);
        assertNotEquals(0, result.exitCode());
        assertTrue(result.output().contains("Install jq"), result.output());
        assertFalse(started());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "fixture-private-content", "null"})
    void rejectsUnresolvedFilePathsWithoutPrintingTheModel(String output) throws Exception {
        modelOverride = output;
        var result = launch("sambryx:0.15.0\n", 0);
        assertNotEquals(0, result.exitCode());
        assertFalse(started());
        assertFalse(result.output().contains("fixture-private-content"));
        assertTrue(result.output().contains("Compose did not resolve"), result.output());
    }

    @Test
    void stopsWhenComposeModelResolutionFails() throws Exception {
        modelExit = 27;
        var result = launch("sambryx:0.15.0\n", 0);
        assertNotEquals(0, result.exitCode());
        assertFalse(started());
        assertTrue(result.output().contains("Compose model resolution failed"), result.output());
    }

    private void prepareInputs() throws Exception {
        Path inputs = Files.createDirectories(directory.resolve("input files"));
        for (String name : List.of("known_hosts", "certificate.pem", "private.key")) {
            Path input = inputs.resolve(name);
            if (!Files.exists(input)) Files.writeString(input, "fixture-private-content");
        }
    }

    private boolean started() throws Exception {
        return Files.exists(directory.resolve("calls"))
                && Files.readString(directory.resolve("calls")).contains("up --detach --wait");
    }

    private CommandProcess.Result launch(String images, int imageExit) throws Exception {
        if (!Files.exists(directory.resolve("input files"))) prepareInputs();
        var mapper = new ObjectMapper();
        var model = mapper.createObjectNode();
        var volume =
                model.putObject("services").putObject("web").putArray("volumes").addObject();
        volume.put("target", "/etc/sambryx/known_hosts");
        volume.put("source", directory.resolve("input files/known_hosts").toString());
        var secrets = model.putObject("secrets");
        secrets.putObject("tls_certificate")
                .put("file", directory.resolve("input files/certificate.pem").toString());
        secrets.putObject("tls_private_key")
                .put("file", directory.resolve("input files/private.key").toString());
        Path docker = directory.resolve("docker");
        Files.writeString(
                docker,
                """
                #!/bin/bash
                printf '%s\\n' "$*" >> "$FIXTURE_CALLS"
                case "$*" in
                  *'config --quiet') exit 0 ;;
                  *'config --format json') printf '%s' "$FIXTURE_MODEL"; exit "$FIXTURE_MODEL_EXIT" ;;
                  *'config --images')
                    if [[ "$FIXTURE_IMAGE_EXIT" != 0 ]]; then echo fixture-private-content >&2; fi
                    printf '%s' "$FIXTURE_IMAGES"; exit "$FIXTURE_IMAGE_EXIT" ;;
                  *'up --detach --wait'*) exit 0 ;;
                  *) exit 99 ;;
                esac
                """);
        Files.setPosixFilePermissions(docker, PosixFilePermissions.fromString("rwx------"));
        Path config = Files.writeString(directory.resolve("fixture config.env"), "UNUSED=$(touch should-not-exist)\n");
        var environment = new HashMap<>(System.getenv());
        environment.put("PATH", omitJq ? directory.toString() : directory + ":/usr/bin:/bin");
        environment.put("SAMBRYX_ENV_FILE", config.getFileName().toString());
        environment.put("FIXTURE_CALLS", directory.resolve("calls").toString());
        environment.put("FIXTURE_IMAGES", images);
        environment.put("FIXTURE_MODEL", modelOverride == null ? mapper.writeValueAsString(model) : modelOverride);
        environment.put("FIXTURE_MODEL_EXIT", Integer.toString(modelExit));
        environment.put("FIXTURE_IMAGE_EXIT", Integer.toString(imageExit));
        Path root = Path.of(System.getProperty("user.dir")).resolve("../..").normalize();
        Path cwd = launchFromRepository ? root : directory;
        environment.put("SAMBRYX_ENV_FILE", cwd.relativize(config).toString());
        return CommandProcess.run(
                List.of("bash", root.resolve("scripts/production/run.sh").toString()), cwd, environment, "");
    }
}
