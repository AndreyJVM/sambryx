package mari.samba.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mari.samba.share.SambaShare;
import mari.samba.share.SambaShareCreateDto;
import mari.samba.smbconfig.SambaGlobalConfigDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@ResourceLock(Resources.LOCALE)
@Epic("Configuration and backups")
@Feature("Unit: configuration parsing and generation")
class SmbConfParserTest {

    private SmbConfParser parser;

    @BeforeEach
    void setUp() {
        parser = new SmbConfParser();
    }

    private final String sampleConfig =
            """
            # Global settings
            [global]
               workgroup = TESTGROUP
               server string = Test File Server
               security = user
               map to guest = Bad User
               interfaces = 192.168.1.0/24
               bind interfaces only = yes
               load printers = no
               disable netbios = yes
               server min protocol = SMB2
               server max protocol = SMB3

            [public]
               path = /srv/samba/public
               comment = Public share
               read only = no
               guest ok = yes
               browseable = yes

            [private]
               path = /srv/samba/private
               comment = Private files
               read only = yes
               guest ok = no
               browseable = no
               valid users = admin, @managers
            """;

    @Test
    @DisplayName("Should parse global configuration from smb.conf")
    void shouldParseGlobalConfig() {
        SambaGlobalConfigDto global = parser.parseGlobalConfig(sampleConfig);

        assertThat(global.getWorkgroup()).isEqualTo("TESTGROUP");
        assertThat(global.getServerString()).isEqualTo("Test File Server");
        assertThat(global.getSecurity()).isEqualTo("user");
        assertThat(global.getMapToGuest()).isEqualTo("Bad User");
        assertThat(global.getInterfaces()).isEqualTo("192.168.1.0/24");
        assertThat(global.isBindInterfacesOnly()).isTrue();
        assertThat(global.isLoadPrinters()).isFalse();
        assertThat(global.isDisableNetbios()).isTrue();
        assertThat(global.getServerMinProtocol()).isEqualTo("SMB2");
        assertThat(global.getServerMaxProtocol()).isEqualTo("SMB3");
    }

    @Test
    @DisplayName("Should parse only user-defined shares")
    void shouldParseOnlyUserShares() {
        List<SambaShare> shares = parser.parseShares(sampleConfig);

        assertThat(shares).hasSize(2);

        SambaShare publicShare = shares.stream()
                .filter(s -> s.getName().equals("public"))
                .findFirst()
                .orElse(null);

        assertThat(publicShare).isNotNull();
        assertThat(publicShare.getPath()).isEqualTo("/srv/samba/public");
        assertThat(publicShare.isReadOnly()).isFalse();
        assertThat(publicShare.isGuestOk()).isTrue();
        assertThat(publicShare.isBrowseable()).isTrue();

        SambaShare privateShare = shares.stream()
                .filter(s -> s.getName().equals("private"))
                .findFirst()
                .orElse(null);

        assertThat(privateShare).isNotNull();
        assertThat(privateShare.getPath()).isEqualTo("/srv/samba/private");
        assertThat(privateShare.isReadOnly()).isTrue();
        assertThat(privateShare.isGuestOk()).isFalse();
        assertThat(privateShare.getValidUsers()).isEqualTo("admin, @managers");
    }

    @Test
    @DisplayName("Should build valid share section")
    void shouldBuildShareSection() {
        SambaShareCreateDto dto = SambaShareCreateDto.builder()
                .name("finance")
                .path("/srv/samba/finance")
                .comment("Finance Docs")
                .readOnly(true)
                .guestOk(false)
                .browseable(true)
                .validUsers("accountant")
                .build();

        String section = parser.buildShareSection(dto);

        assertThat(section).contains("[finance]");
        assertThat(section).contains("path = /srv/samba/finance");
        assertThat(section).contains("comment = Finance Docs");
        assertThat(section).contains("read only = yes");
        assertThat(section).contains("guest ok = no");
        assertThat(section).contains("valid users = accountant");
    }

    @Test
    @DisplayName("Should remove specified share section")
    void shouldRemoveSection() {
        String result = parser.removeSection(sampleConfig, "public");

        assertThat(result).doesNotContain("[public]");
        assertThat(result).doesNotContain("/srv/samba/public");
        assertThat(result).contains("[global]");
        assertThat(result).contains("[private]");
    }

