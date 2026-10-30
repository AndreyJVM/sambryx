package mari.samba.share;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import mari.samba.config.SambaProperties;
import mari.samba.core.SambaCommandException;
import mari.samba.filesystem.FileSystemService;
import mari.samba.infra.CommandExecutor;
import mari.samba.infra.CommandResult;
import mari.samba.infra.LinuxCommands;
import mari.samba.infra.SmbConfParser;
import org.springframework.stereotype.Service;

@Service
class ShareAccessService {
    private static final String USER_PATTERN = "[a-z_][a-z0-9_.-]{0,31}";
    private final SambaShareService shares;
    private final CommandExecutor executor;
    private final SmbConfParser parser;
    private final FileSystemService filesystem;
    private final String configPath;

    ShareAccessService(
            SambaShareService shares,
            CommandExecutor executor,
            SmbConfParser parser,
            FileSystemService filesystem,
            SambaProperties properties) {
        this.shares = shares;
        this.executor = executor;
        this.parser = parser;
        this.filesystem = filesystem;
        this.configPath = properties.paths().config();
    }

    ShareAccessReport check(String sessionId, String share, String username) {
        if (username == null || !username.matches(USER_PATTERN)) {
            throw new IllegalArgumentException("A local username is required.");
        }
        if (share == null
                || share.isBlank()
                || share.length() > 128
                || share.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid share name.");
        }
        var findings = new ArrayList<ShareAccessReport.Finding>();
        try {
            shares.getShareByName(sessionId, share);
        } catch (SambaCommandException e) {
            add(findings, "CONFIG", "UNKNOWN", "configUnknown", "reviewConfig");
            add(findings, "LIMITS", "UNKNOWN", "limits", "verifyClient");
            return new ShareAccessReport(share, username, List.copyOf(findings));
        }
        boolean local = localIdentity(sessionId, username, findings);
        inspectConfiguration(sessionId, share, username, local, findings);
        add(findings, "LIMITS", "UNKNOWN", "limits", "verifyClient");
        return new ShareAccessReport(share, username, List.copyOf(findings));
    }

    private void inspectConfiguration(
            String sessionId, String share, String username, boolean local, List<ShareAccessReport.Finding> findings) {
        String dump = read(sessionId, "testparm -sv -- " + LinuxCommands.escape(configPath) + " 2>/dev/null", 262144);
        var sections = parser.parseEffectiveConfig(dump);
        var settings = section(sections, share);
        if (settings.isEmpty()) {
            add(findings, "CONFIG", "UNKNOWN", "configUnknown", "reviewConfig");
            return;
        }
        if ("no".equalsIgnoreCase(settings.get("available"))) {
            add(findings, "UNAVAILABLE", "BLOCKING", "unavailable", "reviewConfig");
        }
        boolean supported = local && supportsIdentity(section(sections, "global"));
        userList(settings, "valid users", "VALID_USERS", username, supported, findings);
        userList(settings, "invalid users", "INVALID_USERS", username, supported, findings);
        inspectPath(sessionId, settings.get("path"), findings);
    }

    private static Map<String, String> section(Map<String, Map<String, String>> sections, String name) {
        return sections.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(Map.of());
    }

    private static boolean supportsIdentity(Map<String, String> global) {
        return "user".equalsIgnoreCase(global.get("security"))
                && global.getOrDefault("username map", "").isBlank()
                && global.getOrDefault("username map script", "").isBlank();
    }

    private boolean localIdentity(String sessionId, String username, List<ShareAccessReport.Finding> findings) {
        String output = read(sessionId, "getent -s files passwd " + LinuxCommands.escape(username), 4096)
                .strip();
        String[] fields = output.split(":", -1);
        boolean found = fields.length == 7
                && fields[0].equals(username)
                && fields[2].matches("[0-9]{1,10}")
                && !output.contains("\n");
        add(
                findings,
                "LOCAL_IDENTITY",
                found ? "OBSERVED" : "UNKNOWN",
                found ? "localIdentityFound" : "localIdentityUnknown",
                "reviewIdentity");
        return found;
    }

