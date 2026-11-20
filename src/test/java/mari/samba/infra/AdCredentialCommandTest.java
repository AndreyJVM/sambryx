package mari.samba.infra;

import static org.assertj.core.api.Assertions.assertThat;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.nio.file.Files;
import java.nio.file.Path;
import mari.samba.support.ShellCommandFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Epic("Active Directory")
@Feature("Local shell characterization: credential arguments with stub sudo and net")
class AdCredentialCommandTest {
    @TempDir
    Path temporary;

    @DisplayName("Current domain commands deliver the literal credential to sudo and net arguments")
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"join", "leave"})
    void currentDomainCommandsDeliverLiteralCredentialToSudoAndNetArguments(String action) throws Exception {
        String password = "synthetic-ad-marker ' $(touch unexpected) % tail";
        String credential = "Administrator%" + password;
        var shell = new ShellCommandFixture(temporary);
        shell.stub("sudo", "printf '%s\\n' \"$@\" > sudo-arguments\nexec \"$@\"");
        shell.stub("net", "printf '%s\\n' \"$@\" > net-arguments\ncat > net-input");
        String command = action.equals("join")
                ? LinuxCommands.netAdsJoin("Administrator", password)
                : LinuxCommands.netAdsLeave("Administrator", password);

        var result = shell.run(command, "");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).isEmpty();
        assertThat(Files.readAllLines(temporary.resolve("sudo-arguments")))
                .containsExactly("net", "ads", action, "-U", credential);
        assertThat(Files.readAllLines(temporary.resolve("net-arguments")))
                .containsExactly("ads", action, "-U", credential);
        assertThat(Files.readString(temporary.resolve("net-input"))).isEmpty();
        assertThat(temporary.resolve("unexpected")).doesNotExist();
    }
}
