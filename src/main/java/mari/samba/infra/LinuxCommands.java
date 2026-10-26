package mari.samba.infra;

import java.util.regex.Pattern;

/**
 * Utility for generating safe Linux commands. Enforces strict input validation to prevent Shell
 * Injection and Path Traversal. Includes defense-in-depth measures like "--" to prevent
 * Argument/Option Injection.
 */
public final class LinuxCommands {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z_][a-z0-9_.-]{0,31}$");
    private static final Pattern GROUP_PATTERN = Pattern.compile("^[a-z_][a-z0-9_.-]{0,31}$");
    private static final Pattern DANGEROUS_CHARS_PATTERN = Pattern.compile("[|&;\\$<>!\\n\\r`]");

    private LinuxCommands() {}

    /**
     * Escapes a string for safe usage in bash within single quotes. Rejects newlines to prevent
     * multi-line execution vulnerabilities.
     */
    public static String escape(String arg) {
        if (arg == null) return "''";
        if (arg.contains("\n") || arg.contains("\r")) {
            throw new SecurityException("Newlines are strictly forbidden in shell arguments.");
        }
        return "'" + arg.replace("'", "'\\''") + "'";
    }

    // --- Security Guard Methods ---

    private static String requireValidUsername(String username) {
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            throw new SecurityException("Invalid username format: possible injection detected.");
        }
        return username;
    }

    private static String requireValidGroupName(String groupName) {
        if (groupName == null || !GROUP_PATTERN.matcher(groupName).matches()) {
            throw new SecurityException("Invalid group name format: possible injection detected.");
        }
        return groupName;
    }

    private static String requireValidPath(String path) {
        if (path == null || path.isBlank()) {
            throw new SecurityException("Path cannot be empty.");
        }
        if (path.contains("..")) {
            throw new SecurityException("Path traversal attempt detected (..).");
        }
        if (DANGEROUS_CHARS_PATTERN.matcher(path).find()) {
            throw new SecurityException("Path contains forbidden shell characters.");
        }
        if (!path.startsWith("/")) {
            throw new SecurityException("Path must be absolute (start with /).");
        }
        return path;
    }

    private static String requireValidAction(String action) {
        if (action == null || !action.matches("^(start|stop|restart|reload|status|is-active)$")) {
            throw new SecurityException("Invalid systemd action requested.");
        }
        return action;
    }

    private static String requireValidPid(String pid) {
        if (pid == null || !pid.matches("^\\d+$")) {
            throw new SecurityException("PID must be strictly numeric.");
        }
        return pid;
    }

    // ==========================================
    // File and Directory Operations (Safe)
    // ==========================================

    public static String cat(String path) {
        return String.format("cat -- %s", escape(requireValidPath(path)));
    }

    public static String sudoCat(String path) {
        return String.format("sudo cat -- %s", escape(requireValidPath(path)));
    }

    public static String base64(String path) {
        return String.format("sudo base64 -- %s", escape(requireValidPath(path)));
    }

    public static String writeToFileStdin(String targetPath) {
        return String.format("cat > %s", escape(requireValidPath(targetPath)));
    }

    public static String uploadBase64Stdin(String targetPath) {
        return String.format("base64 -d | sudo tee -- %s > /dev/null", escape(requireValidPath(targetPath)));
    }

    public static String du(String path) {
        String safePath = escape(requireValidPath(path));
        return String.format(
                "if [ -e %s ]; then du -sh -- %s 2>/dev/null || sudo -n du -sh -- %s 2>/dev/null || echo '0 B'; else echo '0 B'; fi",
                safePath, safePath, safePath);
    }

    public static String df(String path) {
        return String.format("sudo df -kP -- %s", escape(requireValidPath(path)));
    }

    public static String findDirectories(String path) {
        return String.format(
                "sudo find -L %s -mindepth 1 -maxdepth 1 ! -name '.*' -printf '%%y|%%s|%%p\\n' 2>/dev/null | sort",
                escape(requireValidPath(path)));
    }

    public static String mkdir(String path) {
        return String.format("sudo mkdir -p -- %s", escape(requireValidPath(path)));
    }

    public static String chmod(String permissions, String path) {
        if (!permissions.matches("^[0-7]{3,4}$")) {
            throw new SecurityException("Invalid chmod permissions format.");
        }
        return String.format("sudo chmod %s -- %s", permissions, escape(requireValidPath(path)));
    }

    public static String chownRecursive(String owner, String path) {
        if (!owner.matches("^([a-z_][a-z0-9_.-]*(:[a-z_][a-z0-9_.-]*)?|:[a-z_][a-z0-9_.-]+)$")) {
            throw new SecurityException("Invalid chown owner profile.");
        }
        return String.format("sudo chown -R %s -- %s", escape(owner), escape(requireValidPath(path)));
    }

    public static String copy(String source, String destination) {
        return String.format(
                "sudo cp -- %s %s", escape(requireValidPath(source)), escape(requireValidPath(destination)));
    }

    /** Publishes a completed backup without replacing an existing directory entry. */
    public static String createBackup(String source, String basePath) {
        String safeSource = escape(requireValidPath(source));
        String safeBase = escape(requireValidPath(basePath));
        String script = String.join(
                " ",
                "staged=$(mktemp "
                        + escape(requireValidPath(
                                basePath.substring(0, basePath.lastIndexOf('/')) + "/.smb.conf.staging.XXXXXX"))
                        + ") || exit 1;",
                "trap 'rm -f -- \"$staged\"' EXIT;",
                "cp -- " + safeSource + " \"$staged\" || exit 1;",
                "base=" + safeBase + ";",
                "for ((attempt=0; attempt<100; attempt++)); do",
                "candidate=$base;",
                "if ((attempt>0)); then candidate=${base}_$attempt; fi;",
                "if [ -e \"$candidate\" ] || [ -L \"$candidate\" ]; then continue; fi;",
                "ln -T -- \"$staged\" \"$candidate\" || exit 1;",
                "printf '%s\\n' \"$candidate\"; exit 0;",
                "done;",
                "echo 'Backup name attempts exhausted.' >&2; exit 1;");
        return "sudo bash -c " + escape(script);
    }

    public static String copyRecursive(String source, String destination) {
        return String.format(
                "sudo cp -r -- %s %s", escape(requireValidPath(source)), escape(requireValidPath(destination)));
    }

    public static String move(String source, String destination) {
        return String.format(
                "sudo mv -- %s %s", escape(requireValidPath(source)), escape(requireValidPath(destination)));
    }

    public static String removeRecursive(String path) {
        return String.format("sudo rm -rf -- %s", escape(requireValidPath(path)));
    }

    public static String listBackupsDetailed(String backupDir) {
        return String.format(
                "ls -lh --time-style=\"+%%Y-%%m-%%d %%H:%%M:%%S\" -- %s/smb.conf.backup_* 2>/dev/null",
                escape(requireValidPath(backupDir)));
    }

    public static String cleanupOldBackups(String backupDir, int keepCount) {
        if (keepCount < 1 || keepCount > 100) throw new SecurityException("Invalid backup keep count.");
        String safeDirectory = escape(requireValidPath(backupDir));
        String script = String.join(
                " ",
                "export LC_ALL=C;",
                "backup_list=$(mktemp) || exit 1;",
                "trap 'rm -f -- \"$backup_list\"' EXIT;",
                "find " + safeDirectory + " -maxdepth 1 -type f -name 'smb.conf.backup_*' -printf '%T@ %p\\0'",
                "| sort -z -nr > \"$backup_list\" || exit 1;",
                "tail -z -n +" + (keepCount + 1) + " -- \"$backup_list\"",
                "| cut -z -d ' ' -f 2- | xargs -0 -r sudo rm --");
        return "bash -o pipefail -c " + escape(script);
    }

    // ==========================================
    // Logs and Config Verification
    // ==========================================

    public static String tail(String filePath, int lines) {
        if (lines < 1 || lines > 5000) throw new SecurityException("Invalid lines requested.");
        return String.format("sudo tail -n %d -- %s", lines, escape(requireValidPath(filePath)));
    }

    public static String testparmSilent(String filePath) {
        return String.format("testparm -s -- %s > /dev/null", escape(requireValidPath(filePath)));
    }

    public static String testparmDump() {
        return "testparm -sv 2>/dev/null";
    }

    // ==========================================
    // Systemd Services
    // ==========================================

    public static String systemctl(String action, String service) {
        if (!service.matches("^[a-zA-Z0-9_-]+$")) throw new SecurityException("Invalid service name.");
        return String.format("sudo systemctl %s -- %s", escape(requireValidAction(action)), escape(service));
    }

    // ==========================================
    // Samba Users (pdbedit / smbpasswd)
    // ==========================================

    public static String listSambaUsers() {
        return "sudo pdbedit -L";
    }

    public static String listSystemUsers() {
        return "getent passwd";
    }

    public static String checkUserExists(String username) {
        return String.format("id -- %s", escape(requireValidUsername(username)));
    }

    public static String addSystemUserWithHome(String username, String comment) {
        return String.format(
                "sudo useradd -m -s /bin/bash -c %s -- %s", escape(comment), escape(requireValidUsername(username)));
    }

    public static String deleteSystemUser(String username) {
        return String.format("sudo userdel -r -- %s", escape(requireValidUsername(username)));
    }

    public static String chpasswd() {
        return "sudo chpasswd";
    }

    public static String addSambaUser(String username) {
        return String.format("sudo smbpasswd -s -a %s", escape(requireValidUsername(username)));
    }

    public static String enableSambaUser(String username) {
        return String.format("sudo smbpasswd -e %s", escape(requireValidUsername(username)));
    }

    public static String changeSambaPassword(String username) {
        return String.format("sudo smbpasswd -s %s", escape(requireValidUsername(username)));
    }

    public static String deleteSambaUser(String username) {
        return String.format("sudo smbpasswd -x %s", escape(requireValidUsername(username)));
    }

    // ==========================================
    // Linux / Samba Groups (groupadd / groupdel / gpasswd / getent)
    // ==========================================

    public static String getentGroup() {
        return "getent group";
    }

    public static String addGroup(String groupName) {
        return String.format("sudo groupadd %s", escape(requireValidGroupName(groupName)));
    }

    public static String deleteGroup(String groupName) {
        return String.format("sudo groupdel %s", escape(requireValidGroupName(groupName)));
    }

    public static String addUserToGroup(String username, String groupName) {
        return String.format(
                "sudo gpasswd -a %s %s",
                escape(requireValidUsername(username)), escape(requireValidGroupName(groupName)));
    }

    public static String removeUserFromGroup(String username, String groupName) {
        return String.format(
                "sudo gpasswd -d %s %s",
                escape(requireValidUsername(username)), escape(requireValidGroupName(groupName)));
    }

    // ==========================================
    // Samba Monitoring (SMBSTATUS)
    // ==========================================

    public static String smbstatus(String flag) {
        if (!flag.matches("^-[a-zA-Z]$")) throw new SecurityException("Invalid smbstatus flag.");
        return "sudo smbstatus " + flag;
    }

    public static String kill(String pid) {
        return "sudo kill -9 " + requireValidPid(pid);
    }

    // ==========================================
    // Active Directory Integration
    // ==========================================

    public static String netAdsJoin(String username, String password) {
        return String.format("sudo net ads join -U %s", escape(username + "%" + password));
    }

    public static String netAdsLeave(String username, String password) {
        return String.format("sudo net ads leave -U %s", escape(username + "%" + password));
    }

    public static String netAdsTestJoin() {
        return "sudo net ads testjoin";
    }

    public static String wbinfoUsers() {
        return "wbinfo -u";
    }

    public static String wbinfoGroups() {
        return "wbinfo -g";
    }

    public static String writeKrb5Conf() {
        return "sudo tee /etc/krb5.conf > /dev/null";
    }
}
