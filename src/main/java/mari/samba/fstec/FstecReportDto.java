package mari.samba.fstec;

import java.time.Instant;
import java.util.List;

/** Aggregate report summarizing FSTEC compliance check results. */
public record FstecReportDto(
        Instant checkedAt,
        int compliancePercent,
        int okCount,
        int failedCount,
        int manualCount,
        int notApplicableCount,
        List<FstecRuleResult> rules) {

    public FstecReportDto {
        if (rules == null) {
            rules = List.of();
        }
    }
}
