package mari.samba.smbconfig;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import mari.samba.config.SambaProperties;
import mari.samba.core.SambaCommandException;
import mari.samba.infra.*;
import mari.samba.support.ShellCommandFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigStagingTest {
    @TempDir
    Path root;

    private Path config;
    private ShellCommandFixture shell;
    private Path restarted;
    private SambaConfigService service;

    @BeforeEach
    void prepare() throws Exception {
        config = Files.writeString(root.resolve("config's file"), "original");
        Files.setPosixFilePermissions(config, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"));
        shell = new ShellCommandFixture(root);
        restarted = root.resolve("restart");
        shell.stub("sudo", "if [ \"$1\" = -n ]; then shift; fi; case \"$1\" in bash|sh) exit 77;; esac; exec \"$@\"");
        shell.stub("testparm", "test -s \"$3\"");
        shell.stub("systemctl", "touch " + LinuxCommands.escape(restarted.toString()));
        var executor = mock(CommandExecutor.class);
        lenient().when(executor.executeCommand(anyString(), anyString())).thenReturn(new CommandResult(0, "", ""));
        lenient()
                .when(executor.execute(anyString(), anyString()))
                .thenAnswer(c -> shell.runChecked(c.getArgument(1), ""));
        when(executor.execute(anyString(), anyString(), anyString()))
                .thenAnswer(c -> shell.runChecked(c.getArgument(1), c.getArgument(2)));
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
        shell.stub(command, body);
        assertThatThrownBy(() -> service.updateSmbConf("session", "updated")).isInstanceOf(SambaCommandException.class);
        assertThat(Files.readString(config)).isEqualTo("original");
        assertThat(Files.readString(foreign)).isEqualTo("foreign");
        assertOnlyStaging(foreign);
        assertThat(restarted).doesNotExist();
    }

    @Test
    void cleanupFailureReportsFailureAfterInstallationWithoutClaimingRollback() throws Exception {
        shell.stub("rm", "case \"$*\" in *update*) exit 29;; esac; exec /usr/bin/rm \"$@\"");
        assertThatThrownBy(() -> service.updateSmbConf("session", "updated"))
                .isInstanceOf(SambaCommandException.class)
                .hasMessageContaining("Configuration staging cleanup failed.");
        assertThat(Files.readString(config)).isEqualTo("updated");
        assertThat(restarted).doesNotExist();
    }

    @Test
    void validationAndCleanupFailureLeavesOnlyOwnResidueAndReportsCleanupError() throws Exception {
        Path foreign = Files.writeString(root.resolve(".smb.conf.update.foreign"), "foreign");
        shell.stub("testparm", "exit 29");
        shell.stub("rm", "case \"$*\" in *update*) exit 29;; esac; exec /usr/bin/rm \"$@\"");
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
            shell.runChecked("mkfifo -- "
                    + LinuxCommands.escape(root.resolve(name + "-ready").toString()) + " "
                    + LinuxCommands.escape(root.resolve(name + "-go").toString()));
        }
        shell.stub(
                "testparm",
                "content=$(cat -- \"$3\"); printf '%s' \"$3\" > " + LinuxCommands.escape(root.toString())
                        + "/$content-ready; read -r go < " + LinuxCommands.escape(root.toString())
                        + "/$content-go; test \"$(cat -- \"$3\")\" = \"$content\"");
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> first = pool.submit(() -> service.updateSmbConf("first", "first"));
            Future<String> firstReady = pool.submit(() -> shell.runChecked(
                    "timeout 10s cat -- "
                            + LinuxCommands.escape(root.resolve("first-ready").toString()),
                    ""));
            String firstPath = firstReady.get(10, TimeUnit.SECONDS);
            Future<?> second = pool.submit(() -> service.updateSmbConf("second", "second"));
            Future<String> secondReady = pool.submit(() -> shell.runChecked(
                    "timeout 10s cat -- "
                            + LinuxCommands.escape(root.resolve("second-ready").toString()),
                    ""));
            String secondPath = secondReady.get(10, TimeUnit.SECONDS);
            assertThat(firstPath).isNotEqualTo(secondPath);
            shell.runChecked(
                    "timeout 10s tee -- "
                            + LinuxCommands.escape(root.resolve("second-go").toString()),
                    "go\n");
            second.get(10, TimeUnit.SECONDS);
            assertThat(Files.readString(config)).isEqualTo("second");
            assertThat(Files.readString(Path.of(firstPath))).isEqualTo("first");
            shell.runChecked(
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
}
