package mari.samba.core;

/** A sanitized failure raised before SSH user authentication can complete. */
public final class SshHostKeyException extends RuntimeException {
    public enum Reason {
        UNKNOWN,
        CHANGED,
        UNAVAILABLE
    }

    private final Reason reason;
    private final String fingerprint;

    public SshHostKeyException(Reason reason, String fingerprint) {
        super(
                switch (reason) {
                    case UNKNOWN -> "The SSH server key is not trusted. Contact the administrator.";
                    case CHANGED -> "The SSH server key has changed. Connection blocked; verify the server identity.";
                    case UNAVAILABLE -> "The SSH trust store is unavailable. Contact the administrator.";
                });
        this.reason = reason;
        this.fingerprint = fingerprint;
    }

    public Reason reason() {
        return reason;
    }

    public String code() {
        return "SSH_HOST_KEY_" + reason.name();
    }

    public String fingerprint() {
        return fingerprint;
    }
}
