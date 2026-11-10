package mari.samba.smbconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import mari.samba.auth.ConnectionRequestDto;
import mari.samba.config.SambaProperties;
import mari.samba.core.SambaCommandException;
import mari.samba.infra.SmbConfParser;
import mari.samba.infra.SshSessionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Explicit Docker/SSH test; run with -Dtest=BackupSshIT and a prepared sandbox image. */
class BackupSshIT {
    private static final String ROOT = "/srv/backup-it";
    private static final String BACKUPS = ROOT + "/backups";
    private static final String CONFIG = ROOT + "/smb.conf";
    private static final String SESSION = "backup-ssh-fixture";

    @TempDir
    Path directory;

    private SshSessionManager ssh;
    private SambaConfigService service;

    @Test
    @Timeout(120)
    void createsListsAndPreservesBackupsOverSshWithoutPrivilegedShell() throws Exception {
        String image = System.getProperty("backup.ssh.image", "");
        assertThat(image)
                .as("Set backup.ssh.image to a locally prepared sandbox image")
                .isNotBlank();
        String container = "sambryx-backup-it-" + UUID.randomUUID();
        try {
            startContainer(container, image);
            prepareServer(container);
            connect(container);

            assertThat(ssh.executeCommand(SESSION, "sudo -n bash -c 'exit 0'").exitCode())
                    .isNotZero();
            assertThat(ssh.execute(SESSION, "id -u").trim()).isNotEqualTo("0");
            verifyBoundedInput(container);
            verifySnapshots(container);
            verifyUpdates(container);
            verifyDeniedCopy(container);

        } finally {
            if (ssh != null) ssh.disconnect(SESSION);
            command(List.of("docker", "rm", "--force", container), "");
        }
    }

    private void startContainer(String container, String image) throws Exception {
        command(
                List.of(
                        "docker",
                        "run",
                        "--detach",
                        "--name",
                        container,
                        "--publish",
                        "127.0.0.1::22",
                        "--entrypoint",
                        "/bin/sh",
                        image,
                        "-c",
                        "mkdir -p /run/sshd && ssh-keygen -A && exec /usr/sbin/sshd -D -e"),
                "");
    }

