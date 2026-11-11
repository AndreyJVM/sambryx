package mari.samba.infra;

import static org.assertj.core.api.Assertions.assertThat;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.util.stream.Stream;
import mari.samba.share.SambaShare;
import mari.samba.share.SambaShareCreateDto;
import mari.samba.smbconfig.SambaGlobalConfigDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Epic("Configuration and backups")
@Feature("Unit: configuration text round trips")
class SmbConfParserRoundTripTest {
    private final SmbConfParser parser = new SmbConfParser();

    @Test
    @DisplayName("Generated share sections preserve advanced fields when parsed again")
    void preservesAdvancedShareFieldsWhenGeneratedSectionIsReadAgain() {
        var dto = SambaShareCreateDto.builder()
                .name("team")
                .path("/srv/team")
                .comment("Team files")
                .validUsers("@team alice")
                .writeList("alice")
                .createMask("0640")
                .directoryMask("0750")
                .forceUser("owner")
                .forceGroup("team")
                .maxConnections("12")
                .hostsAllow("192.0.2.0/24")
                .hostsDeny("ALL")
                .build();
        String section = parser.buildShareSection(dto);
        assertThat(section)
                .contains(
                        "write list = alice\n",
                        "create mask = 0640\n",
                        "directory mask = 0750\n",
                        "force user = owner\n",
                        "force group = team\n",
                        "max connections = 12\n",
                        "hosts allow = 192.0.2.0/24\n",
                        "hosts deny = ALL\n");
        assertThat(parser.parseShares(section)).singleElement().satisfies(share -> {
            assertThat(share.getName()).isEqualTo("team");
            assertThat(share.getPath()).isEqualTo("/srv/team");
            assertThat(share.getComment()).isEqualTo("Team files");
            assertThat(share.getValidUsers()).isEqualTo("@team alice");
            assertThat(share.getWriteList()).isEqualTo("alice");
            assertThat(share.getCreateMask()).isEqualTo("0640");
            assertThat(share.getDirectoryMask()).isEqualTo("0750");
            assertThat(share.getForceUser()).isEqualTo("owner");
            assertThat(share.getForceGroup()).isEqualTo("team");
            assertThat(share.getMaxConnections()).isEqualTo("12");
            assertThat(share.getHostsAllow()).isEqualTo("192.0.2.0/24");
            assertThat(share.getHostsDeny()).isEqualTo("ALL");
        });
    }

    @Test
    @DisplayName("Blank optional share fields are omitted and remain absent on read")
    void omitsBlankOptionalShareFieldsWithoutInventingValuesOnRead() {
        String section = parser.buildShareSection(SambaShareCreateDto.builder()
                .name("team")
                .path("/srv/team")
                .comment("  ")
                .validUsers("")
                .hostsAllow("\t")
                .build());
        assertThat(section).doesNotContain("comment =", "valid users =", "hosts allow =", "write list =");
        assertThat(parser.parseShares(section)).singleElement().satisfies(share -> {
            assertThat(share.getComment()).isNull();
            assertThat(share.getValidUsers()).isNull();
            assertThat(share.getHostsAllow()).isNull();
            assertThat(share.getWriteList()).isNull();
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shareBooleanSettings")
    @DisplayName("Generated share booleans use explicit Samba forms and survive parsing")
    void emitsExplicitShareBooleansAndReadsThemBack(boolean enabled) {
        String section = parser.buildShareSection(SambaShareCreateDto.builder()
                .name("team")
                .path("/srv/team")
                .readOnly(enabled)
                .guestOk(enabled)
                .browseable(enabled)
                .build());
        String value = enabled ? "yes" : "no";
        assertThat(section)
                .contains("read only = " + value + "\n", "guest ok = " + value + "\n", "browseable = " + value + "\n");
        assertThat(parser.parseShares(section)).singleElement().satisfies(share -> {
            assertThat(share.isReadOnly()).isEqualTo(enabled);
            assertThat(share.isGuestOk()).isEqualTo(enabled);
            assertThat(share.isBrowseable()).isEqualTo(enabled);
        });
    }

    private static Stream<Named<Boolean>> shareBooleanSettings() {
        return Stream.of(
                Named.of("all managed share flags enabled", true), Named.of("all managed share flags disabled", false));
    }

    @Test
    @DisplayName("Global replacement preserves managed domain settings on read")
    void preservesManagedDomainSettingsAcrossGlobalReplacementAndRead() {
        var dto = SambaGlobalConfigDto.builder()
                .workgroup("EXAMPLE")
                .security("ads")
                .realm("EXAMPLE.TEST")
                .netbiosName("FILES")
                .interfaces("eth0")
                .bindInterfacesOnly(true)
                .loadPrinters(false)
                .disableNetbios(true)
                .winbindUseDefaultDomain(true)
                .serverMinProtocol("SMB2")
                .serverMaxProtocol("SMB3")
                .templateShell("/bin/bash")
                .idmapDefaultBackend("tdb")
                .idmapDefaultRange("3000-7999")
                .idmapDomainBackend("rid")
                .idmapDomainRange("10000-99999")
                .build();
        String updated = parser.updateGlobalSection("[global]\nworkgroup = OLD\n", dto);
        assertThat(updated)
                .contains(
                        "security = ads\n",
                        "realm = EXAMPLE.TEST\n",
                        "idmap config EXAMPLE : backend = rid\n",
                        "idmap config EXAMPLE : range = 10000-99999\n");
        assertThat(parser.parseGlobalConfig(updated)).isEqualTo(dto);
    }

    @Test
    @DisplayName("The last repeated parameter wins while global replacement preserves shares")
    void lastRepeatedParameterWinsBeforeAndAfterGlobalReplacement() {
        String config = "[global]\nworkgroup = FIRST\nworkgroup = LAST\n"
                + "[team]\npath = /srv/old\npath = /srv/team\nread only = yes\nread only = no\n";
        assertThat(parser.parseGlobalConfig(config).workgroup()).isEqualTo("LAST");
        assertThat(parser.parseShares(config)).singleElement().satisfies(share -> {
            assertThat(share.getPath()).isEqualTo("/srv/team");
            assertThat(share.isReadOnly()).isFalse();
        });
        String updated = parser.updateGlobalSection(
                config, SambaGlobalConfigDto.builder().workgroup("NEW").build());
        assertThat(parser.parseGlobalConfig(updated).workgroup()).isEqualTo("NEW");
        assertThat(parser.parseShares(updated)).containsExactlyElementsOf(parser.parseShares(config));
    }

    @Test
    @DisplayName("Global replacement removes repeated global sections and preserves interleaved shares")
    void replacesAllRepeatedGlobalSectionsWithoutRemovingInterleavedShares() {
        String config = "[global]\nworkgroup = FIRST\n[team]\npath = /srv/team\n"
                + "[global]\nworkgroup = LAST\n[archive]\npath = /srv/archive\n";
        assertThat(parser.parseGlobalConfig(config).workgroup()).isEqualTo("LAST");
        String updated = parser.updateGlobalSection(
                config, SambaGlobalConfigDto.builder().workgroup("NEW").build());
        assertThat(updated).doesNotContain("FIRST", "LAST");
        assertThat(updated.split("\\[global\\]", -1)).hasSize(2);
        assertThat(parser.parseShares(updated))
                .extracting(SambaShare::getPath)
                .containsExactly("/srv/team", "/srv/archive");
        assertThat(parser.parseGlobalConfig(updated).workgroup()).isEqualTo("NEW");
    }
}
