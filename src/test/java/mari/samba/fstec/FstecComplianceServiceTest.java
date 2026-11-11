package mari.samba.fstec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import mari.samba.core.SambaCommandException;
import mari.samba.infra.CommandExecutor;
import mari.samba.infra.LinuxCommands;
import mari.samba.infra.SmbConfParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@Epic("Compliance")
@Feature("Unit: compliance report evaluation (mock commands)")
class FstecComplianceServiceTest {

    @Mock
    private CommandExecutor commandExecutor;

    private SmbConfParser smbConfParser;
    private FstecRuleLoader ruleLoader;
    private FstecRuleEvaluator evaluator;
    private FstecComplianceService service;

    @BeforeEach
    void setUp() {
        smbConfParser = new SmbConfParser();
        ruleLoader = new FstecRuleLoader();
        ruleLoader.init();
        evaluator = new FstecRuleEvaluator();
        service = new FstecComplianceService(commandExecutor, smbConfParser, ruleLoader, evaluator);
    }

    private final String compliantConfig =
            """
            [global]
            \tserver role = standalone server
            \tmap to guest = Never
            \thosts allow = 192.168.1.0/24
            \thosts deny = ALL
            \tdisable netbios = Yes
            \tsmb ports = 445
            \tserver min protocol = SMB2_02
            \tserver max protocol = SMB3
            \tvfs objects = full_audit
            \tfull_audit:success = connect open mkdir rmdir unlink write rename lock pwrite renameat unlinkat
            \tfull_audit:failure = connect open mkdir rmdir unlink write rename
            \tfull_audit:facility = local5
            \tfull_audit:priority = notice
            \tlog level = 1 vfs:3

            [docs]
            \tpath = /srv/samba/docs
            \tguest ok = No
            \tcreate mask = 0640
            \tdirectory mask = 0750
            """;

    private final String nonCompliantConfig =
            """
            [global]
            \tserver role = standalone server
            \tmap to guest = Bad User
            \thosts allow =
            \thosts deny =
            \tdisable netbios = No
            \tsmb ports = 139 445
            \tserver min protocol = NT1
            \tserver max protocol = SMB2
            \tvfs objects =
            \tfull_audit:success =
            \tfull_audit:failure =
            \tfull_audit:facility =
            \tfull_audit:priority =
            \tlog level = 1

            [public]
            \tpath = /srv/samba/public
            \tguest ok = Yes
            \tcreate mask = 0777
            \tdirectory mask = 0777
            """;

    @Test
    @DisplayName("Should report high compliance on fully compliant standalone file server")
    void shouldReportHighComplianceWhenConfigIsCompliant() {
        when(commandExecutor.execute("session-1", LinuxCommands.testparmDump())).thenReturn(compliantConfig);

        FstecReportDto report = service.check("session-1");

        assertThat(report).isNotNull();
        assertThat(report.compliancePercent()).isEqualTo(100);
        assertThat(report.failedCount()).isZero();
        assertThat(report.okCount()).isGreaterThan(0);
        assertThat(report.notApplicableCount()).isGreaterThan(0); // AD_DC rules (4.1, 4.4, 4.5, 4.6) are NOT_APPLICABLE

        // Check specific rules
        FstecRuleResult minProto = report.rules().stream()
                .filter(r -> "2.5-server-min-protocol".equals(r.id()))
                .findFirst()
                .orElseThrow();
        assertThat(minProto.status()).isEqualTo(FstecStatus.OK);

        FstecRuleResult shareGuestOk = report.rules().stream()
                .filter(r -> "2.1-share-guest-ok".equals(r.id()))
                .findFirst()
                .orElseThrow();
        assertThat(shareGuestOk.status()).isEqualTo(FstecStatus.OK);
        assertThat(shareGuestOk.shareResults()).hasSize(1);
        assertThat(shareGuestOk.shareResults().getFirst().status()).isEqualTo(FstecStatus.OK);
    }

    @Test
    @DisplayName("Should detect non-compliant rules on insecure configuration")
    void shouldDetectNonCompliantRules() {
        when(commandExecutor.execute("session-1", LinuxCommands.testparmDump())).thenReturn(nonCompliantConfig);

        FstecReportDto report = service.check("session-1");

        assertThat(report).isNotNull();
        assertThat(report.compliancePercent()).isLessThan(50);
        assertThat(report.failedCount()).isGreaterThan(0);

        FstecRuleResult guestOk = report.rules().stream()
                .filter(r -> "2.1-share-guest-ok".equals(r.id()))
                .findFirst()
                .orElseThrow();
        assertThat(guestOk.status()).isEqualTo(FstecStatus.FAILED);
        assertThat(guestOk.shareResults().getFirst().status()).isEqualTo(FstecStatus.FAILED);

        FstecRuleResult createMask = report.rules().stream()
                .filter(r -> "2.3-share-create-mask".equals(r.id()))
                .findFirst()
                .orElseThrow();
        assertThat(createMask.status()).isEqualTo(FstecStatus.FAILED);

        FstecRuleResult minProto = report.rules().stream()
                .filter(r -> "2.5-server-min-protocol".equals(r.id()))
                .findFirst()
                .orElseThrow();
        assertThat(minProto.status()).isEqualTo(FstecStatus.FAILED);
    }