    @Test
    @DisplayName("Should throw exception when removing non-existent section")
    void shouldThrowExceptionWhenRemovingNonExistentSection() {
        assertThatThrownBy(() -> parser.removeSection(sampleConfig, "non_existent"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("Should update global section while preserving shares")
    void shouldUpdateGlobalSection() {
        SambaGlobalConfigDto newGlobal = SambaGlobalConfigDto.builder()
                .workgroup("NEWCORP")
                .serverString("New Server")
                .build();

        String updatedConfig = parser.updateGlobalSection(sampleConfig, newGlobal);

        assertThat(updatedConfig).contains("workgroup = NEWCORP");
        assertThat(updatedConfig).contains("server string = New Server");
        assertThat(updatedConfig).doesNotContain("workgroup = TESTGROUP");
        assertThat(updatedConfig).contains("[public]");
        assertThat(updatedConfig).contains("[private]");
    }

    @Test
    @DisplayName("Should parse effective configuration from testparm dump")
    void shouldParseEffectiveConfig() {
        String testparmDump =
                """
                # Load smb config files from /etc/samba/smb.conf
                # Loaded services file OK.
                ; Server role: ROLE_STANDALONE

                [global]
                \tbind interfaces only = No
                \tdisable netbios = Yes
                \tguest ok = No
                \thosts allow = 192.168.1.0/24
                \thosts deny = ALL
                \tmap to guest = Bad User
                \tSERVER   MIN   PROTOCOL = SMB2_02
                \tserver max protocol = SMB3
                \tvfs objects = full_audit
                \tldap server require strong auth = yes

                [public]
                \tcomment = Public share
                \tpath = /srv/samba/public
                \tread only = No
                \tguest ok = Yes
                \tcreate mask = 0744
                \tdirectory mask = 0755

                [developers_shared]
                \tcomment = Devs
                \tpath = /srv/samba/devs
                \tcreate mask = 0640
                \tdirectory mask = 0750
                """;

        Map<String, Map<String, String>> effective = parser.parseEffectiveConfig(testparmDump);

        assertThat(effective).containsOnlyKeys("global", "public", "developers_shared");

        Map<String, String> global = effective.get("global");
        assertThat(global)
                .containsEntry("bind interfaces only", "No")
                .containsEntry("disable netbios", "Yes")
                .containsEntry("guest ok", "No")
                .containsEntry("hosts allow", "192.168.1.0/24")
                .containsEntry("hosts deny", "ALL")
                .containsEntry("map to guest", "Bad User")
                .containsEntry("server min protocol", "SMB2_02")
                .containsEntry("server max protocol", "SMB3")
                .containsEntry("vfs objects", "full_audit")
                .containsEntry("ldap server require strong auth", "yes");

        Map<String, String> publicShare = effective.get("public");
        assertThat(publicShare)
                .containsEntry("comment", "Public share")
                .containsEntry("path", "/srv/samba/public")
                .containsEntry("read only", "No")
                .containsEntry("guest ok", "Yes")
                .containsEntry("create mask", "0744")
                .containsEntry("directory mask", "0755");

        Map<String, String> devShare = effective.get("developers_shared");
        assertThat(devShare).containsEntry("create mask", "0640").containsEntry("directory mask", "0750");
    }

    @Test
    void parsesWindowsLineEndingsAndPreservesEqualsInsideValues() {
        String config = "[global]\r\nserver string = Files = Archive\r\n"
                + "[archive]\r\npath = /srv/data=archive\r\ncomment = Revision=2\r\n";

        assertThat(parser.parseGlobalConfig(config).getServerString()).isEqualTo("Files = Archive");
        assertThat(parser.parseShares(config)).singleElement().satisfies(share -> {
            assertThat(share.getPath()).isEqualTo("/srv/data=archive");
            assertThat(share.getComment()).isEqualTo("Revision=2");
        });
    }

    @Test
    void removingSectionDoesNotRemoveSimilarlyNamedShare() {
        String config = "[archive]\npath = /srv/old\n[archive-2026]\npath = /srv/current\n";

        String result = parser.removeSection(config, "ARCHIVE");

        assertThat(parser.parseShares(result)).singleElement().satisfies(share -> {
            assertThat(share.getName()).isEqualTo("archive-2026");
            assertThat(share.getPath()).isEqualTo("/srv/current");
        });
        assertThat(result).doesNotContain("/srv/old");
    }

    @Test
    void specialSectionsDoNotLeakTheirPropertiesIntoUserShares() {
        String config = "[team]\npath = /srv/team\n[homes]\npath = /home/%S\n"
                + "[printers]\npath = /var/spool/samba\n[other]\npath = /srv/other\n";

        assertThat(parser.parseShares(config)).extracting(SambaShare::getName).containsExactly("team", "other");
        assertThat(parser.parseShares(config))
                .extracting(SambaShare::getPath)
                .containsExactly("/srv/team", "/srv/other");
    }

    @ParameterizedTest
    @ValueSource(strings = {"en-US", "tr-TR"})
    void parsesGlobalParameterNamesIndependentlyOfDefaultLocale(String languageTag) {
        Locale originalLocale = Locale.getDefault();
        Locale originalDisplayLocale = Locale.getDefault(Locale.Category.DISPLAY);
        Locale originalFormatLocale = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.forLanguageTag(languageTag));

            SambaGlobalConfigDto global =
                    parser.parseGlobalConfig("[GLOBAL]\nINTERFACES = eth0\nBIND INTERFACES ONLY = yes\n");

            assertThat(global.interfaces()).isEqualTo("eth0");
            assertThat(global.bindInterfacesOnly()).isTrue();
        } finally {
            Locale.setDefault(originalLocale);
            Locale.setDefault(Locale.Category.DISPLAY, originalDisplayLocale);
            Locale.setDefault(Locale.Category.FORMAT, originalFormatLocale);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en-US", "tr-TR"})
    void parsesShareParameterNamesIndependentlyOfDefaultLocale(String languageTag) {
        Locale originalLocale = Locale.getDefault();
        Locale originalDisplayLocale = Locale.getDefault(Locale.Category.DISPLAY);
        Locale originalFormatLocale = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.forLanguageTag(languageTag));

            assertThat(parser.parseShares("[Archive]\npath = /srv/archive\nVALID USERS = @Archivists\n"))
                    .singleElement()
                    .satisfies(share -> assertThat(share.getValidUsers()).isEqualTo("@Archivists"));
        } finally {
            Locale.setDefault(originalLocale);
            Locale.setDefault(Locale.Category.DISPLAY, originalDisplayLocale);
            Locale.setDefault(Locale.Category.FORMAT, originalFormatLocale);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en-US", "tr-TR"})
    void normalizesSecurityDomainIndependentlyOfDefaultLocale(String languageTag) {
        Locale originalLocale = Locale.getDefault();
        Locale originalDisplayLocale = Locale.getDefault(Locale.Category.DISPLAY);
        Locale originalFormatLocale = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.forLanguageTag(languageTag));

            assertThat(parser.parseGlobalConfig("[global]\nsecurity = DOMAIN\n").security())
                    .isEqualTo("domain");
        } finally {
            Locale.setDefault(originalLocale);
            Locale.setDefault(Locale.Category.DISPLAY, originalDisplayLocale);
            Locale.setDefault(Locale.Category.FORMAT, originalFormatLocale);
        }
    }

    @Test
    void parsesMixedCaseNamesWhilePreservingStringValueCase() {
        String config = "[GlObAl]\nSeRvEr StRiNg = Mixed Case Server\n"
                + "[HoMeS]\npath = /home/%S\n[PrInTeRs]\npath = /var/spool/samba\n"
                + "[Archive]\nPaTh = /srv/MixedCase\nVaLiD UsErS = @Archivists\n";

        assertThat(parser.parseGlobalConfig(config).serverString()).isEqualTo("Mixed Case Server");
        assertThat(parser.parseShares(config)).singleElement().satisfies(share -> {
            assertThat(share.getName()).isEqualTo("Archive");
            assertThat(share.getPath()).isEqualTo("/srv/MixedCase");
            assertThat(share.getValidUsers()).isEqualTo("@Archivists");
        });
    }

    @Test
    void updatingGlobalPreservesUnmanagedParametersInOtherSections() {
        String config = "[GlObAl]\nworkgroup = OLD\n"
                + "[homes]\npath = /home/%S\nvfs objects = acl_xattr\n"
                + "[Archive]\npath = /srv/Archive\nfull_audit:prefix = %u|%I|Action=Open\n"
                + "comment = Mixed  Case = Value\n";
        SambaGlobalConfigDto replacement = SambaGlobalConfigDto.builder()
                .workgroup("NEW")
                .serverString("New Server")
                .build();

        String updated = parser.updateGlobalSection(config, replacement);
        Map<String, Map<String, String>> sections = parser.parseEffectiveConfig(updated);

        assertThat(sections).containsOnlyKeys("global", "homes", "Archive");
        assertThat(sections.get("global"))
                .containsEntry("workgroup", "NEW")
                .containsEntry("server string", "New Server");
        assertThat(sections.get("homes"))
                .containsExactlyInAnyOrderEntriesOf(Map.of("path", "/home/%S", "vfs objects", "acl_xattr"));
        assertThat(sections.get("Archive"))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "path", "/srv/Archive",
                        "full_audit:prefix", "%u|%I|Action=Open",
                        "comment", "Mixed  Case = Value"));
    }

    @Test
    @DisplayName("Should return empty map on null or empty input")
    void shouldReturnEmptyMapOnNullOrEmptyInput() {
        assertThat(parser.parseEffectiveConfig(null)).isEmpty();
        assertThat(parser.parseEffectiveConfig("")).isEmpty();
        assertThat(parser.parseEffectiveConfig("   \n\t  \n")).isEmpty();
    }
}
