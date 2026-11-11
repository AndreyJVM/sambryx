package mari.samba.infra;

import static org.assertj.core.api.Assertions.assertThat;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import mari.samba.share.SambaShareCreateDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Epic("Configuration and backups")
@Feature("Unit: Samba boolean forms")
class SmbConfParserBooleanTest {
    private final SmbConfParser parser = new SmbConfParser();

    @ParameterizedTest(name = "Samba form={0}")
    @ValueSource(strings = {"true", "TRUE", "1"})
    @DisplayName("Share settings recognize Samba true forms")
    void readsSambaTrueFormsInShareSettings(String value) {
        assertThat(parser.parseShares("[team]\npath = /srv/team\nread only = " + value + "\n"))
                .singleElement()
                .satisfies(share -> assertThat(share.isReadOnly()).isTrue());
    }

    @ParameterizedTest(name = "Samba form={0}")
    @ValueSource(strings = {"true", "TRUE", "1"})
    @DisplayName("Global settings recognize Samba true forms")
    void readsSambaTrueFormsInGlobalSettings(String value) {
        assertThat(parser.parseGlobalConfig("[global]\nbind interfaces only = " + value + "\n")
                        .bindInterfacesOnly())
                .isTrue();
    }

    @ParameterizedTest(name = "Samba form=[{0}], expected enabled={1}")
    @CsvSource({
        "yes,true",
        "YES,true",
        "YeS,true",
        "true,true",
        "TRUE,true",
        "TrUe,true",
        "1,true",
        "no,false",
        "NO,false",
        "nO,false",
        "false,false",
        "FALSE,false",
        "FaLsE,false",
        "0,false",
        "unknown,false",
        "2,false",
        "'',false"
    })
    @DisplayName("Managed boolean values retain their meaning across read and rewrite")
    void preservesManagedBooleanSemanticsAcrossReadAndRewrite(String value, boolean expected) {
        String config = "[global]\ninterfaces = eth0\nbind interfaces only = " + value
                + "\nload printers = " + value + "\ndisable netbios = " + value
                + "\nwinbind use default domain = " + value
                + "\n[team]\npath = /srv/team\nread only = " + value
                + "\nguest ok = " + value + "\nbrowseable = " + value + "\n";
        var global = parser.parseGlobalConfig(config);
        assertThat(global.bindInterfacesOnly()).isEqualTo(expected);
        assertThat(global.loadPrinters()).isEqualTo(expected);
        assertThat(global.disableNetbios()).isEqualTo(expected);
        assertThat(global.winbindUseDefaultDomain()).isEqualTo(expected);
        var share = parser.parseShares(config).getFirst();
        assertThat(share.isReadOnly()).isEqualTo(expected);
        assertThat(share.isGuestOk()).isEqualTo(expected);
        assertThat(share.isBrowseable()).isEqualTo(expected);
        String output = expected ? "yes" : "no";
        String updated = parser.updateGlobalSection(config, global);
        assertThat(updated)
                .contains(
                        "bind interfaces only = " + output + "\n",
                        "load printers = " + output + "\n",
                        "disable netbios = " + output + "\n",
                        "winbind use default domain = " + output + "\n");
        assertThat(parser.parseGlobalConfig(updated)).isEqualTo(global);
        assertThat(parser.parseShares(updated)).containsExactly(share);
        String generated = parser.buildShareSection(SambaShareCreateDto.builder()
                .name(share.getName())
                .path(share.getPath())
                .comment("Updated team files")
                .readOnly(share.isReadOnly())
                .guestOk(share.isGuestOk())
                .browseable(share.isBrowseable())
                .build());
        assertThat(generated)
                .contains(
                        "read only = " + output + "\n", "guest ok = " + output + "\n", "browseable = " + output + "\n");
        assertThat(parser.parseShares(generated)).singleElement().satisfies(reloaded -> {
            assertThat(reloaded.isReadOnly()).isEqualTo(expected);
            assertThat(reloaded.isGuestOk()).isEqualTo(expected);
            assertThat(reloaded.isBrowseable()).isEqualTo(expected);
            assertThat(reloaded.getComment()).isEqualTo("Updated team files");
        });
    }

    @Test
    @DisplayName("Absent boolean settings retain global and share defaults")
    void retainsDefaultsWhenBooleanParametersAreAbsent() {
        var global = parser.parseGlobalConfig("[global]\n");
        assertThat(global.bindInterfacesOnly()).isFalse();
        assertThat(global.loadPrinters()).isFalse();
        assertThat(global.disableNetbios()).isFalse();
        assertThat(global.winbindUseDefaultDomain()).isTrue();
        assertThat(parser.parseGlobalConfig(null)).isEqualTo(global);
        assertThat(parser.parseGlobalConfig("")).isEqualTo(global);
        assertThat(parser.parseShares("[team]\npath = /srv/team\n"))
                .singleElement()
                .satisfies(share -> {
                    assertThat(share.isReadOnly()).isTrue();
                    assertThat(share.isGuestOk()).isFalse();
                    assertThat(share.isBrowseable()).isTrue();
                });
    }
}
