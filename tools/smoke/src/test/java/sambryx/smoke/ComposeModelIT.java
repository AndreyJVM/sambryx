package sambryx.smoke;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ComposeModelIT {
    @TempDir
    Path directory;

    @Test
    void externalServerModeExposesOnlyLoopbackHttpsAndProtectsHostTrust() throws Exception {
        var model = render(false, false, "sambryx-production");
        var services = model.path("services");
        assertEquals(2, services.size());
        var web = services.path("web");
        var proxy = services.path("nginx");
        assertTrue(web.path("ports").isMissingNode() || web.path("ports").isEmpty());
        var trust = StreamSupport.stream(web.path("volumes").spliterator(), false)
                .filter(mount -> mount.path("target").asText().equals("/etc/sambryx/known_hosts"))
                .findFirst()
                .orElseThrow();
        assertTrue(trust.path("read_only").asBoolean());
        assertFalse(trust.path("bind").path("create_host_path").asBoolean());
        assertEquals(1, proxy.path("ports").size());
        assertEquals(8443, proxy.path("ports").get(0).path("target").asInt());
        assertEquals("127.0.0.1", proxy.path("ports").get(0).path("host_ip").asText());
        assertTrue(model.path("networks").path("proxy").path("internal").asBoolean());
        for (var service : new JsonNode[] {web, proxy}) {
            assertTrue(service.path("read_only").asBoolean());
            assertNotEquals("0", service.path("user").asText().split(":")[0]);
            assertTrue(service.path("cap_drop").toString().contains("ALL"));
            assertFalse(service.path("privileged").asBoolean());
        }
    }

    @Test
    void tlsMaterialIsReadOnlyAndNotEnvironmentContent() throws Exception {
        var proxy = render(false, false, "sambryx-production").path("services").path("nginx");
        var sources = StreamSupport.stream(proxy.path("secrets").spliterator(), false)
                .map(secret -> secret.path("source").asText())
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(java.util.Set.of("tls_certificate", "tls_private_key"), sources);
        assertTrue(StreamSupport.stream(proxy.path("volumes").spliterator(), false)
                .allMatch(mount -> mount.path("read_only").asBoolean()));
        assertFalse(proxy.has("environment"));
    }

    @Test
    void bundledServerPersistsIdentityAndNeverPublishesSsh() throws Exception {
        var model = render(true, false, "sambryx-production");
        var node = model.path("services").path("samba-node");
        assertEquals(1, node.path("ports").size());
        assertEquals(445, node.path("ports").get(0).path("target").asInt());
        assertFalse(node.path("privileged").asBoolean());
        for (String target : new String[] {"/etc", "/var/lib/samba", "/srv/samba", "/home"}) {
            var mount = StreamSupport.stream(node.path("volumes").spliterator(), false)
                    .filter(value -> value.path("target").asText().equals(target))
                    .findFirst()
                    .orElseThrow();
            assertEquals("volume", mount.path("type").asText());
            assertFalse(mount.path("source").asText().isBlank());
        }
        assertTrue(model.path("networks").path("management").path("internal").asBoolean());
        for (String network : new String[] {"proxy", "ssh-egress", "management"}) {
            assertTrue(model.path("services").path("web").path("networks").has(network));
        }
        assertFalse(node.path("environment").has("AUTO_ADMIN_PASS"));
        assertEquals(1, node.path("secrets").size());
        assertEquals(
                "ssh_authorized_keys",
                node.path("secrets").get(0).path("source").asText());
    }

    @Test
    void existingProjectKeepsItsPersistentVolumeNames() throws Exception {
        var model = render(true, false, "shareguard-production");
        assertEquals("shareguard-production", model.path("name").asText());
        for (String volume : new String[] {"node-identity", "samba-state", "samba-data", "samba-home", "samba-logs"}) {
            assertEquals(
                    "shareguard-production_" + volume,
                    model.path("volumes").path(volume).path("name").asText());
        }
    }

    @Test
    void applicationImageIsMandatory() throws Exception {
        render(false, true, "sambryx-production");
    }

    @Test
    void resolvesEnvFilePathsAgainstComposeDirectoryFromAnotherWorkingDirectory() throws Exception {
        Path root = Path.of(System.getProperty("smoke.root")).toAbsolutePath().normalize();
        Path env = directory.resolve("custom config.env");
        Files.writeString(
                env,
                """
                SAMBRYX_IMAGE=sambryx:0.15.0
                SSH_KNOWN_HOSTS_FILE='./input files/known_hosts'
                TLS_CERT_FILE='./input files/certificate.pem'
                TLS_KEY_FILE='./input files/private.key'
                UNUSED=$(touch should-not-exist)
                """);
        var environment = new java.util.HashMap<>(System.getenv());
        for (String name : new String[] {"SAMBRYX_IMAGE", "SSH_KNOWN_HOSTS_FILE", "TLS_CERT_FILE", "TLS_KEY_FILE"}) {
            environment.remove(name);
        }
        var result = CommandProcess.run(
                java.util.List.of(
                        "docker",
                        "compose",
                        "--env-file",
                        env.getFileName().toString(),
                        "-f",
                        root.resolve("docker-compose.production.yml").toString(),
                        "config",
                        "--format",
                        "json"),
                directory,
                environment,
                "");
        assertEquals(0, result.exitCode(), "Compose model rendering failed");
        var model = new ObjectMapper().readTree(result.output());
        var trust = StreamSupport.stream(
                        model.path("services").path("web").path("volumes").spliterator(), false)
                .filter(mount -> mount.path("target").asText().equals("/etc/sambryx/known_hosts"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                root.resolve("input files/known_hosts").toString(),
                trust.path("source").asText());
        assertEquals(
                root.resolve("input files/certificate.pem").toString(),
                model.path("secrets").path("tls_certificate").path("file").asText());
        assertEquals(
                root.resolve("input files/private.key").toString(),
                model.path("secrets").path("tls_private_key").path("file").asText());
        assertFalse(Files.exists(directory.resolve("should-not-exist")));
    }

    private JsonNode render(boolean bundled, boolean missingImage, String project) throws Exception {
        Path root = Path.of(System.getProperty("smoke.root"));
        try (var fixture = new ComposeFixture("fixture:image", root, directory)) {
            Path placeholder = Files.writeString(directory.resolve("fixture"), "fixture-only");
            for (String name :
                    new String[] {"TLS_CERT_FILE", "TLS_KEY_FILE", "SSH_AUTHORIZED_KEYS_FILE", "SSH_KNOWN_HOSTS_FILE"
                    }) {
                fixture.environment.put(name, placeholder.toString());
            }
            var command = new ArrayList<>(fixture.compose);
            command.set(command.indexOf("--project-name") + 1, project);
            if (!bundled) command.subList(command.size() - 2, command.size()).clear();
            if (missingImage) fixture.environment.remove("SAMBRYX_IMAGE");
            command.addAll(java.util.List.of("config", "--format", "json"));
            var result = CommandProcess.run(command, root, fixture.environment, "");
            if (missingImage) {
                assertNotEquals(0, result.exitCode());
                assertTrue(result.output().contains("SAMBRYX_IMAGE"));
                return null;
            }
            assertEquals(0, result.exitCode(), "Compose model rendering failed");
            return new ObjectMapper().readTree(result.output());
        }
    }
}
