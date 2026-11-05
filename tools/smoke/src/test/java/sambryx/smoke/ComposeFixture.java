package sambryx.smoke;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

final class ComposeFixture implements AutoCloseable {
    final Path directory;
    final Path root;
    final Map<String, String> environment;
    final List<String> compose;
    final String password = HexFormat.of().formatHex(new SecureRandom().generateSeed(24));
    SmokeApi api;
    private boolean started;
    private Thread shutdownHook;
    private final AtomicBoolean closed = new AtomicBoolean();

    ComposeFixture(String image, Path root, Path directory) throws Exception {
        if (image == null || image.isBlank() || image.startsWith("${") || image.startsWith("-")) {
            throw new IllegalArgumentException("Set smoke.image to a locally built application image");
        }
        this.directory = directory;
        this.root = root.toAbsolutePath().normalize();
        environment = new HashMap<>(System.getenv());
        environment.put("SAMBRYX_IMAGE", image);
        environment.put("TLS_CERT_FILE", directory.resolve("certificate.pem").toString());
        environment.put("TLS_KEY_FILE", directory.resolve("tls.key").toString());
        environment.put(
                "SSH_AUTHORIZED_KEYS_FILE", directory.resolve("management.pub").toString());
        environment.put("SSH_KNOWN_HOSTS_FILE", directory.resolve("known_hosts").toString());
        environment.put("PROXY_UID", checked(List.of("id", "-u"), "").trim());
        environment.put("PROXY_GID", checked(List.of("id", "-g"), "").trim());
        if (environment.get("PROXY_UID").equals("0"))
            throw new IllegalStateException("Run smoke as a non-root Docker user");
        environment.put("HTTPS_BIND_IP", "127.0.0.1");
        environment.put("HTTPS_PORT", "0");
        environment.put("SAMBA_BIND_IP", "127.0.0.1");
        environment.put("SAMBA_PORT", "0");
        compose = List.of(
                "docker",
                "compose",
                "--env-file",
                "/dev/null",
                "--project-name",
                "sg-smoke-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                "-f",
                this.root.resolve("docker-compose.production.yml").toString(),
                "-f",
                this.root.resolve("docker-compose.samba.yml").toString());
    }

    void prepare() throws Exception {
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        Files.writeString(directory.resolve("known_hosts"), "");
        Files.setPosixFilePermissions(directory.resolve("known_hosts"), PosixFilePermissions.fromString("rw-r--r--"));
        checked(
                List.of(
                        "openssl",
                        "req",
                        "-x509",
                        "-newkey",
                        "rsa:2048",
                        "-nodes",
                        "-keyout",
                        environment.get("TLS_KEY_FILE"),
                        "-out",
                        environment.get("TLS_CERT_FILE"),
                        "-days",
                        "1",
                        "-subj",
                        "/CN=localhost",
                        "-addext",
                        "subjectAltName=DNS:localhost,IP:127.0.0.1"),
                "");
        Files.setPosixFilePermissions(directory.resolve("tls.key"), PosixFilePermissions.fromString("rw-------"));
        checked(
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
    }

    void start(boolean recreate) throws Exception {
        if (!started) {
            started = true;
            shutdownHook = new Thread(
                    () -> {
                        try {
                            close();
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            System.err.println("Compose smoke cleanup interrupted");
                        } catch (IOException | RuntimeException error) {
                            System.err.println("Compose smoke cleanup failed");
                        }
                    },
                    "compose-smoke-cleanup");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        }
        var args = new ArrayList<>(List.of("up", "--detach", "--wait", "--wait-timeout", "180", "--no-build"));
        if (recreate) args.add("--force-recreate");
        command("", args.toArray(String[]::new));
        String binding = command("", "port", "nginx", "8443").trim();
        if (api != null) api.close();
        api = SmokeApi.trusted(URI.create("https://" + binding), directory.resolve("certificate.pem"));
    }

    String command(String input, String... args) throws IOException, InterruptedException {
        var command = new ArrayList<>(compose);
        command.addAll(List.of(args));
        return checked(command, input);
    }

    String node(String input, String... args) throws IOException, InterruptedException {
        var command = new ArrayList<>(List.of("exec", "-T", "samba-node"));
        command.addAll(List.of(args));
        return command(input, command.toArray(String[]::new));
    }

    CommandProcess.Result nodeResult(String... args) throws IOException, InterruptedException {
        var command = new ArrayList<>(compose);
        command.addAll(List.of("exec", "-T", "samba-node"));
        command.addAll(List.of(args));
        return CommandProcess.run(command, root, environment, "");
    }

    private String checked(List<String> command, String input) throws IOException, InterruptedException {
        var result = CommandProcess.run(command, root, environment, input);
        if (result.exitCode() != 0) {
            throw new IllegalStateException(
                    "Smoke command failed: " + command.getFirst() + " (exit " + result.exitCode() + ")");
        }
        return result.output();
    }

    void smb(boolean write) throws Exception {
        // Only the fixed operation is an argument. Credentials travel on stdin, never in argv.
        String script =
                """
                set -eu
                umask 077
                directory=$(mktemp -d)
                trap 'rm -rf -- "$directory"' EXIT HUP INT TERM
                cat > "$directory/auth"
                printf 'persistent SMB fixture' > "$directory/source.txt"
                if [ "$1" = write ]; then
                    smbclient //127.0.0.1/lifecycle -A "$directory/auth" -c "put $directory/source.txt sentinel.txt" >/dev/null 2>&1
                else
                    smbclient //127.0.0.1/lifecycle -A "$directory/auth" -c "get sentinel.txt $directory/download.txt" >/dev/null 2>&1
                    cmp "$directory/source.txt" "$directory/download.txt"
                fi
                """;
        node(
                "username = sgfixture\npassword = " + password + "\n",
                "sh",
                "-c",
                script,
                "smb-probe",
                write ? "write" : "read");
    }

    void login() throws Exception {
        check(
                api.data("GET", "/api/auth/environment", null)
                        .path("mode")
                        .asText()
                        .equals("remote"),
                "Expected SSH mode");
        check(api.request("GET", "/api/auth/me", null).status() == 401, "Anonymous session was accepted");
        api.data(
                "POST",
                "/api/auth/login",
                Map.of(
                        "host",
                        "samba-node",
                        "username",
                        "root",
                        "authType",
                        "key",
                        "privateKey",
                        Files.readString(directory.resolve("management"))));
        check(api.data("GET", "/api/auth/me", null).path("user").asText().equals("root"), "SSH key login failed");
        var session = api.cookie("JSESSIONID");
        check(session.getSecure() && session.isHttpOnly(), "Session cookie is not protected");
        check(
                api.data("GET", "/api/monitoring/dashboard", null)
                        .path("isRunning")
                        .asBoolean(),
                "Samba is not running");
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    @Override
    public void close() throws IOException, InterruptedException {
        if (!closed.compareAndSet(false, true)) return;
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException shuttingDown) {
                /* The registered hook is already running. */
            }
        }
        try {
            if (started) command("", "down", "--volumes", "--remove-orphans");
        } finally {
            if (api != null) api.close();
        }
    }
}
