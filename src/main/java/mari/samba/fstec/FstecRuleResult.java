package mari.samba.fstec;

import java.util.List;

/** Result of evaluating a specific FSTEC compliance rule. */
public record FstecRuleResult(
        String id,
        String section,
        String title,
        String severity,
        FstecStatus status,
        String currentValue,
        String expectedValue,
        String howToFix,
        String consequences,
        List<ShareResult> shareResults) {

    public FstecRuleResult {
        if (shareResults == null) {
            shareResults = List.of();
        }
    }

    public FstecRuleResult(
            String id,
            String section,
            String title,
            String severity,
            FstecStatus status,
            String currentValue,
            String expectedValue,
            String howToFix) {
        this(id, section, title, severity, status, currentValue, expectedValue, howToFix, null, List.of());
    }
}
