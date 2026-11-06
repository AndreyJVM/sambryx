package mari.samba.monitoring;

import java.util.List;

public record ReadinessReport(boolean ready, List<Check> checks) {
    public ReadinessReport {
        checks = List.copyOf(checks);
    }

    public enum Status {
        PASSED,
        FAILED,
        UNKNOWN
    }

    public record Check(String id, Status status, String reason, String nextAction) {}
}
