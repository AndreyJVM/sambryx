package mari.samba.smbconfig;

import java.text.SimpleDateFormat;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import mari.samba.config.SambaProperties;
import mari.samba.core.SambaCommandException;
import mari.samba.infra.CommandExecutor;
import mari.samba.infra.LinuxCommands;
import mari.samba.infra.SmbConfParser;
import mari.samba.share.SambaShare;
import mari.samba.share.SambaShareCreateDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;

/**
 * Service responsible for managing the Samba configuration file ({@code smb.conf}). It provides
 * operations to read, parse, update, backup, and restore the configuration via remote Linux
 * commands.
 */
@Service
public class SambaConfigService {

    private static final Logger log = LoggerFactory.getLogger(SambaConfigService.class);

    private final BackupComparisonReader backupComparison = new BackupComparisonReader();
    private final CommandExecutor commandExecutor;
    private final SmbConfParser smbConfParser;
    private final String configPath;
    private final String backupDir;

    public SambaConfigService(
            CommandExecutor commandExecutor, SmbConfParser smbConfParser, SambaProperties properties) {
        this.commandExecutor = commandExecutor;
        this.smbConfParser = smbConfParser;
        this.configPath = properties.paths().config();
        this.backupDir = properties.paths().backups();
    }

    public String getSmbConfContent(@NonNull String sessionId) {
        return commandExecutor.execute(sessionId, LinuxCommands.cat(configPath));
    }

    public List<SambaShare> parseShares(@NonNull String content) {
        return smbConfParser.parseShares(content);
    }

    public String buildShareSection(@NonNull SambaShareCreateDto dto) {
        return smbConfParser.buildShareSection(dto);
    }

    public String removeShareSection(@NonNull String content, @NonNull String shareName) {
        return smbConfParser.removeSection(content, shareName);
    }

    /** Creates a backup of smb.conf and retains up to 10 latest backups. */
    public void createBackup(@NonNull String sessionId) {
        commandExecutor.execute(sessionId, LinuxCommands.mkdir(backupDir));
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String backupFile = backupDir + "/smb.conf.backup_" + timestamp;

        commandExecutor.execute(sessionId, LinuxCommands.createBackup(configPath, backupFile));
        commandExecutor.execute(sessionId, LinuxCommands.cleanupOldBackups(backupDir, 10));
        log.info("Created configuration backup");
    }

    public List<SambaBackupDto> listBackups(@NonNull String sessionId) {
        try {
            String output = commandExecutor.execute(sessionId, LinuxCommands.listBackupsDetailed(backupDir));
            if (output == null) throw new IllegalArgumentException("Missing backup listing result.");
            if (output.isEmpty()) return List.of();
            if (output.isBlank()) throw new IllegalArgumentException("Invalid backup listing result.");
            List<SambaBackupDto> backups = new ArrayList<>();
            for (String line : output.split("\\r?\\n")) {
                backups.add(parseBackupEntry(line));
            }
            return List.copyOf(backups);
        } catch (SambaCommandException | IllegalArgumentException | DateTimeException e) {
            log.warn("Configuration backup listing failed");
            throw new SambaCommandException("Configuration backup list is unavailable.");
        }
    }

    private SambaBackupDto parseBackupEntry(String line) {
        String[] parts = line.split("\\s+", 8);
        if (parts.length != 8
                || !parts[0].matches("-[rwxStTs-]{9}[.+]?")
                || !parts[1].matches("[0-9]+")
                || !parts[4].matches("[0-9]+(?:\\.[0-9]+)?[KMGTPEZY]?")) {
            throw new IllegalArgumentException("Invalid backup listing entry.");
        }
        String filename = parts[7].substring(parts[7].lastIndexOf('/') + 1);
        if (!filename.matches("smb\\.conf\\.backup_\\d{8}_\\d{6}(?:_[1-9][0-9]?)?")
                || !parts[7].equals(backupDir + "/" + filename)) {
            throw new IllegalArgumentException("Invalid backup listing filename.");
        }
        String date = parts[5] + " " + parts[6];
        LocalDateTime.parse(
                date, DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT));
        return new SambaBackupDto(filename, date, parts[4]);
    }

    public BackupComparison compareBackup(@NonNull String sessionId, @NonNull String filename) {
        if (!filename.matches("^smb\\.conf\\.backup_\\d{8}_\\d{6}(?:_[1-9][0-9]?)?$")) {
            throw new IllegalArgumentException("Invalid backup filename format.");
        }
        return backupComparison.compare(commandExecutor, sessionId, configPath, backupDir + "/" + filename, filename);
    }

    /**
     * Restores the Samba configuration from a specific backup file.
     * The backup must pass {@code testparm} validation before replacing configuration and restarting {@code smbd}.
     */
    public void restoreBackup(@NonNull String sessionId, @NonNull String filename) {
        if (!filename.matches("^smb\\.conf\\.backup_\\d{8}_\\d{6}(?:_[1-9][0-9]?)?$")) {
            throw new IllegalArgumentException("Invalid backup filename format.");
        }

        String backupFile = backupDir + "/" + filename;
        commandExecutor.execute(sessionId, LinuxCommands.testparmSilent(backupFile));
        createBackup(sessionId);

        commandExecutor.execute(sessionId, LinuxCommands.copy(backupFile, configPath));
        commandExecutor.execute(sessionId, LinuxCommands.systemctl("restart", "smbd"));
        log.info("Restored configuration from backup {} and restarted smbd", filename);
    }

    /**
     * Replaces the current configuration file with new content.
     * Syntax is checked with {@code testparm} before overwriting configuration and restarting {@code smbd}.
     */
    public void updateSmbConf(@NonNull String sessionId, @NonNull String content) {
        var permissionResult = commandExecutor.executeCommand(sessionId, LinuxCommands.configSavePermissionProbe());
        if (permissionResult == null || !permissionResult.isSuccess()) {
            throw new SambaCommandException("Review configuration save sudo permissions with the server administrator: "
                    + "mktemp, testparm and ln require noninteractive access. See docs/server-setup.md. "
                    + "No configuration changes were started.");
        }
        createBackup(sessionId);
        commandExecutor.execute(sessionId, LinuxCommands.installSmbConfStdin(configPath), content);
        commandExecutor.execute(sessionId, LinuxCommands.systemctl("restart", "smbd"));
        log.info("Updated {} and restarted smbd", configPath);
    }

    public SambaGlobalConfigDto getGlobalConfig(@NonNull String sessionId) {
        String content = getSmbConfContent(sessionId);
        return smbConfParser.parseGlobalConfig(content);
    }

    public void updateGlobalConfig(@NonNull String sessionId, @NonNull SambaGlobalConfigDto dto) {
        String currentContent = getSmbConfContent(sessionId);
        String updatedContent = smbConfParser.updateGlobalSection(currentContent, dto);
        updateSmbConf(sessionId, updatedContent);
    }
}
