package mari.samba.fstec;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mari.samba.core.SambaCommandException;
import mari.samba.infra.CommandExecutor;
import mari.samba.infra.LinuxCommands;
import mari.samba.infra.SmbConfParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;

/**
 * Service that inspects Samba configuration and evaluates compliance against
 * FSTEC security guidelines.
 */
@Service
public class FstecComplianceService {

    private static final Logger log = LoggerFactory.getLogger(FstecComplianceService.class);

    private final CommandExecutor commandExecutor;
    private final SmbConfParser smbConfParser;
    private final FstecRuleLoader ruleLoader;
    private final FstecRuleEvaluator evaluator;

    public FstecComplianceService(
            CommandExecutor commandExecutor,
            SmbConfParser smbConfParser,
            FstecRuleLoader ruleLoader,
            FstecRuleEvaluator evaluator) {
        this.commandExecutor = commandExecutor;
        this.smbConfParser = smbConfParser;
        this.ruleLoader = ruleLoader;
        this.evaluator = evaluator;
    }

    /** Executes effective configuration audit on the remote server and produces a compliance report. */
    public FstecReportDto check(@NonNull String sessionId) throws SambaCommandException {
        String dump = commandExecutor.execute(sessionId, LinuxCommands.testparmDump());
        if (dump == null || dump.isBlank()) {
            throw new SambaCommandException("testparm returned empty configuration output");
        }

        Map<String, Map<String, String>> effectiveConfig = smbConfParser.parseEffectiveConfig(dump);
        return evaluateConfig(effectiveConfig);
    }

    /** Evaluates parsed effective configuration against all loaded FSTEC rules. */
    public FstecReportDto evaluateConfig(Map<String, Map<String, String>> effectiveConfig) {
        Map<String, String> globalSection = effectiveConfig.getOrDefault("global", Map.of());
        String serverRole = globalSection.getOrDefault("server role", "").toLowerCase(Locale.ROOT);
        boolean isAdDc = serverRole.contains("domain controller");

        // Collect user shares (exclude global and built-in printer/homes shares)
        Map<String, Map<String, String>> shares = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> entry : effectiveConfig.entrySet()) {
            String section = entry.getKey();
            if (!section.equalsIgnoreCase("global")
                    && !section.equalsIgnoreCase("homes")
                    && !section.equalsIgnoreCase("printers")) {
                shares.put(section, entry.getValue());
            }
        }

        List<FstecRuleDefinition> definitions = ruleLoader.getRules();
        List<FstecRuleResult> results = new ArrayList<>();

        for (FstecRuleDefinition rule : definitions) {
            results.add(evaluateRule(rule, globalSection, shares, isAdDc));
        }

        int okCount =
                (int) results.stream().filter(r -> r.status() == FstecStatus.OK).count();
        int failedCount = (int)
                results.stream().filter(r -> r.status() == FstecStatus.FAILED).count();
        int manualCount = (int)
                results.stream().filter(r -> r.status() == FstecStatus.MANUAL).count();
        int notApplicableCount = (int) results.stream()
                .filter(r -> r.status() == FstecStatus.NOT_APPLICABLE)
                .count();

        int evaluatedTotal = okCount + failedCount;
        int compliancePercent = evaluatedTotal > 0 ? (int) Math.round((double) okCount * 100.0 / evaluatedTotal) : 100;

        return new FstecReportDto(
                Instant.now(), compliancePercent, okCount, failedCount, manualCount, notApplicableCount, results);
    }

    private FstecRuleResult evaluateRule(
            FstecRuleDefinition rule,
            Map<String, String> globalSection,
            Map<String, Map<String, String>> shares,
            boolean isAdDc) {

        String appliesTo = rule.appliesTo() != null ? rule.appliesTo().toUpperCase(Locale.ROOT) : "ALWAYS";

        if ("AD_DC".equals(appliesTo) && !isAdDc) {
            return new FstecRuleResult(
                    rule.id(),
                    rule.section(),
                    rule.title(),
                    rule.severity(),
                    FstecStatus.NOT_APPLICABLE,
                    "Не применимо (сервер не является контроллером домена)",
                    rule.expected(),
                    rule.howToFix(),
                    rule.consequences(),
                    List.of());
        }

        if ("FILE_SERVER".equals(appliesTo) && isAdDc) {
            return new FstecRuleResult(
                    rule.id(),
                    rule.section(),
                    rule.title(),
                    rule.severity(),
                    FstecStatus.NOT_APPLICABLE,
                    "Не применимо для контроллера домена",
                    rule.expected(),
                    rule.howToFix(),
                    rule.consequences(),
                    List.of());
        }

        String scope = rule.scope() != null ? rule.scope().toUpperCase(Locale.ROOT) : "GLOBAL";

        if ("MANUAL".equals(scope)) {
            return new FstecRuleResult(
                    rule.id(),
                    rule.section(),
                    rule.title(),
                    rule.severity(),
                    FstecStatus.MANUAL,
                    "Требуется ручная проверка",
                    rule.expected(),
                    rule.howToFix(),
                    rule.consequences(),
                    List.of());
        }

        if ("SHARE".equals(scope)) {
            List<ShareResult> shareResults = new ArrayList<>();
            boolean allOk = true;

            for (Map.Entry<String, Map<String, String>> entry : shares.entrySet()) {
                String shareName = entry.getKey();
                String val = entry.getValue().getOrDefault(rule.key(), "");
                boolean ok = evaluator.evaluate(rule.operator(), val, rule.expected());
                FstecStatus shareStatus = ok ? FstecStatus.OK : FstecStatus.FAILED;
                if (!ok) {
                    allOk = false;
                }
                shareResults.add(new ShareResult(shareName, shareStatus, val.isBlank() ? "не задано" : val));
            }

            FstecStatus overallStatus = shares.isEmpty() || allOk ? FstecStatus.OK : FstecStatus.FAILED;
            String currentValDesc;
            if (shares.isEmpty()) {
                currentValDesc = "Общие ресурсы не настроены";
            } else if (allOk) {
                currentValDesc = "Все ресурсы соответствуют (" + shares.size() + ")";
            } else {
                long failedShareCount = shareResults.stream()
                        .filter(s -> s.status() == FstecStatus.FAILED)
                        .count();
                currentValDesc = "Несоответствие в ресурсах: " + failedShareCount + " из " + shares.size();
            }

            return new FstecRuleResult(
                    rule.id(),
                    rule.section(),
                    rule.title(),
                    rule.severity(),
                    overallStatus,
                    currentValDesc,
                    rule.expected(),
                    rule.howToFix(),
                    rule.consequences(),
                    shareResults);
        }

        // GLOBAL or other scope
        String val = globalSection.getOrDefault(rule.key(), "");
        boolean ok = evaluator.evaluate(rule.operator(), val, rule.expected());
        FstecStatus status = ok ? FstecStatus.OK : FstecStatus.FAILED;
        String currentValDesc = val.isBlank() ? "не задано" : val;

        return new FstecRuleResult(
                rule.id(),
                rule.section(),
                rule.title(),
                rule.severity(),
                status,
                currentValDesc,
                rule.expected(),
                rule.howToFix(),
                rule.consequences(),
                List.of());
    }
}
