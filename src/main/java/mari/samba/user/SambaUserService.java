package mari.samba.user;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import mari.samba.core.SambaCommandException;
import mari.samba.core.SshSessionExpiredException;
import mari.samba.infra.CommandExecutor;
import mari.samba.infra.LinuxCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service responsible for managing Samba and underlying OS users via SSH commands. Acts as a bridge
 * between the web UI and the Linux server, translating Java instructions into bash execution
 * sequences. Ensures both system and Samba user accounts are kept in sync.
 */
@Service
public class SambaUserService {

    private static final Logger log = LoggerFactory.getLogger(SambaUserService.class);

    // Critical system users blocked from modification or deletion via API.
    private static final Set<String> SYSTEM_USERS_BLACKLIST = Set.of(
            "root",
            "daemon",
            "bin",
            "sys",
            "sync",
            "games",
            "man",
            "lp",
            "mail",
            "news",
            "uucp",
            "proxy",
            "www-data",
            "backup",
            "list",
            "irc",
            "gnats",
            "nobody",
            "systemd-network",
            "systemd-resolve",
            "syslog",
            "messagebus",
            "_apt",
            "lxd",
            "uuidd",
            "dnsmasq",
            "landscape",
            "pollinate",
            "sshd",
            "postgres",
            "mysql");

    private final CommandExecutor commandExecutor;

    public SambaUserService(CommandExecutor commandExecutor) {
        this.commandExecutor = commandExecutor;
    }

    private void requireNonSystemUser(String username) {
        if (SYSTEM_USERS_BLACKLIST.contains(username.trim().toLowerCase())) {
            throw new SecurityException("Security Policy: Cannot modify core system users via Samba API.");
        }
    }

    public List<SambaUser> getAllUsers(String sessionId) {
        String output = commandExecutor.execute(sessionId, LinuxCommands.listSambaUsers());

        if (output == null || output.isBlank()) {
            return List.of();
        }

        return Arrays.stream(output.split("\\r?\\n"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .map(line -> {
                    String[] parts = line.split(":");
                    if (parts.length >= 2) {
                        String username = parts[0].trim();
                        String fullName = (parts.length > 2 && !parts[2].trim().isEmpty()) ? parts[2].trim() : "-";
                        return new SambaUser(username, fullName, true, null, null);
                    }
                    return null;
                })
                .filter(u -> u != null)
                .toList();
    }

    /** Returns account names from the OS user database without exposing passwd fields. */
    public List<String> getSystemUsernames(String sessionId) {
        String output = commandExecutor.execute(sessionId, LinuxCommands.listSystemUsers());
        if (output == null || output.isBlank()) {
            return List.of();
        }
        return output.lines()
                .map(line -> line.split(":", -1))
                .filter(parts -> parts.length == 7 && !parts[0].isBlank())
                .map(parts -> parts[0])
                .distinct()
                .sorted()
                .toList();
    }

    /** Creates a new user in both the Linux OS and Samba, updating passwords and enabling the account. */
    public void createUser(String sessionId, String username, String password, String fullName) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Password cannot be empty.");
        }
        if (password.contains("\n") || password.contains("\r")) {
            throw new IllegalArgumentException("Password cannot contain newline characters.");
        }
        requireNonSystemUser(username);
        String cleanUsername = username.trim();
        String cleanPassword = password.trim();
        String comment = (fullName != null && !fullName.isBlank()) ? fullName.trim() : cleanUsername;

        log.info("Creating system and Samba user '{}'", cleanUsername);
        commandExecutor.execute(sessionId, LinuxCommands.addSystemUserWithHome(cleanUsername, comment));
        commandExecutor.execute(sessionId, LinuxCommands.chpasswd(), cleanUsername + ":" + cleanPassword + "\n");
        commandExecutor.execute(
                sessionId, LinuxCommands.addSambaUser(cleanUsername), cleanPassword + "\n" + cleanPassword + "\n");
        commandExecutor.execute(sessionId, LinuxCommands.enableSambaUser(cleanUsername));
        log.info("Successfully created and enabled Samba user '{}'", cleanUsername);
    }

    /** Deletes Samba and Linux accounts while preserving their files and ownership. */
    public void deleteUser(String sessionId, String username) {
        requireNonSystemUser(username);
        log.info("Deleting Samba and system user '{}'", username);
        commandExecutor.execute(sessionId, LinuxCommands.deleteSambaUser(username));
        try {
            commandExecutor.execute(sessionId, LinuxCommands.deleteSystemUser(username));
        } catch (SshSessionExpiredException e) {
            throw new SshSessionExpiredException(
                    "Samba account was deleted, but the SSH session expired before Linux account deletion completed. "
                            + "Log in again and check both account databases; no rollback was performed.");
        } catch (SambaCommandException e) {
            throw new SambaCommandException(
                    "Samba account was deleted, but Linux account deletion failed. "
                            + "Check both account databases before retrying; no rollback was performed.",
                    e);
        }
        log.info("Successfully deleted user '{}'", username);
    }

    /** Synchronizes and updates the password for both the underlying OS and the Samba service. */
    public void changePassword(String sessionId, String username, String newPassword) {
        if (newPassword == null || newPassword.isBlank()) {
            throw new IllegalArgumentException("Password cannot be empty.");
        }
        if (newPassword.contains("\n") || newPassword.contains("\r")) {
            throw new IllegalArgumentException("Password cannot contain newline characters.");
        }
        requireNonSystemUser(username);
        String cleanUsername = username.trim();
        String cleanPassword = newPassword.trim();
        log.info("Rotating password for user '{}'", cleanUsername);
        commandExecutor.execute(sessionId, LinuxCommands.chpasswd(), cleanUsername + ":" + cleanPassword + "\n");
        commandExecutor.execute(
                sessionId,
                LinuxCommands.changeSambaPassword(cleanUsername),
                cleanPassword + "\n" + cleanPassword + "\n");
        log.info("Successfully rotated password for user '{}'", cleanUsername);
    }

    public boolean userExists(String sessionId, String username) {
        try {
            commandExecutor.execute(sessionId, LinuxCommands.checkUserExists(username));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
