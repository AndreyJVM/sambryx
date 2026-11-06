package sambryx.smoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProductionComposeSmokeIT {
    @TempDir
    Path directory;

    @Test
    void verifiesHttpsSshSmbPersistenceAndSafeRestore() throws Exception {
        Path root = Path.of(System.getProperty("smoke.root"));
        try (var fixture = new ComposeFixture(System.getProperty("smoke.image"), root, directory)) {
            fixture.prepare();
            fixture.start(false);
            verifyHostIdentity(fixture);
            fixture.login();
            verifyPersistence(fixture);
            verifyValidRestore(fixture);
            verifyInvalidBackupIsRejected(fixture);
        }
    }

    private void verifyHostIdentity(ComposeFixture fixture) throws Exception {
        fixture.api.data("GET", "/api/auth/environment", null);
        var credentials = Map.of(
                "host",
                "samba-node",
                "username",
                "root",
                "authType",
                "key",
                "privateKey",
                Files.readString(fixture.directory.resolve("management")));
        String expected = fixture.node("", "ssh-keygen", "-lf", "/etc/ssh/ssh_host_ed25519_key.pub", "-E", "sha256")
                .split("\\s+")[1];
        for (String reason : new String[] {"UNKNOWN", "CHANGED"}) {
            if (reason.equals("CHANGED")) {
                Files.writeString(
                        fixture.directory.resolve("known_hosts"),
                        "samba-node " + Files.readString(fixture.directory.resolve("management.pub")));
            }
            var response = fixture.api.request("POST", "/api/auth/login", credentials);
            assertTrue(response.status() == 409, "Untrusted SSH host was not rejected");
            assertTrue(
                    response.body().path("data").path("code").asText().equals("SSH_HOST_KEY_" + reason),
                    "Wrong SSH identity error");
            assertTrue(
                    response.body().path("data").path("fingerprint").asText().equals(expected),
                    "Fingerprint differs from server console");
            assertTrue(
                    fixture.api.request("GET", "/api/auth/me", null).status() == 401,
                    "Identity failure authenticated a user");
        }
        // Docker exec provides the trusted administrative channel for this disposable fixture.
        Files.writeString(
                fixture.directory.resolve("known_hosts"),
                "samba-node " + fixture.node("", "cat", "/etc/ssh/ssh_host_ed25519_key.pub"));
        System.out.println("PASS: unknown and changed host keys rejected before authentication");
    }

    private void verifyPersistence(ComposeFixture fixture) throws Exception {
        createUserAndShare(fixture);
        String uid = fixture.node("", "id", "-u", "sgfixture");
        String key = fixture.node("", "ssh-keygen", "-lf", "/etc/ssh/ssh_host_ed25519_key.pub");
        fixture.smb(true);
        fixture.api.data("POST", "/api/monitoring/control?action=restart", Map.of());
        assertTrue(
                fixture.api
                        .data("GET", "/api/monitoring/dashboard", null)
                        .path("isRunning")
                        .asBoolean(),
                "Service restart failed");
        fixture.start(true);
        fixture.login();
        assertEquals(uid, fixture.node("", "id", "-u", "sgfixture"), "User UID changed after recreation");
        assertTrue(
                fixture.node("", "ssh-keygen", "-lf", "/etc/ssh/ssh_host_ed25519_key.pub")
                        .equals(key),
                "SSH host identity changed");
        fixture.smb(false);
        assertTrue(
                StreamSupport.stream(
                                fixture.api.data("GET", "/api/shares", null).spliterator(), false)
                        .anyMatch(share -> share.path("name").asText().equals("lifecycle")),
                "Share configuration was lost");
        System.out.println("PASS: SMB data, password, UID, host key and shares survive recreation");
    }

    private void createUserAndShare(ComposeFixture fixture) throws Exception {
        fixture.api.data(
                "POST",
                "/api/users",
                Map.of("username", "sgfixture", "password", fixture.password, "fullName", "Disposable fixture"));
        fixture.api.data(
                "POST",
                "/api/shares",
                new ShareRequest(
                        "lifecycle",
                        "/srv/samba/lifecycle",
                        false,
                        false,
                        true,
                        "sgfixture",
                        "sgfixture",
                        "0770",
                        "0660"));
    }

    private void verifyValidRestore(ComposeFixture fixture) throws Exception {
        String original = config(fixture);
        assertTrue(
                fixture.api.data("GET", "/api/config/raw", null).asText().equals(original),
                "API and node configuration differ");
        fixture.api.data("POST", "/api/config/backups", Map.of());
        var backups = fixture.api.data("GET", "/api/config/backups", null);
        String created = StreamSupport.stream(backups.spliterator(), false)
                .map(item -> item.path("filename").asText())
                .max(Comparator.naturalOrder())
                .orElseThrow(() -> new IllegalStateException("Created backup is not listed"));
        String backup = "smb.conf.backup_20990101_000001";
        fixture.node("", "mv", "--", "/etc/samba/backups/" + created, "/etc/samba/backups/" + backup);
        assertTrue(
                StreamSupport.stream(
                                fixture.api
                                        .data("GET", "/api/config/backups", null)
                                        .spliterator(),
                                false)
                        .anyMatch(item -> item.path("filename").asText().equals(backup)),
                "Restore candidate is not listed");
        assertTrue(fixture.node("", "cat", "/etc/samba/backups/" + backup).equals(original), "Backup content differs");
        String changed = original + "\n[global]\nserver string = Disposable restore smoke\n";
        fixture.api.data("PUT", "/api/config/raw", Map.of("content", changed));
        assertEquals(changed, config(fixture), "Configuration change was not installed");
        fixture.api.data("POST", "/api/config/backups/" + backup + "/restore", Map.of());
        assertTrue(config(fixture).equals(original), "Restore did not recover exact backup content");
        assertTrue(
                fixture.api.data("GET", "/api/config/raw", null).asText().equals(original),
                "API did not read restored content");
        assertTrue(
                fixture.api
                        .data("GET", "/api/monitoring/dashboard", null)
                        .path("isRunning")
                        .asBoolean(),
                "Samba stopped after restore");
        fixture.smb(false);
        System.out.println("PASS: backup, configuration change and restore recover exact content");
    }

    private void verifyInvalidBackupIsRejected(ComposeFixture fixture) throws Exception {
        String corrupted = "smb.conf.backup_20990101_000002";
        String path = "/etc/samba/backups/" + corrupted;
        fixture.node("[global]\nsecurity = invalid-smoke-value\n", "tee", path);
        assertTrue(
                fixture.nodeResult("testparm", "-s", path).exitCode() != 0, "Corrupted fixture passed real testparm");
        String before = config(fixture);
        String pid = pid(fixture);
        assertTrue(pid.matches("[1-9][0-9]*"), "Cannot observe the running Samba process");
        var rejected = fixture.api.request("POST", "/api/config/backups/restore?filename=" + corrupted, Map.of());
        assertTrue(
                rejected.status() == 500
                        && rejected.body().path("success").isBoolean()
                        && !rejected.body().path("success").booleanValue(),
                "Invalid backup was not rejected");
        assertEquals(before, config(fixture), "Rejected backup replaced active configuration");
        assertTrue(
                fixture.api.data("GET", "/api/config/raw", null).asText().equals(before),
                "API content changed after rejected restore");
        assertEquals(pid, pid(fixture), "Rejected backup restarted Samba");
        fixture.smb(false);
        System.out.println("PASS: invalid backup rejected without changing configuration, Samba PID or SMB data");
    }

    private record ShareRequest(
            String name,
            String path,
            boolean readOnly,
            boolean guestOk,
            boolean browseable,
            String validUsers,
            String forceUser,
            String directoryMask,
            String createMask) {}

    private String config(ComposeFixture fixture) throws Exception {
        return fixture.node("", "cat", "/etc/samba/smb.conf");
    }

    private String pid(ComposeFixture fixture) throws Exception {
        return fixture.node("", "supervisorctl", "-c", "/opt/sambryx-node/supervisord.conf", "pid", "smbd")
                .trim();
    }
}
