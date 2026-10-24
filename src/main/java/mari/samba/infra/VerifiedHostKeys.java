package mari.samba.infra;

import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.UserInfo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import mari.samba.core.SshHostKeyException;
import mari.samba.core.SshHostKeyException.Reason;

/** Per-connection, read-only view of administrator-provisioned OpenSSH known_hosts. */
final class VerifiedHostKeys implements HostKeyRepository {
    private final HostKey[] keys;
    private SshHostKeyException rejection;

    private VerifiedHostKeys(HostKey[] keys) {
        this.keys = keys == null ? new HostKey[0] : keys.clone();
    }

    static VerifiedHostKeys configure(JSch jsch, String file) {
        try (var input = Files.newInputStream(Path.of(file))) {
            jsch.setKnownHosts(input);
            var repository = new VerifiedHostKeys(jsch.getHostKeyRepository().getHostKey());
            jsch.setHostKeyRepository(repository);
            return repository;
        } catch (IOException | JSchException | IllegalArgumentException | SecurityException e) {
            throw new SshHostKeyException(Reason.UNAVAILABLE, null);
        }
    }

    @Override
    public int check(String host, byte[] key) {
        HostKey[] matching = getHostKey(host, null);
        String encoded = Base64.getEncoder().encodeToString(key);
        String type;
        try {
            type = new HostKey(host, key).getType();
        } catch (JSchException e) {
            rejection = new SshHostKeyException(Reason.UNKNOWN, fingerprint(key));
            return NOT_INCLUDED;
        }
        // A revoked entry must never be bypassed by another entry for the same key.
        boolean revoked = Arrays.stream(matching)
                .anyMatch(item ->
                        "@revoked".equals(item.getMarker()) && item.getKey().equals(encoded));
        boolean trusted = !revoked
                && Arrays.stream(matching)
                        .anyMatch(item ->
                                (item.getMarker() == null || item.getMarker().isEmpty())
                                        && item.getType().equals(type)
                                        && item.getKey().equals(encoded));
        int result = trusted ? OK : matching.length == 0 ? NOT_INCLUDED : CHANGED;
        rejection = trusted
                ? null
                : new SshHostKeyException(result == CHANGED ? Reason.CHANGED : Reason.UNKNOWN, fingerprint(key));
        return result;
    }

    SshHostKeyException rejection() {
        return rejection;
    }

    private static String fingerprint(byte[] key) {
        try {
            return "SHA256:"
                    + Base64.getEncoder()
                            .withoutPadding()
                            .encodeToString(MessageDigest.getInstance("SHA-256").digest(key));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static boolean matches(String names, String host) {
        if (names.startsWith("|1|")) {
            String[] parts = names.split("\\|", -1);
            if (parts.length != 4) {
                return false;
            }
            try {
                Mac mac = Mac.getInstance("HmacSHA1"); // OpenSSH hashed-host format version 1.
                mac.init(new SecretKeySpec(Base64.getDecoder().decode(parts[2]), "HmacSHA1"));
                return MessageDigest.isEqual(
                        mac.doFinal(host.getBytes(StandardCharsets.UTF_8)),
                        Base64.getDecoder().decode(parts[3]));
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                return false;
            }
        }
        return Arrays.stream(names.split(",")).anyMatch(name -> name.equalsIgnoreCase(host));
    }

    @Override
    public String getKnownHostsRepositoryID() {
        return "administrator-managed";
    }

    @Override
    public HostKey[] getHostKey() {
        return keys.clone();
    }

    @Override
    public HostKey[] getHostKey(String host, String type) {
        return Arrays.stream(keys)
                .filter(key ->
                        (host == null || matches(key.getHost(), host)) && (type == null || type.equals(key.getType())))
                .toArray(HostKey[]::new);
    }

    @Override
    public void add(HostKey key, UserInfo userInfo) {
        throw new UnsupportedOperationException("Read-only trust store");
    }

    @Override
    public void remove(String host, String type) {
        throw new UnsupportedOperationException("Read-only trust store");
    }

    @Override
    public void remove(String host, String type, byte[] key) {
        throw new UnsupportedOperationException("Read-only trust store");
    }
}
