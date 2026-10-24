package mari.samba.infra;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.jcraft.jsch.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import mari.samba.auth.ConnectionRequestDto;
import mari.samba.config.SambaProperties;
import mari.samba.core.SshHostKeyException;
import mari.samba.core.SshSessionExpiredException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VerifiedHostKeysTest {
    @TempDir
    Path directory;

    private static byte[] key;
    private static byte[] differentKey;

    @BeforeAll
    static void keys() throws Exception {
        var first = KeyPair.genKeyPair(new JSch(), KeyPair.RSA, 2048);
        var second = KeyPair.genKeyPair(new JSch(), KeyPair.RSA, 2048);
        key = first.getPublicKeyBlob();
        differentKey = second.getPublicKeyBlob();
        first.dispose();
        second.dispose();
    }

    private Path file(String entry) throws Exception {
        return Files.writeString(directory.resolve("known_hosts"), entry);
    }

    private String entry(String host, byte[] bytes) {
        return host + " ssh-rsa " + Base64.getEncoder().encodeToString(bytes) + "\n";
    }

    @Test
    void trustedKeyMatchesButNeverAuthorizesAnotherPortOrHost() throws Exception {
        Path path = file(entry("fixture.test", key) + entry("[fixture.test]:2222", differentKey));
        byte[] original = Files.readAllBytes(path);
        var repository = VerifiedHostKeys.configure(new JSch(), path.toString());
        assertThat(repository.check("fixture.test", key)).isEqualTo(HostKeyRepository.OK);
        assertThat(repository.check("[fixture.test]:2222", key)).isEqualTo(HostKeyRepository.CHANGED);
        assertThat(repository.check("[fixture.test]:2223", key)).isEqualTo(HostKeyRepository.NOT_INCLUDED);
        assertThat(repository.check("other.test", key)).isEqualTo(HostKeyRepository.NOT_INCLUDED);
        assertThat(Files.readAllBytes(path)).isEqualTo(original);
    }

    @Test
    void hashedEntriesAndMultipleKeysAreSupported() throws Exception {
        byte[] salt = new byte[20];
        var mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(salt, "HmacSHA1"));
        String host = "[fixture.test]:2222";
        String hashed = "|1|" + Base64.getEncoder().encodeToString(salt) + "|"
                + Base64.getEncoder()
                        .encodeToString(mac.doFinal(host.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var repository = VerifiedHostKeys.configure(
                new JSch(),
                file(entry(hashed, key) + entry(hashed, differentKey)).toString());
        assertThat(repository.check(host, key)).isEqualTo(HostKeyRepository.OK);
        assertThat(repository.check(host, differentKey)).isEqualTo(HostKeyRepository.OK);
        assertThat(repository.check("[fixture.test]:2223", key)).isEqualTo(HostKeyRepository.NOT_INCLUDED);
    }

    @Test
    void revokedKeyCannotBeRescuedByAnotherTrustedEntry() throws Exception {
        var repository = VerifiedHostKeys.configure(
                new JSch(),
                file(entry("fixture.test", key) + "@revoked " + entry("fixture.test", key))
                        .toString());
        assertThat(repository.check("fixture.test", key)).isEqualTo(HostKeyRepository.CHANGED);
        assertThat(repository.rejection().code()).isEqualTo("SSH_HOST_KEY_CHANGED");
        assertThat(repository.rejection().fingerprint()).matches("SHA256:[A-Za-z0-9+/]{43}");
    }

    @Test
    void trustStoreIsReadOnlyAndRejectsIncorrectKeyType() throws Exception {
        Path path = file(entry("fixture.test", key).replace("ssh-rsa", "ssh-ed25519"));
        byte[] original = Files.readAllBytes(path);
        var repository = VerifiedHostKeys.configure(new JSch(), path.toString());
        assertThat(repository.check("fixture.test", key)).isNotEqualTo(HostKeyRepository.OK);
        assertThatThrownBy(() -> repository.add(new HostKey("fixture.test", key), null))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> repository.remove("fixture.test", null))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(Files.readAllBytes(path)).isEqualTo(original);
    }

    @Test
    void missingTrustStoreFailsWithoutPathOrUnderlyingException() {
        String path = directory.resolve("private-location").toString();
        assertThatThrownBy(() -> VerifiedHostKeys.configure(new JSch(), path))
                .isInstanceOf(SshHostKeyException.class)
                .hasNoCause()
                .hasMessageNotContaining(path);
    }

    @Test
    void missingTrustStoreCannotCreateTransport() throws Exception {
        var manager = new SshSessionManager(new SambaProperties(
                new SambaProperties.Ssh(
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        3,
                        directory.resolve("missing").toString()),
                null,
                null,
                null));
        try (var construction = mockConstruction(JSch.class)) {
            assertThatThrownBy(() -> manager.createSession(
                            "session",
                            new ConnectionRequestDto("fixture.test", 22, "admin", "password", "synthetic", null, null)))
                    .isInstanceOfSatisfying(SshHostKeyException.class, error -> assertThat(error.code())
                            .isEqualTo("SSH_HOST_KEY_UNAVAILABLE"));
            verify(construction.constructed().getFirst(), never()).getSession(anyString(), anyString(), anyInt());
        }
    }

    @Test
    void transportFailureDoesNotLeakDetailsOrRetainPartialSession() throws Exception {
        Path path = file(entry("fixture.test", key));
        Session session = mock(Session.class);
        JSch parser = new JSch();
        parser.setKnownHosts(path.toString());
        var known = parser.getHostKeyRepository();
        var manager = new SshSessionManager(new SambaProperties(
                new SambaProperties.Ssh(
                        Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), 3, path.toString()),
                null,
                null,
                null));
        try (var construction = mockConstruction(JSch.class, (jsch, context) -> {
            when(jsch.getHostKeyRepository()).thenReturn(known);
            when(jsch.getSession("admin", "fixture.test", 22)).thenReturn(session);
        })) {
            doThrow(new JSchException("synthetic-sensitive-connection-details"))
                    .when(session)
                    .connect(anyInt());
            assertThatThrownBy(() -> manager.createSession(
                            "session",
                            new ConnectionRequestDto("fixture.test", 22, "admin", "password", "synthetic", null, null)))
                    .isInstanceOf(mari.samba.core.SambaCommandException.class)
                    .hasNoCause()
                    .hasMessage("SSH connection failed. Check the connection details and credentials.");
            assertThatThrownBy(() -> manager.getSession("session")).isInstanceOf(SshSessionExpiredException.class);
            verify(session).disconnect();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void untrustedKeyClosesConnectionAndDoesNotPublishSession(boolean changed) throws Exception {
        Path path = file(changed ? entry("fixture.test", differentKey) : "");
        Session session = mock(Session.class);
        var manager = new SshSessionManager(new SambaProperties(
                new SambaProperties.Ssh(
                        Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), 3, path.toString()),
                null,
                null,
                null));
        JSch parser = new JSch();
        parser.setKnownHosts(path.toString());
        var known = parser.getHostKeyRepository();
        var verifier = new java.util.concurrent.atomic.AtomicReference<HostKeyRepository>();
        try (var construction = mockConstruction(JSch.class, (jsch, context) -> {
            when(jsch.getHostKeyRepository()).thenReturn(known);
            when(jsch.getSession("admin", "fixture.test", 22)).thenReturn(session);
            doAnswer(call -> {
                        verifier.set(call.getArgument(0));
                        return null;
                    })
                    .when(jsch)
                    .setHostKeyRepository(any());
        })) {
            doAnswer(call -> {
                        if (verifier.get().check("fixture.test", key) != HostKeyRepository.OK) {
                            throw new JSchException("synthetic-sensitive-transport-failure");
                        }
                        return null;
                    })
                    .when(session)
                    .connect(anyInt());
            assertThatThrownBy(() -> manager.createSession(
                            "session",
                            new ConnectionRequestDto("fixture.test", 22, "admin", "password", "synthetic", null, null)))
                    .isInstanceOfSatisfying(SshHostKeyException.class, error -> {
                        assertThat(error.code()).isEqualTo(changed ? "SSH_HOST_KEY_CHANGED" : "SSH_HOST_KEY_UNKNOWN");
                        assertThat(error.fingerprint()).startsWith("SHA256:");
                    })
                    .hasNoCause()
                    .hasMessageNotContaining("synthetic-sensitive");
            assertThatThrownBy(() -> manager.getSession("session")).isInstanceOf(SshSessionExpiredException.class);
            verify(session).disconnect();
            verify(session).setConfig("StrictHostKeyChecking", "yes");
            verify(session, never()).openChannel(anyString());
        }
    }
}