    private void userList(
            Map<String, String> settings,
            String key,
            String code,
            String username,
            boolean supported,
            List<ShareAccessReport.Finding> findings) {
        String value = settings.get(key);
        if (value == null || !supported || value.length() > 4096) {
            add(findings, code, "UNKNOWN", "restrictionUnknown", "reviewConfig");
            return;
        }
        if (value.isBlank()) {
            add(findings, code, "OBSERVED", "noListedRestriction", "verifyClient");
            return;
        }
        List<String> names = Arrays.asList(value.split("[,\\s]+"));
        if (names.stream().anyMatch(name -> !name.matches(USER_PATTERN))) {
            add(findings, code, "UNKNOWN", "restrictionUnknown", "reviewConfig");
            return;
        }
        boolean blocked = key.equals("valid users") ? !names.contains(username) : names.contains(username);
        add(
                findings,
                code,
                blocked ? "BLOCKING" : "OBSERVED",
                blocked
                        ? (key.equals("valid users") ? "notInValidUsers" : "inInvalidUsers")
                        : "literalRestrictionPassed",
                blocked ? "reviewConfig" : "verifyClient");
    }

    private void inspectPath(String sessionId, String configuredPath, List<ShareAccessReport.Finding> findings) {
        try {
            if (configuredPath == null
                    || configuredPath.contains("%")
                    || configuredPath.length() > 4096
                    || configuredPath.chars().anyMatch(Character::isISOControl)) {
                throw new SecurityException("Unsupported configured path.");
            }
            if (Arrays.stream(configuredPath.split("/")).anyMatch(part -> part.equals(".") || part.equals(".."))) {
                throw new SecurityException("Ambiguous configured path.");
            }
            filesystem.requireAllowedPath(Path.of(configuredPath).normalize().toString());
            String resolved = read(sessionId, "realpath -e -- " + LinuxCommands.escape(configuredPath), 4096);
            if (resolved.endsWith("\n")) resolved = resolved.substring(0, resolved.length() - 1);
            if (!resolved.startsWith("/") || resolved.chars().anyMatch(Character::isISOControl)) {
                throw new SecurityException("Unresolved configured path.");
            }
            if (!Path.of(resolved).normalize().toString().equals(resolved)) {
                throw new SecurityException("Noncanonical resolved path.");
            }
            filesystem.requireAllowedPath(resolved);
            String type = read(sessionId, "LC_ALL=C stat -L -c '%F' -- " + LinuxCommands.escape(resolved), 128)
                    .strip();
            if (type.equals("directory")) {
                add(findings, "DIRECTORY", "OBSERVED", "directoryFound", "reviewPermissions");
            } else if (List.of(
                            "regular file",
                            "regular empty file",
                            "fifo",
                            "socket",
                            "block special file",
                            "character special file")
                    .contains(type)) {
                add(findings, "NOT_DIRECTORY", "BLOCKING", "notDirectory", "reviewPath");
            } else {
                add(findings, "PATH", "UNKNOWN", "pathUnknown", "reviewPath");
            }
        } catch (IllegalArgumentException | SecurityException e) {
            add(findings, "PATH", "UNKNOWN", "pathUnknown", "reviewPath");
        }
    }

    private String read(String sessionId, String command, int limit) {
        try {
            CommandResult result = executor.executeCommand(sessionId, command);
            return result != null
                            && result.isSuccess()
                            && result.stdout() != null
                            && result.stdout().length() <= limit
                    ? result.stdout()
                    : "";
        } catch (SambaCommandException e) {
            return "";
        }
    }

    private static void add(
            List<ShareAccessReport.Finding> findings, String code, String status, String evidence, String nextAction) {
        findings.add(new ShareAccessReport.Finding(code, status, evidence, nextAction));
    }
}
