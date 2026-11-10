package mari.samba.monitoring;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mari.samba.core.SambaCommandException;
import mari.samba.core.SshSessionExpiredException;
import mari.samba.infra.CommandExecutor;
import mari.samba.infra.LinuxCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SambaMonitoringService {

    private static final Logger log = LoggerFactory.getLogger(SambaMonitoringService.class);

    private final CommandExecutor commandExecutor;

    public SambaMonitoringService(CommandExecutor commandExecutor) {
        this.commandExecutor = commandExecutor;
    }

    public boolean isServiceRunning(String sessionId) {
        try {
            var result = commandExecutor.executeCommand(sessionId, LinuxCommands.systemctl("is-active", "smbd"));
            if (result != null && result.stdout() != null) {
                String state = result.stdout().trim();
                if (result.isSuccess() && state.equals("active")) return true;
                if ((result.exitCode() == 0 || result.exitCode() == 3)
                        && (state.equals("inactive") || state.equals("failed"))) return false;
            }
            throw new SambaCommandException("Samba service status is unavailable.");
        } catch (SshSessionExpiredException e) {
            throw e;
        } catch (Exception ignored) {
            throw new SambaCommandException("Samba service status is unavailable.");
        }
    }

    public List<Map<String, String>> getActiveConnections(String sessionId) {
        List<Map<String, String>> connections = new ArrayList<>();
        try {
            String output = commandExecutor.execute(sessionId, LinuxCommands.smbstatus("-b"));
            if (output == null || output.isBlank()) {
                return connections;
            }
            String[] lines = output.split("\\r?\\n");

            boolean parsingSessions = false;
            int pidIdx = 0;
            int userIdx = 1;
            int machineIdx = 3;

            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("Samba version")) continue;

                if (line.contains("PID") && line.contains("Username")) {
                    parsingSessions = true;
                    String[] headers = line.split("\\s+");
                    for (int i = 0; i < headers.length; i++) {
                        if (headers[i].equalsIgnoreCase("PID")) pidIdx = i;
                        if (headers[i].equalsIgnoreCase("Username")) userIdx = i;
                        if (headers[i].equalsIgnoreCase("Machine")) machineIdx = i;
                    }
                    continue;
                }

                if (line.startsWith("---")) continue;
                if (line.isEmpty()) {
                    parsingSessions = false;
                    continue;
                }

                if (parsingSessions) {
                    String[] parts = line.split("\\s+");
                    if (parts.length > Math.max(userIdx, machineIdx)) {
                        Map<String, String> conn = new HashMap<>();
                        conn.put("pid", parts[pidIdx]);
                        conn.put("user", parts[userIdx]);
                        conn.put("machine", parts[machineIdx]);
                        connections.add(conn);
                    }
                }
            }
        } catch (SshSessionExpiredException e) {
            throw e;
        } catch (Exception ignored) {
            throw new SambaCommandException("Samba connections are unavailable.");
        }
        return connections;
    }

    public List<Map<String, String>> getOpenFiles(String sessionId) {
        List<Map<String, String>> openFiles = new ArrayList<>();
        try {
            String output = commandExecutor.execute(sessionId, LinuxCommands.smbstatus("-L"));
            if (output == null || output.isBlank()) {
                return openFiles;
            }
            String[] lines = output.split("\\r?\\n");

            boolean parsingFiles = false;
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("Pid") && line.contains("DenyMode")) {
                    parsingFiles = true;
                    continue;
                }
                if (line.startsWith("---")) continue;
                if (line.isEmpty() || line.startsWith("Locked files:")) continue;

                if (parsingFiles) {
                    String[] parts = line.split("\\s+");

                    if (parts.length >= 8) {
                        Map<String, String> file = new HashMap<>();
                        file.put("pid", parts[0]);
                        file.put("rw", parts[4]);

                        StringBuilder fileName = new StringBuilder();
                        for (int i = 7; i < parts.length; i++) {
                            if (parts[i].matches("Mon|Tue|Wed|Thu|Fri|Sat|Sun")) {
                                break;
                            }
                            fileName.append(parts[i]).append(" ");
                        }
                        file.put("file", fileName.toString().trim());
                        openFiles.add(file);
                    }
                }
            }
        } catch (SshSessionExpiredException e) {
            throw e;
        } catch (Exception ignored) {
            throw new SambaCommandException("Samba open files are unavailable.");
        }
        return openFiles;
    }

    public void controlService(String sessionId, String action) {
        if (!action.matches("^(restart|start|stop)$")) {
            throw new IllegalArgumentException("Invalid service control action: " + action);
        }
        commandExecutor.execute(sessionId, LinuxCommands.systemctl(action, "smbd"));
        log.info("Executed smbd action: {}", action);
    }

    public void killSession(String sessionId, String pid) {
        if (!pid.matches("^\\d+$")) {
            throw new IllegalArgumentException("Invalid session PID: " + pid);
        }
        commandExecutor.execute(sessionId, LinuxCommands.kill(pid));
        log.info("Killed Samba session PID: {}", pid);
    }

    public List<String> getDiskUsage(String sessionId) {
        List<String> stats = new ArrayList<>();
        try {
            String output = commandExecutor.execute(sessionId, "df -h");
            if (output == null || output.isBlank()) {
                return stats;
            }
            String[] lines = output.trim().split("\\r?\\n");
            for (int i = 1; i < lines.length; i++) {
                String[] parts = lines[i].trim().split("\\s+");
                if (parts.length >= 6) {
                    stats.add(parts[5] + " " + parts[4] + " " + parts[1]);
                }
            }
        } catch (SshSessionExpiredException e) {
            throw e;
        } catch (Exception ignored) {
            throw new SambaCommandException("Samba disk usage is unavailable.");
        }
        return stats;
    }
}
