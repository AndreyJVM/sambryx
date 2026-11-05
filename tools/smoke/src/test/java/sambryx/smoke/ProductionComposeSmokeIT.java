package sambryx.smoke;

import static sambryx.smoke.ComposeFixture.check;

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
            verifyRestore(fixture);
        }
    }

    private void verifyHostIdentity(ComposeFixture f) throws Exception {
        f.api.data("GET", "/api/auth/environment", null);
        var credentials = Map.of(
                "host",
                "samba-node",
                "username",
                "root",
                "authType",
                "key",
                "privateKey",
                Files.readString(f.directory.resolve("management")));
        String expected = f.node("", "ssh-keygen", "-lf", "/etc/ssh/ssh_host_ed25519_key.pub", "-E", "sha256")
                .split("\\s+")[1];
        for (String reason : new String[] {"UNKNOWN", "CHANGED"}) {
            if (reason.equals("CHANGED")) {
                Files.writeString(
                        f.directory.resolve("known_hosts"),
                        "samba-node " + Files.readString(f.directory.resolve("management.pub")));
            }
            var response = f.api.request("POST", "/api/auth/login", credentials);
            check(response.status() == 409, "Untrusted SSH host was not rejected");
            check(
                    response.body().path("data").path("code").asText().equals("SSH_HOST_KEY_" + reason),
                    "Wrong SSH identity error");
            check(
                    response.body().path("data").path("fingerprint").asText().equals(expected),
                    "Fingerprint differs from server console");
            check(f.api.request("GET", "/api/auth/me", null).status() == 401, "Identity failure authenticated a user");
        }
        // Docker exec provides the trusted administrative channel for this disposable fixture.
        Files.writeString(
                f.directory.resolve("known_hosts"),
                "samba-node " + f.node("", "cat", "/etc/ssh/ssh_host_ed25519_key.pub"));
        System.out.println("PASS: unknown and changed host keys rejected before authentication");
    }

    private void verifyPersistence(ComposeFixture f) throws Exception {
        f.api.data(
                "POST",
                "/api/users",
                Map.of("username", "sgfixture", "password", f.password, "fullName", "Disposable fixture"));
        f.api.data(
                "POST",
                "/api/shares",
                Map.of(
                        "name",
                        "lifecycle",
                        "path",
                        "/srv/samba/lifecycle",
                        "readOnly",
                        false,
                        "guestOk",
                        false,
                        "browseable",
                        true,
                        "validUsers",
                        "sgfixture",
                        "forceUser",
                        "sgfixture",
                        "directoryMask",
                        "0770",
                        "createMask",
                        "0660"));
        String uid = f.node("", "id", "-u", "sgfixture");
        String key = f.node("", "ssh-keygen", "-lf", "/etc/ssh/ssh_host_ed25519_key.pub");
        f.smb(true);
        f.api.data("POST", "/api/monitoring/control?action=restart", Map.of());
        check(
                f.api.data("GET", "/api/monitoring/dashboard", null)
                        .path("isRunning")
                        .asBoolean(),
                "Service restart failed");
        f.start(true);
        f.login();
        check(f.node("", "id", "-u", "sgfixture").equals(uid), "User UID changed after recreation");
        check(
                f.node("", "ssh-keygen", "-lf", "/etc/ssh/ssh_host_ed25519_key.pub")
                        .equals(key),
                "SSH host identity changed");
        f.smb(false);
        check(
                StreamSupport.stream(f.api.data("GET", "/api/shares", null).spliterator(), false)
                        .anyMatch(share -> share.path("name").asText().equals("lifecycle")),
                "Share configuration was lost");
        System.out.println("PASS: SMB data, password, UID, host key and shares survive recreation");
    }

    private void verifyRestore(ComposeFixture f) throws Exception {
        String original = config(f);
        check(
                f.api.data("GET", "/api/config/raw", null).asText().equals(original),
                "API and node configuration differ");
        f.api.data("POST", "/api/config/backups", Map.of());
        var backups = f.api.data("GET", "/api/config/backups", null);
        String created = StreamSupport.stream(backups.spliterator(), false)
                .map(item -> item.path("filename").asText())
                .max(Comparator.naturalOrder())
                .orElseThrow(() -> new IllegalStateException("Created backup is not listed"));
        String backup = "smb.conf.backup_20990101_000001";
        f.node("", "mv", "--", "/etc/samba/backups/" + created, "/etc/samba/backups/" + backup);
        check(
                StreamSupport.stream(
                                f.api.data("GET", "/api/config/backups", null).spliterator(), false)
                        .anyMatch(item -> item.path("filename").asText().equals(backup)),
                "Restore candidate is not listed");
        check(f.node("", "cat", "/etc/samba/backups/" + backup).equals(original), "Backup content differs");
        String changed = original + "\n[global]\nserver string = Disposable restore smoke\n";
        f.api.data("PUT", "/api/config/raw", Map.of("content", changed));
        check(config(f).equals(changed), "Configuration change was not installed");
        f.api.data("POST", "/api/config/backups/" + backup + "/restore", Map.of());
        check(config(f).equals(original), "Restore did not recover exact backup content");
        check(
                f.api.data("GET", "/api/config/raw", null).asText().equals(original),
                "API did not read restored content");
        check(
                f.api.data("GET", "/api/monitoring/dashboard", null)
                        .path("isRunning")
                        .asBoolean(),
                "Samba stopped after restore");
        f.smb(false);
        System.out.println("PASS: backup, configuration change and restore recover exact content");

        String corrupted = "smb.conf.backup_20990101_000002";
        String path = "/etc/samba/backups/" + corrupted;
        f.node("[global]\nsecurity = invalid-smoke-value\n", "tee", path);
        check(f.nodeResult("testparm", "-s", path).exitCode() != 0, "Corrupted fixture passed real testparm");
        String before = config(f);
        String pid = pid(f);
        check(pid.matches("[1-9][0-9]*"), "Cannot observe the running Samba process");
        var rejected = f.api.request("POST", "/api/config/backups/restore?filename=" + corrupted, Map.of());
        check(
                rejected.status() == 500
                        && rejected.body().path("success").isBoolean()
                        && !rejected.body().path("success").booleanValue(),
                "Invalid backup was not rejected");
        check(config(f).equals(before), "Rejected backup replaced active configuration");
        check(
                f.api.data("GET", "/api/config/raw", null).asText().equals(before),
                "API content changed after rejected restore");
        check(pid(f).equals(pid), "Rejected backup restarted Samba");
        f.smb(false);
        System.out.println("PASS: invalid backup rejected without changing configuration, Samba PID or SMB data");
    }

    private String config(ComposeFixture f) throws Exception {
        return f.node("", "cat", "/etc/samba/smb.conf");
    }

    private String pid(ComposeFixture f) throws Exception {
        return f.node("", "supervisorctl", "-c", "/opt/sambryx-node/supervisord.conf", "pid", "smbd")
                .trim();
    }
}