    @Test
    @DisplayName("Should mark AD rules as NOT_APPLICABLE on standalone file server")
    void shouldMarkAdRulesAsNotApplicableOnStandaloneServer() {
        when(commandExecutor.execute("session-1", LinuxCommands.testparmDump())).thenReturn(compliantConfig);

        FstecReportDto report = service.check("session-1");

        FstecRuleResult ldapRule = report.rules().stream()
                .filter(r -> "4.1-ldap-strong-auth".equals(r.id()))
                .findFirst()
                .orElseThrow();
        assertThat(ldapRule.status()).isEqualTo(FstecStatus.NOT_APPLICABLE);

        FstecRuleResult backupRule = report.rules().stream()
                .filter(r -> "4.4-ad-backup".equals(r.id()))
                .findFirst()
                .orElseThrow();
        assertThat(backupRule.status()).isEqualTo(FstecStatus.NOT_APPLICABLE);
    }

    @Test
    @DisplayName("Should mark AD manual rules as MANUAL on Active Directory Domain Controller")
    void shouldMarkAdManualRulesAsManualOnAdDc() {
        String adDcConfig =
                """
                [global]
                \tserver role = active directory domain controller
                \tldap server require strong auth = yes
                \tmap to guest = Never
                \thosts allow = 192.168.1.0/24
                \thosts deny = ALL
                \tdisable netbios = Yes
                \tsmb ports = 445
                \tserver min protocol = SMB2
                \tserver max protocol = SMB3
                \tvfs objects = full_audit
                \tfull_audit:success = connect open mkdir rmdir unlink write rename lock pwrite renameat unlinkat
                \tfull_audit:failure = connect open mkdir rmdir unlink write rename
                \tfull_audit:facility = local5
                \tfull_audit:priority = notice
                \tlog level = 1 vfs:3

                [sysvol]
                \tpath = /var/lib/samba/sysvol
                \tguest ok = No
                \tcreate mask = 0640
                \tdirectory mask = 0750
                """;

        when(commandExecutor.execute("session-1", LinuxCommands.testparmDump())).thenReturn(adDcConfig);

        FstecReportDto report = service.check("session-1");

        FstecRuleResult backupRule = report.rules().stream()
                .filter(r -> "4.4-ad-backup".equals(r.id()))
                .findFirst()
                .orElseThrow();
        assertThat(backupRule.status()).isEqualTo(FstecStatus.MANUAL);

        FstecRuleResult domainAdminsRule = report.rules().stream()
                .filter(r -> "4.5-domain-admins".equals(r.id()))
                .findFirst()
                .orElseThrow();
        assertThat(domainAdminsRule.status()).isEqualTo(FstecStatus.MANUAL);

        FstecRuleResult ldapRule = report.rules().stream()
                .filter(r -> "4.1-ldap-strong-auth".equals(r.id()))
                .findFirst()
                .orElseThrow();
        assertThat(ldapRule.status()).isEqualTo(FstecStatus.OK);
    }

    @Test
    @DisplayName("Should fail when one share has permissive mask while another is strict")
    void shouldFailWhenOneShareIsPermissive() {
        String mixedConfig =
                """
                [global]
                \tserver role = standalone server
                \thosts allow = 192.168.1.0/24
                \thosts deny = ALL

                [secure_share]
                \tpath = /srv/samba/sec
                \tguest ok = No
                \tcreate mask = 0640
                \tdirectory mask = 0750

                [insecure_share]
                \tpath = /srv/samba/insec
                \tguest ok = No
                \tcreate mask = 0755
                \tdirectory mask = 0750
                """;

        when(commandExecutor.execute("session-1", LinuxCommands.testparmDump())).thenReturn(mixedConfig);

        FstecReportDto report = service.check("session-1");

        FstecRuleResult createMask = report.rules().stream()
                .filter(r -> "2.3-share-create-mask".equals(r.id()))
                .findFirst()
                .orElseThrow();

        assertThat(createMask.status()).isEqualTo(FstecStatus.FAILED);
        assertThat(createMask.shareResults()).hasSize(2);

        ShareResult secureResult = createMask.shareResults().stream()
                .filter(s -> "secure_share".equals(s.shareName()))
                .findFirst()
                .orElseThrow();
        assertThat(secureResult.status()).isEqualTo(FstecStatus.OK);

        ShareResult insecureResult = createMask.shareResults().stream()
                .filter(s -> "insecure_share".equals(s.shareName()))
                .findFirst()
                .orElseThrow();
        assertThat(insecureResult.status()).isEqualTo(FstecStatus.FAILED);
        assertThat(insecureResult.currentValue()).isEqualTo("0755");
    }

    @Test
    @DisplayName("Should throw SambaCommandException when testparm command fails or returns blank")
    void shouldThrowExceptionWhenTestparmFails() {
        when(commandExecutor.execute(anyString(), anyString()))
                .thenThrow(new SambaCommandException("Command failed with exit code 1"));

        assertThatThrownBy(() -> service.check("session-err"))
                .isInstanceOf(SambaCommandException.class)
                .hasMessageContaining("Command failed");
    }

    @Test
    @DisplayName("Should throw SambaCommandException when testparm returns empty string")
    void shouldThrowExceptionWhenTestparmOutputEmpty() {
        when(commandExecutor.execute(anyString(), anyString())).thenReturn("   \n\t  \n");

        assertThatThrownBy(() -> service.check("session-empty"))
                .isInstanceOf(SambaCommandException.class)
                .hasMessageContaining("empty configuration");
    }
}
