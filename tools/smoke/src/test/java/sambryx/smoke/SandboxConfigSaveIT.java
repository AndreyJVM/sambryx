package sambryx.smoke;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import java.net.*;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SandboxConfigSaveIT {
    @TempDir
    Path directory;

    @Test
    void shippedSandboxSupportsConfigurationSaveAndRejectsInvalidContent() throws Exception {
        Path root = Path.of(System.getProperty("smoke.root")).toAbsolutePath();
        String image = System.getProperty("smoke.image");
        assertNotNull(image, "Set smoke.image to the application image under review");
        String password = HexFormat.of().formatHex(new SecureRandom().generateSeed(24));
        Path secret = Files.writeString(directory.resolve("password"), password + "\n");
        Files.setPosixFilePermissions(secret, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        Path trust = Files.writeString(directory.resolve("known_hosts"), "");
        Files.setPosixFilePermissions(trust, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
        Path override = Files.writeString(
                directory.resolve("override.yml"),
                """
                services:
                  web:
                    image: ${SAVE_SMOKE_IMAGE}
                    container_name: !reset null
                    ports: !override ["127.0.0.1::8080"]
                  samba-node:
                    build:
                      network: ${SAVE_SMOKE_BUILD_NETWORK:-default}
                    container_name: !reset null
                    ports: !override ["127.0.0.1::22"]
                volumes:
                  samba-shares:
                    name: ${COMPOSE_PROJECT_NAME}_shares
                  samba-config:
                    name: ${COMPOSE_PROJECT_NAME}_config
                """);
        var environment = new HashMap<>(System.getenv());
        environment.put(
                "COMPOSE_PROJECT_NAME",
                "sg-save-" + UUID.randomUUID().toString().substring(0, 12));
        environment.put("SAVE_SMOKE_IMAGE", image);
        environment.put("SAVE_SMOKE_BUILD_NETWORK", System.getProperty("smoke.build.network", "default"));
        String publishedNode = System.getProperty("smoke.node.image");
        environment.put(
                "SAMBA_SANDBOX_IMAGE", publishedNode == null ? "sambryx-sandbox-save-smoke:local" : publishedNode);
        environment.put("SANDBOX_ADMIN_PASSWORD_FILE", secret.toString());
        environment.put("SSH_KNOWN_HOSTS_FILE", trust.toString());
        var compose = new ArrayList<>(List.of(
                "docker",
                "compose",
                "--env-file",
                "/dev/null",
                "-f",
                root.resolve("docker-compose.yml").toString(),
                "-f",
                override.toString()));
        try {
            checked(
                    root,
                    environment,
                    compose,
                    "up",
                    "--detach",
                    publishedNode == null ? "--build" : "--no-build",
                    "--wait",
                    "--wait-timeout",
                    "180",
                    "samba-node");
            for (String tool : List.of("mktemp", "testparm", "tee", "cp", "mv", "rm", "ln")) {
                var command = new ArrayList<>(compose);
                command.addAll(List.of("exec", "-T", "--user", "admin", "samba-node", "sudo", "-n", tool, "--version"));
                var result = CommandProcess.run(command, root, environment, "");
                assertEquals(0, result.exitCode(), "Shipped sandbox must grant noninteractive " + tool);
            }
            Files.writeString(
                    trust,
                    "samba-node "
                            + checked(
                                    root,
                                    environment,
                                    compose,
                                    "exec",
                                    "-T",
                                    "samba-node",
                                    "cat",
                                    "/etc/ssh/ssh_host_ed25519_key.pub"));
            checked(root, environment, compose, "up", "--detach", "--no-build", "web");
            String binding =
                    checked(root, environment, compose, "port", "web", "8080").trim();
            var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
            try (var api = new SmokeApi(
                    URI.create("http://" + binding),
                    HttpClient.newBuilder().cookieHandler(cookies).build(),
                    cookies)) {
                await().atMost(Duration.ofSeconds(60))
                        .ignoreExceptions()
                        .untilAsserted(() -> assertEquals(
                                200,
                                api.request("GET", "/api/auth/environment", null)
                                        .status()));
                api.data(
                        "POST",
                        "/api/auth/login",
                        Map.of(
                                "host",
                                "samba-node",
                                "username",
                                "admin",
                                "authType",
                                "password",
                                "password",
                                password));
                var shell = new ArrayList<>(compose);
                shell.addAll(
                        List.of("exec", "-T", "--user", "admin", "samba-node", "sudo", "-n", "bash", "-c", "exit 0"));
                assertNotEquals(
                        0,
                        CommandProcess.run(shell, root, environment, "").exitCode(),
                        "Privileged shell must remain denied");
                String before = node(root, environment, compose, "cat", "/etc/samba/smb.conf");
                String metadata = node(root, environment, compose, "stat", "-c", "%a:%u:%g", "/etc/samba/smb.conf");
                String originalPid = node(root, environment, compose, "pgrep", "-o", "smbd");
                api.data("PUT", "/api/config/global", Map.of("serverString", "Configuration save smoke"));
                assertNotEquals(
                        originalPid,
                        node(root, environment, compose, "pgrep", "-o", "smbd"),
                        "Successful save must restart real smbd");
                String updated = node(root, environment, compose, "cat", "/etc/samba/smb.conf");
                assertTrue(updated.contains("server string = Configuration save smoke"));
                assertEquals(
                        metadata, node(root, environment, compose, "stat", "-c", "%a:%u:%g", "/etc/samba/smb.conf"));
                node(root, environment, compose, "testparm", "-s", "/etc/samba/smb.conf");
                var backups = api.data("GET", "/api/config/backups", null);
                assertFalse(backups.isEmpty());
                String backup =
                        "/etc/samba/backups/" + backups.get(0).path("filename").asText();
                assertEquals(before, node(root, environment, compose, "cat", backup));
                node(root, environment, compose, "testparm", "-s", backup);
                String pid = node(root, environment, compose, "pgrep", "-o", "smbd");
                var rejected = api.request(
                        "PUT", "/api/config/raw", Map.of("content", "[global]\nsecurity = invalid-save-smoke\n"));
                assertEquals(500, rejected.status());
                assertFalse(rejected.body().path("success").asBoolean());
                assertEquals(updated, node(root, environment, compose, "cat", "/etc/samba/smb.conf"));
                assertEquals(
                        metadata, node(root, environment, compose, "stat", "-c", "%a:%u:%g", "/etc/samba/smb.conf"));
                assertEquals(pid, node(root, environment, compose, "pgrep", "-o", "smbd"));
                assertEquals(
                        "",
                        node(
                                root,
                                environment,
                                compose,
                                "find",
                                "/etc/samba",
                                "-maxdepth",
                                "1",
                                "-name",
                                ".smb.conf.update.*"));
                String backupListing =
                        api.data("GET", "/api/config/backups", null).toString();
                node(root, environment, compose, "mv", "/etc/sudoers.d/sambryx-admin", "/root/saved-sambryx-policy");
                var incompatible =
                        api.request("PUT", "/api/config/global", Map.of("serverString", "Must not be installed"));
                assertEquals(500, incompatible.status());
                assertTrue(incompatible
                        .body()
                        .path("message")
                        .asText()
                        .contains("Review configuration save sudo permissions"));
                assertEquals(updated, node(root, environment, compose, "cat", "/etc/samba/smb.conf"));
                assertEquals(
                        backupListing,
                        api.data("GET", "/api/config/backups", null).toString());
                assertEquals(pid, node(root, environment, compose, "pgrep", "-o", "smbd"));
                assertEquals(
                        "",
                        node(
                                root,
                                environment,
                                compose,
                                "find",
                                "/etc/samba",
                                "-maxdepth",
                                "1",
                                "-name",
                                ".smb.conf.update.*"));
                // The packaged entrypoint repairs its own policy, without rerunning package/account setup.
                checked(root, environment, compose, "restart", "samba-node");
                checked(root, environment, compose, "up", "--detach", "--no-build", "--wait", "samba-node");
                assertEquals(updated, node(root, environment, compose, "cat", "/etc/samba/smb.conf"));
                assertEquals(
                        metadata, node(root, environment, compose, "stat", "-c", "%a:%u:%g", "/etc/samba/smb.conf"));
                var permission = new ArrayList<>(compose);
                permission.addAll(
                        List.of("exec", "-T", "--user", "admin", "samba-node", "sudo", "-n", "mktemp", "--version"));
                assertEquals(
                        0, CommandProcess.run(permission, root, environment, "").exitCode());
            }
        } finally {
            checked(root, environment, compose, "down", "--volumes", "--remove-orphans");
        }
    }

    private String node(Path root, Map<String, String> environment, List<String> compose, String... args)
            throws Exception {
        var tail = new ArrayList<>(List.of("exec", "-T", "samba-node"));
        tail.addAll(List.of(args));
        return checked(root, environment, compose, tail.toArray(String[]::new));
    }

    private String checked(Path root, Map<String, String> environment, List<String> compose, String... args)
            throws Exception {
        var command = new ArrayList<>(compose);
        command.addAll(List.of(args));
        var result = CommandProcess.run(command, root, environment, "");
        assertEquals(
                0,
                result.exitCode(),
                "Sandbox fixture command failed: " + String.join(" ", args) + "\n" + result.output());
        return result.output();
    }
}
