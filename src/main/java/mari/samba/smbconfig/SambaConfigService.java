package mari.samba.smbconfig;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import mari.samba.config.SambaProperties;
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
        List<SambaBackupDto> backups = new ArrayList<>();
        try {
            String cmd = LinuxCommands.listBackupsDetailed(backupDir);
            String output = commandExecutor.execute(sessionId, cmd);
            String[] lines = output.split("\\r?\\n");

            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("total")) continue;

                String[] parts = line.split("\\s+");
                if (parts.length >= 7) {
                    String size = parts[4];
                    String date = parts[5] + " " + parts[6];
                    String fullPath = parts[parts.length - 1];
                    String filename = fullPath.substring(fullPath.lastIndexOf('/') + 1);

                    backups.add(new SambaBackupDto(filename, date, size));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to list configuration backups: {}", e.getMessage());
        }
        return backups;
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
        String tempFile = "/tmp/smb.conf.tmp";

        createBackup(sessionId);
        commandExecutor.execute(sessionId, LinuxCommands.writeToFileStdin(tempFile), content);
        commandExecutor.execute(sessionId, LinuxCommands.testparmSilent(tempFile));
        commandExecutor.execute(sessionId, LinuxCommands.move(tempFile, configPath));
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