    private void connect(String container) throws Exception {
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(exec(container, "", "cat", "/etc/ssh/ssh_host_ed25519_key.pub"))
                        .isNotBlank());
        String portOutput =
                command(List.of("docker", "port", container, "22/tcp"), "").trim();
        int port = Integer.parseInt(portOutput.substring(portOutput.lastIndexOf(':') + 1));
        String publicHostKey =
                exec(container, "", "cat", "/etc/ssh/ssh_host_ed25519_key.pub").trim();
        Path trust =
                Files.writeString(directory.resolve("known_hosts"), "[127.0.0.1]:" + port + " " + publicHostKey + "\n");
        var properties = new SambaProperties(
                new SambaProperties.Ssh(
                        Duration.ofSeconds(3), Duration.ofSeconds(10), Duration.ofSeconds(30), 3, trust.toString()),
                new SambaProperties.Paths(CONFIG, BACKUPS, ROOT + "/log"),
                null,
                null);
        ssh = new SshSessionManager(properties);
        var request = new ConnectionRequestDto(
                "127.0.0.1", port, "admin", "key", null, Files.readString(directory.resolve("management")), null);
        SshSessionManager manager = ssh;
        await().atMost(Duration.ofSeconds(15)).until(() -> {
            try {
                manager.createSession(SESSION, request);
                return true;
            } catch (SambaCommandException error) {
                return false;
            }
        });
        service = new SambaConfigService(ssh, new SmbConfParser(), properties);
    }

    private void verifyBoundedInput(String container) throws Exception {
        String input = "synthetic-input\n".repeat(300_000);
        String result = ssh.execute(SESSION, "head -c 4194304 /dev/zero; wc -c", input);
        String count = input.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + "\n";
        assertThat(result.length()).isEqualTo(4_194_304 + count.length());
        assertThat(result.substring(4_194_304)).isEqualTo(count);
        assertThat(result.chars().limit(4_194_304).allMatch(value -> value == 0))
                .isTrue();

        String blocked = "/home/admin/blocked-stdin";
        exec(container, "", "mkfifo", blocked);
        assertThatThrownBy(() -> ssh.execute(SESSION, "cat -- '" + blocked + "'", input))
                .isInstanceOf(SambaCommandException.class)
                .hasMessage("SSH command timed out after 10000 ms.");
        assertThat(ssh.execute(SESSION, "printf recovered")).isEqualTo("recovered");
    }

    private void verifySnapshots(String container) throws Exception {
        service.createBackup(SESSION);
        var first = service.listBackups(SESSION);
        assertThat(first).hasSize(1);
        String firstName = first.getFirst().filename();
        exec(container, "second", "tee", CONFIG);
        service.createBackup(SESSION);
        var second = service.listBackups(SESSION);
        assertThat(second).hasSize(2);
        assertThat(exec(container, "", "cat", BACKUPS + "/" + firstName)).isEqualTo("first");
        for (var backup : second) {
            assertThat(exec(container, "", "cat", BACKUPS + "/" + backup.filename()))
                    .isIn("first", "second");
        }
    }

    private void verifyUpdates(String container) throws Exception {
        String systemctl =
                exec(container, "", "sh", "-c", "command -v systemctl").trim();
        exec(container, "#!/bin/sh\nprintf restarted > /srv/backup-it/restarted\n", "tee", systemctl);
        exec(container, "", "chmod", "755", systemctl);
        String valid = "[global]\nworkgroup = UPDATED\n";
        service.updateSmbConf(SESSION, valid);
        assertThat(exec(container, "", "cat", CONFIG)).isEqualTo(valid);
        assertThat(exec(container, "", "stat", "-c", "%a:%U:%G", CONFIG).trim()).isEqualTo("600:root:root");
        exec(container, "", "rm", ROOT + "/restarted");
        assertThatThrownBy(() -> service.updateSmbConf(SESSION, "[broken\n")).isInstanceOf(SambaCommandException.class);
        assertThat(exec(container, "", "cat", CONFIG)).isEqualTo(valid);
        assertThat(exec(container, "", "find", ROOT, "-name", ".smb.conf.update.*"))
                .isEmpty();
        assertThat(exec(container, "", "find", ROOT, "-name", "restarted")).isEmpty();
        String policy = exec(container, "", "cat", "/etc/sudoers.d/sambryx-admin");
        for (String denied : List.of("tee", "mv")) {
            String restricted = policy.replaceAll("(?:/bin/|/usr/bin/)" + denied + "(?=,|\\s|$),?\\s*", "");
            exec(container, restricted, "tee", "/etc/sudoers.d/sambryx-admin");
            assertThatThrownBy(() -> service.updateSmbConf(SESSION, "[global]\nworkgroup = DENIED\n"))
                    .isInstanceOf(SambaCommandException.class);
            assertThat(exec(container, "", "cat", CONFIG)).isEqualTo(valid);
            assertThat(exec(container, "", "find", ROOT, "-name", ".smb.conf.update.*"))
                    .isEmpty();
            assertThat(exec(container, "", "find", ROOT, "-name", "restarted")).isEmpty();
        }
        exec(container, policy, "tee", "/etc/sudoers.d/sambryx-admin");
        verifyOverlappingUpdates(container);
        exec(container, "second", "tee", CONFIG);
    }

    private void verifyOverlappingUpdates(String container) throws Exception {
        String testparm = exec(container, "", "sh", "-c", "command -v testparm").trim();
        exec(container, "", "mv", testparm, testparm + ".real");
        String wrapper = "#!/bin/sh\ncase \"$1\" in --version) exec " + testparm + ".real --version;; esac\n" + testparm
                + ".real \"$@\" >/dev/null || exit 1\n"
                + "name=$(sed -n 's/workgroup = //p' \"$3\")\n"
                + "printf '%s' \"$3\" > " + ROOT + "/$name-ready\n"
                + "read -r go < " + ROOT + "/$name-go\n";
        exec(container, wrapper, "tee", testparm);
        exec(container, "", "chmod", "755", testparm);
        for (String name : List.of("first", "second")) {
            exec(container, "", "mkfifo", ROOT + "/" + name + "-ready", ROOT + "/" + name + "-go");
        }
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> service.updateSmbConf(SESSION, "[global]\nworkgroup = first\n"));
            var firstReady = pool.submit(() -> exec(container, "", "cat", ROOT + "/first-ready"));
            String firstPath = firstReady.get(15, TimeUnit.SECONDS);
            var second = pool.submit(() -> service.updateSmbConf(SESSION, "[global]\nworkgroup = second\n"));
            var secondReady = pool.submit(() -> exec(container, "", "cat", ROOT + "/second-ready"));
            String secondPath = secondReady.get(15, TimeUnit.SECONDS);
            assertThat(firstPath).isNotEqualTo(secondPath);
            assertThat(exec(container, "", "stat", "-c", "%a:%U:%G", firstPath).trim())
                    .isEqualTo("600:root:root");
            exec(container, "go\n", "tee", ROOT + "/second-go");
            second.get(15, TimeUnit.SECONDS);
            assertThat(exec(container, "", "cat", CONFIG)).contains("workgroup = second");
            assertThat(exec(container, "", "cat", firstPath)).contains("workgroup = first");
            exec(container, "go\n", "tee", ROOT + "/first-go");
            first.get(15, TimeUnit.SECONDS);
            assertThat(exec(container, "", "cat", CONFIG)).contains("workgroup = first");
        } finally {
            exec(container, "", "mv", testparm + ".real", testparm);
        }
        assertThat(exec(container, "", "find", ROOT, "-name", ".smb.conf.update.*"))
                .isEmpty();
    }

    private void verifyDeniedCopy(String container) throws Exception {
        var before = service.listBackups(SESSION);
        exec(
                container,
                "admin ALL=(root) NOPASSWD: /usr/bin/mkdir, /usr/bin/mktemp, /usr/bin/ln, /usr/bin/rm\n",
                "tee",
                "/etc/sudoers.d/sambryx-admin");
        assertThatThrownBy(() -> service.createBackup(SESSION)).isInstanceOf(SambaCommandException.class);
        assertThat(service.listBackups(SESSION)).containsExactlyElementsOf(before);
        assertThat(exec(container, "", "find", BACKUPS, "-maxdepth", "1", "-name", ".smb.conf.staging.*"))
                .isEmpty();
        assertThat(exec(container, "", "cat", CONFIG)).isEqualTo("second");
    }

    private void prepareServer(String container) throws Exception {
        exec(
                container,
                "",
                "sh",
                "-c",
                "set -eu; id admin >/dev/null 2>&1 || useradd -m -s /bin/bash admin; mkdir -p " + BACKUPS
                        + " /tmp/backup-it-bin /home/admin/.ssh; "
                        + "chmod 755 " + BACKUPS + "; chmod 700 /home/admin/.ssh; "
                        + "rm -f /etc/sudoers.d/samba-web-ui; "
                        + "printf '#!/bin/sh\\nexit 0\\n' > /tmp/backup-it-bin/apt-get; chmod 755 /tmp/backup-it-bin/apt-get");
        exec(container, "admin:" + UUID.randomUUID() + "\n", "chpasswd");
        command(
                List.of(
                        "ssh-keygen",
                        "-q",
                        "-t",
                        "ed25519",
                        "-N",
                        "",
                        "-f",
                        directory.resolve("management").toString()),
                "");
        exec(
                container,
                Files.readString(directory.resolve("management.pub")),
                "tee",
                "/home/admin/.ssh/authorized_keys");
        exec(
                container,
                "",
                "sh",
                "-c",
                "chown -R admin:admin /home/admin/.ssh; chmod 600 /home/admin/.ssh/authorized_keys");
        exec(
                container,
                Files.readString(Path.of("scripts/setup-samba-server.sh")),
                "env",
                "PATH=/tmp/backup-it-bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "bash",
                "-s",
                "--",
                "admin");
        exec(container, "", "visudo", "-c", "-f", "/etc/sudoers.d/sambryx-admin");
        exec(container, "first", "tee", CONFIG);
        exec(container, "", "chmod", "600", CONFIG);
    }

    private String exec(String container, String input, String... args) throws Exception {
        var command = new ArrayList<>(List.of("docker", "exec", "-i", container));
        command.addAll(List.of(args));
        return command(command, input);
    }

    private String command(List<String> arguments, String input) throws Exception {
        Path output = Files.createTempFile(directory, "process-", ".log");
        Process process = new ProcessBuilder(arguments)
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        try {
            try (var stdin = process.getOutputStream()) {
                stdin.write(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            assertThat(process.waitFor(30, TimeUnit.SECONDS))
                    .as("Fixture command timeout")
                    .isTrue();
            assertThat(process.exitValue())
                    .as("Fixture command failed: %s", arguments.getFirst())
                    .isZero();
            return Files.readString(output);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(output);
        }
    }
}
