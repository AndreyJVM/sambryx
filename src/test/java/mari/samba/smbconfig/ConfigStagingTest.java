package mari.samba.smbconfig;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import mari.samba.config.SambaProperties;
import mari.samba.core.SambaCommandException;
import mari.samba.infra.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigStagingTest {
    @TempDir
    Path root;

    private Path config;
    private Path bin;
    private Path restarted;
    private SambaConfigService service;

    @BeforeEach
    void prepare() throws Exception {
        config = Files.writeString(root.resolve("config's file"), "original");
        Files.setPosixFilePermissions(config, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"));
        bin = Files.createDirectory(root.resolve("bin"));
        restarted = root.resolve("restart");
        stub("sudo", "if [ \"$1\" = -n ]; then shift; fi; case \"$1\" in bash|sh) exit 77;; esac; exec \"$@\"");
        stub("testparm", "test -s \"$3\"");
        stub("systemctl", "touch " + LinuxCommands.escape(restarted.toString()));
        var executor = mock(CommandExecutor.class);
        lenient().when(executor.executeCommand(anyString(), anyString())).thenReturn(new CommandResult(0, "", ""));
        lenient().when(executor.execute(anyString(), anyString())).thenAnswer(c -> run(c.getArgument(1), ""));
        when(executor.execute(anyString(), anyString(), anyString()))
                .thenAnswer(c -> run(c.getArgument(1), c.getArgument(2)));
        var properties = new SambaProperties(
                null,
                new SambaProperties.Paths(
                        config.toString(),
                        root.resolve("backups").toString(),
                        root.resolve("log").toString()),
                null,
                null);
        service = new SambaConfigService(executor, new SmbConfParser(), properties);
    }

    @Test
    void installsValidatedContentPreservesModeAndDoesNotFollowPlantedSymlink() throws Exception {
        Path victim = Files.writeString(root.resolve("victim"), "untouched");
        Path planted = Files.createSymbolicLink(root.resolve(".smb.conf.update.XXXXXX"), victim);
        service.updateSmbConf("session", "updated");
        assertThat(Files.readString(config)).isEqualTo("updated");
        assertThat(Files.getPosixFilePermissions(config))
                .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"));
        assertThat(Files.readString(victim)).isEqualTo("untouched");
        assertThat(planted).isSymbolicLink();
        assertThat(restarted).exists();
        assertOnlyStaging(planted);
    }

    @ParameterizedTest
    @ValueSource(strings = {"mktemp", "tee", "testparm", "mv", "metadata"})
    void failedStageCleansOnlyItsOwnFileAndNeverRestarts(String stage) throws Exception {
        Path foreign = Files.writeString(root.resolve(".smb.conf.update.foreign"), "foreign");
        String command = stage.equals("metadata") ? "cp" : stage;
        String body =
                switch (stage) {
                    case "mktemp" -> "case \"$*\" in *update*) exit 29;; esac; exec /usr/bin/mktemp \"$@\"";
                    case "tee" -> "printf partial > \"$2\"; exit 29";
                    case "metadata" -> "case \"$1\" in --attributes-only) exit 29;; esac; exec /usr/bin/cp \"$@\"";
                    default -> "exit 29";
                };
        stub(command, body);
        assertThatThrownBy(() -> service.updateSmbConf("session", "updated")).isInstanceOf(SambaCommandException.class);
        assertThat(Files.readString(config)).isEqualTo("original");
        assertThat(Files.readString(foreign)).isEqualTo("foreign");
        assertOnlyStaging(foreign);
        assertThat(restarted).doesNotExist();
    }

    @Test
    void cleanupFailureReportsFailureAfterInstallationWithoutClaimingRollback() throws Exception {
        stub("rm", "case \"$*\" in *update*) exit 29;; esac; exec /usr/bin/rm \"$@\"");
        assertThatThrownBy(() -> service.updateSmbConf("session", "updated"))
                .isInstanceOf(SambaCommandException.class)
                .hasMessageContaining("Configuration staging cleanup failed.");
        assertThat(Files.readString(config)).isEqualTo("updated");
        assertThat(restarted).doesNotExist();
    }

    @Test
    void validationAndCleanupFailureLeavesOnlyOwnResidueAndReportsCleanupError() throws Exception {
        Path foreign = Files.writeString(root.resolve(".smb.conf.update.foreign"), "foreign");
        stub("testparm", "exit 29");
        stub("rm", "case \"$*\" in *update*) exit 29;; esac; exec /usr/bin/rm \"$@\"");
        assertThatThrownBy(() -> service.updateSmbConf("session", "updated"))
                .isInstanceOf(SambaCommandException.class)
                .hasMessageContaining("Configuration staging cleanup failed.");
        assertThat(Files.readString(config)).isEqualTo("original");
        assertThat(Files.readString(foreign)).isEqualTo("foreign");
        assertThat(restarted).doesNotExist();
        try (var paths = Files.list(root)) {
            assertThat(paths.filter(p -> p.getFileName().toString().startsWith(".smb.conf.update."))
                            .toList())
                    .hasSize(2);
        }
    }

    @Test
    void intersectingUpdatesValidateAndInstallTheirOwnContents() throws Exception {
        for (String name : List.of("first", "second")) {
            assertThat(new ProcessBuilder(
                                    "mkfifo",
                                    root.resolve(name + "-ready").toString(),
                                    root.resolve(name + "-go").toString())
                            .start()
                            .waitFor())
                    .isZero();
        }
        stub(
                "testparm",
                "content=$(cat -- \"$3\"); printf '%s' \"$3\" > " + LinuxCommands.escape(root.toString())
                        + "/$content-ready; read -r go < " + LinuxCommands.escape(root.toString())
                        + "/$content-go; test \"$(cat -- \"$3\")\" = \"$content\"");
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> first = pool.submit(() -> service.updateSmbConf("first", "first"));
            Future<String> firstReady = pool.submit(() -> run(
                    "timeout 10s cat -- "
                            + LinuxCommands.escape(root.resolve("first-ready").toString()),
                    ""));
            String firstPath = firstReady.get(10, TimeUnit.SECONDS);
            Future<?> second = pool.submit(() -> service.updateSmbConf("second", "second"));
            Future<String> secondReady = pool.submit(() -> run(
                    "timeout 10s cat -- "
                            + LinuxCommands.escape(root.resolve("second-ready").toString()),
                    ""));
            String secondPath = secondReady.get(10, TimeUnit.SECONDS);
            assertThat(firstPath).isNotEqualTo(secondPath);
            run(
                    "timeout 10s tee -- "
                            + LinuxCommands.escape(root.resolve("second-go").toString()),
                    "go\n");
            second.get(10, TimeUnit.SECONDS);
            assertThat(Files.readString(config)).isEqualTo("second");
            assertThat(Files.readString(Path.of(firstPath))).isEqualTo("first");
            run(
                    "timeout 10s tee -- "
                            + LinuxCommands.escape(root.resolve("first-go").toString()),
                    "go\n");
            first.get(10, TimeUnit.SECONDS);
            assertThat(Files.readString(config)).isEqualTo("first");
        }
        assertOnlyStaging();
    }

    private void assertOnlyStaging(Path... expected) throws Exception {
        try (var paths = Files.list(root)) {
            assertThat(paths.filter(p -> p.getFileName().toString().startsWith(".smb.conf.update."))
                            .toList())
                    .containsExactlyInAnyOrder(expected);
        }
    }

    private void stub(String name, String body) throws Exception {
        Path file = Files.writeString(bin.resolve(name), "#!/bin/sh\n" + body + "\n");
        assertThat(file.toFile().setExecutable(true)).isTrue();
    }

    private String run(String command, String input) throws Exception {
        Path output = Files.createTempFile(root, "command-", ".log");
        var builder = new ProcessBuilder("/bin/sh", "-c", command)
                .redirectErrorStream(true)
                .redirectOutput(output.toFile());
        builder.environment().put("PATH", bin + ":/usr/bin:/bin");
        Process process = builder.start();
        try {
            try (var stdin = process.getOutputStream()) {
                stdin.write(input.getBytes(StandardCharsets.UTF_8));
            }
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
            String result = Files.readString(output);
            if (process.exitValue() != 0) throw new SambaCommandException(result);
            return result;
        } finally {
            process.destroyForcibly();
            Files.deleteIfExists(output);
        }
    }
}
