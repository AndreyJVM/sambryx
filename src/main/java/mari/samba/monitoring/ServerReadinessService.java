package mari.samba.monitoring;

import java.util.ArrayList;
import java.util.List;
import mari.samba.config.SambaProperties;
import mari.samba.core.SambaCommandException;
import mari.samba.infra.CommandExecutor;
import mari.samba.infra.LinuxCommands;
import org.springframework.stereotype.Service;

@Service
public class ServerReadinessService {
    private static final String DISCARD_OUTPUT = " > /dev/null 2>&1";
    private final CommandExecutor executor;
    private final SambaProperties properties;

    public ServerReadinessService(CommandExecutor executor, SambaProperties properties) {
        this.executor = executor;
        this.properties = properties;
    }

    public ReadinessReport inspect(String sessionId) {
        var checks = new ArrayList<ReadinessReport.Check>();
        boolean available = true;
        for (var probe : probes()) {
            var status = ReadinessReport.Status.UNKNOWN;
            String reason = "unavailable";
            if (available) {
                try {
                    var result = executor.executeCommand(sessionId, probe.command() + DISCARD_OUTPUT);
                    if (result != null && result.exitCode() >= 0 && result.exitCode() <= 255) {
                        status = result.isSuccess() ? ReadinessReport.Status.PASSED : ReadinessReport.Status.FAILED;
                        reason = result.isSuccess() ? "confirmed" : "rejected";
                    } else available = false;
                } catch (SambaCommandException e) {
                    available = false;
                }
            }
            checks.add(new ReadinessReport.Check(probe.id(), status, reason, probe.nextAction()));
        }
        checks.add(new ReadinessReport.Check(
                "administration", ReadinessReport.Status.UNKNOWN, "unverified", "reviewAdministration"));
        return new ReadinessReport(false, checks);
    }

    private List<Probe> probes() {
        String configRead = "sudo -n " + LinuxCommands.cat(properties.paths().config());
        return List.of(
                new Probe(
                        "tools",
                        "for tool in sudo testparm pdbedit cat; do command -v \"$tool\" > /dev/null 2>&1 || exit 1; done",
                        "reviewTools"),
                new Probe("configRead", configRead, "reviewConfigRead"),
                new Probe(
                        "configValidation",
                        LinuxCommands.testparmSilent(properties.paths().config()),
                        "reviewConfig"),
                new Probe("accountsRead", "sudo -n pdbedit -L", "reviewAccounts"));
    }

    private record Probe(String id, String command, String nextAction) {}
}
