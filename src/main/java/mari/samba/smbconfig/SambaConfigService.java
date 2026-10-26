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

    /** Logger for this service. */
    private static final Logger log = LoggerFactory.getLogger(SambaConfigService.class);

    /** Executor used for dispatching bash commands to the remote server via SSH. */
    private final CommandExecutor commandExecutor;

    /** Utility parser for reading and modifying {@code smb.conf} content in memory. */
    private final SmbConfParser smbConfParser;

    /** The absolute path to the main Samba configuration file on the remote server. */
    private final String configPath;

    /** The absolute directory path where configuration backups are stored on the remote server. */
    private final String backupDir;

    /**
     * Constructs a new {@code SambaConfigService} with the required dependencies and properties.
     *
     * @param commandExecutor the command executor for handling remote Linux command execution
     * @param smbConfParser the utility used to parse and build sections of the configuration
     * @param properties the application properties containing paths for configuration and backups
     */
    public SambaConfigService(
            CommandExecutor commandExecutor, SmbConfParser smbConfParser, SambaProperties properties) {
        this.commandExecutor = commandExecutor;
        this.smbConfParser = smbConfParser;
        this.configPath = properties.paths().config();
        this.backupDir = properties.paths().backups();
    }

    /**
     * Retrieves the raw content of the Samba configuration file from the remote server.
     *
     * @param sessionId the active SSH session identifier
     * @return the raw string content of the configuration file
     */
    public String getSmbConfContent(@NonNull String sessionId) {
        return commandExecutor.execute(sessionId, LinuxCommands.cat(configPath));
    }

    /**
     * Parses the raw content of the configuration file to extract a list of defined Samba shares.
     *
     * @param content the raw string content of the configuration file
     * @return a list of {@link SambaShare} objects representing the parsed shares
     */
    public List<SambaShare> parseShares(@NonNull String content) {
        return smbConfParser.parseShares(content);
    }

    /**
     * Builds a string representing a new share section formatted for {@code smb.conf} based on the
     * provided creation data.
     *
     * @param dto the data transfer object containing the details of the share to create
     * @return a properly formatted string section for the new share
     */
    public String buildShareSection(@NonNull SambaShareCreateDto dto) {
        return smbConfParser.buildShareSection(dto);
    }

    /**
     * Removes a specific share section from the provided raw configuration content.
     *
     * @param content the current raw string content of the configuration file
     * @param shareName the name of the share section to remove
     * @return the updated configuration string with the specified share section removed
     */
    public String removeShareSection(@NonNull String content, @NonNull String shareName) {
        return smbConfParser.removeSection(content, shareName);
    }

    /**
     * Creates a backup of the current Samba configuration file on the remote server. The backup file
     * includes a timestamp, and old backups (beyond a limit of 10) are automatically cleaned up.
     *
     * @param sessionId the active SSH session identifier
     */
    public void createBackup(@NonNull String sessionId) {
        commandExecutor.execute(sessionId, LinuxCommands.mkdir(backupDir));
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String backupFile = backupDir + "/smb.conf.backup_" + timestamp;

        commandExecutor.execute(sessionId, LinuxCommands.createBackup(configPath, backupFile));
        commandExecutor.execute(sessionId, LinuxCommands.cleanupOldBackups(backupDir, 10));
        log.info("Created configuration backup");
    }

    /**
     * Lists all available configuration backups on the remote server.
     *
     * @param sessionId the active SSH session identifier
     * @return a list of {@link SambaBackupDto} objects detailing each available backup
     */
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
     * Restores the Samba configuration from a specific backup file. This action also creates a safety
     * backup of the current state and restarts the {@code smbd} service. The selected backup must
     * pass {@code testparm} validation before any configuration replacement or service restart.
     *
     * @param sessionId the active SSH session identifier
     * @param filename the exact name of the backup file to restore
     * @throws IllegalArgumentException if the provided backup filename does not match the expected
     *     format
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
     * Replaces the current configuration file with new content. The new content is first written to a
     * temporary file and checked for syntax errors using {@code testparm}. If valid, it overwrites
     * the main configuration and restarts the {@code smbd} service.
     *
     * @param sessionId the active SSH session identifier
     * @param content the new raw configuration content to apply
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

    /**
     * Retrieves the global configuration settings from the current Samba configuration.
     *
     * @param sessionId the active SSH session identifier
     * @return a {@link SambaGlobalConfigDto} object containing the parsed global settings
     */
    public SambaGlobalConfigDto getGlobalConfig(@NonNull String sessionId) {
        String content = getSmbConfContent(sessionId);
        return smbConfParser.parseGlobalConfig(content);
    }

    /**
     * Updates the global configuration section with new settings. Merges the provided global
     * configuration into the existing content and applies it to the remote server.
     *
     * @param sessionId the active SSH session identifier
     * @param dto the data transfer object containing the new global configuration settings
     */
    public void updateGlobalConfig(@NonNull String sessionId, @NonNull SambaGlobalConfigDto dto) {
        String currentContent = getSmbConfContent(sessionId);
        String updatedContent = smbConfParser.updateGlobalSection(currentContent, dto);
        updateSmbConf(sessionId, updatedContent);
    }
}
