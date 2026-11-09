package sambryx.smoke;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
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
            verifyUserDeletionPreservesFiles(fixture);
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
        assertEquals(
                "sgfixture:sgfixture:770",
                fixture.node("", "stat", "-c", "%U:%G:%a", "/srv/samba/lifecycle")
                        .trim(),
                "Disposable SMB directory must have explicitly prepared access");
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
        // Provision access explicitly: saving a share preserves filesystem ownership and permissions.
        fixture.node("", "install", "-d", "-o", "sgfixture", "-g", "sgfixture", "-m", "0770", "/srv/samba/lifecycle");
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

    private void verifyUserDeletionPreservesFiles(ComposeFixture fixture) throws Exception {
        var before = preparePreservedFiles(fixture);
        // Mutate through the authenticated application session; observe independently via Docker exec.
        var response = fixture.api.request("DELETE", "/api/users/sgfixture", Map.of());
        assertAll(
                "Account deletion preserves data independently of the HTTP response",
                () -> assertEquals(200, response.status(), "Deletion HTTP status"),
                () -> assertTrue(response.body().path("success").asBoolean(), "Deletion API envelope"),
                () -> verifyLinuxAccountIsAbsent(fixture),
                () -> assertFalse(hasSambaAccount(fixture), "Samba account remains visible"),
                () -> assertAll(
                        "Preserved filesystem entries",
                        before.entrySet().stream()
                                .map(entry -> () -> assertEquals(
                                        entry.getValue(),
                                        snapshot(fixture, entry.getKey()),
                                        "Changed entry: " + entry.getKey()))));
        System.out.println(
                "PASS: HTTP/SSH deletion removes Linux/Samba accounts and preserves home, mail and share data"
                        + " with numeric UID/GID and modes");
    }

    private Map<String, FileSnapshot> preparePreservedFiles(ComposeFixture fixture) throws Exception {
        String account = fixture.node("", "getent", "passwd", "sgfixture").trim();
        String[] fields = account.split(":", -1);
        assertEquals(7, fields.length, "Synthetic Linux account must exist before deletion");
        assertEquals("/home/sgfixture", fields[5], "Unexpected synthetic home path");
        assertTrue(hasSambaAccount(fixture), "Synthetic Samba account must exist before deletion");
        prepareHomeAndMail(fixture);
        var before = new LinkedHashMap<String, FileSnapshot>();
        for (String path : List.of(
                "/home/sgfixture",
                "/home/sgfixture/preserved.txt",
                "/var/mail/sgfixture",
                "/srv/samba/lifecycle",
                "/srv/samba/lifecycle/sentinel.txt")) {
            FileSnapshot snapshot = snapshot(fixture, path);
            assertTrue(
                    snapshot.metadata().startsWith(fields[2] + ":" + fields[3] + ":"),
                    "Fixture must belong to the synthetic UID/GID: " + path);
            before.put(path, snapshot);
        }
        return before;
    }

    private void prepareHomeAndMail(ComposeFixture fixture) throws Exception {
        fixture.node("", "chmod", "0750", "/home/sgfixture");
        fixture.node("", "install", "-d", "/var/mail");
        for (String path : List.of("/home/sgfixture/preserved.txt", "/var/mail/sgfixture")) {
            fixture.node("", "install", "-o", "sgfixture", "-g", "sgfixture", "-m", "0640", "/dev/null", path);
            fixture.node("Synthetic preservation fixture\n", "tee", path);
        }
    }

    private void verifyLinuxAccountIsAbsent(ComposeFixture fixture) throws Exception {
        var result = fixture.nodeResult("getent", "passwd", "sgfixture");
        assertEquals(2, result.exitCode(), "Linux account must be absent, not a failed probe");
        assertTrue(result.output().isBlank(), "Linux account remains visible");
    }

    private boolean hasSambaAccount(ComposeFixture fixture) throws Exception {
        return fixture.node("", "pdbedit", "-L").lines().anyMatch(line -> line.startsWith("sgfixture:"));
    }

    private FileSnapshot snapshot(ComposeFixture fixture, String path) throws Exception {
        String metadata =
                fixture.node("", "stat", "-c", "%u:%g:%a:%F", "--", path).trim();
        String content = metadata.endsWith(":directory") ? "" : fixture.node("", "cat", "--", path);
        return new FileSnapshot(metadata, content);
    }

    private record FileSnapshot(String metadata, String content) {}

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
