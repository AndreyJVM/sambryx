package sambryx.browser;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sambryx.browser.BrowserFixture.button;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.AriaRole;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfigurationBackupIT {
    private BrowserTestRuntime runtime;

    @BeforeAll
    void startBrowser() {
        runtime = new BrowserTestRuntime();
    }

    @AfterAll
    void closeBrowser() {
        if (runtime != null) runtime.close();
    }

    private static final String SELECTED = "smb.conf.backup_20261024_123456";
    private static final String OTHER = "smb.conf.backup_20261023_123456";

    private static void open(BrowserFixture fixture) {
        fixture.app.backups = BrowserState.JSON.valueToTree(List.of(
                new BackupEntry(SELECTED, "2026-10-24 12:34:56", "2 KB"),
                new BackupEntry(OTHER, "2026-10-24 12:34:56", "2 KB")));
        fixture.go("/ui/config");
        assertThat(fixture.button("Restore: " + SELECTED)).isVisible();
    }

    private static void confirm(BrowserFixture fixture) {
        fixture.button("Restore: " + SELECTED).click();
        button(fixture.page.getByRole(AriaRole.ALERTDIALOG), "Restore").click();
    }

    private static Locator restored(BrowserFixture fixture) {
        return fixture.page
                .getByRole(AriaRole.STATUS)
                .filter(new Locator.FilterOptions().setHasText("Backup restored"));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BrowserVariant.class)
    void preservesStateAndFocusWhenRestoreIsCancelled(BrowserVariant variant) {
        runtime.verify(variant, "en", "preservesStateAndFocusWhenRestoreIsCancelled", fixture -> {
            open(fixture);
            for (String action : List.of("button", "escape")) {
                fixture.button("Restore: " + SELECTED).click();
                var dialog = fixture.page.getByRole(AriaRole.ALERTDIALOG);
                assertThat(dialog).containsText(SELECTED);
                assertThat(dialog).not().containsText(OTHER);
                assertThat(dialog).containsText("replace smb.conf");
                assertThat(dialog).containsText("restart smbd");
                assertThat(dialog).containsText("Unsaved form changes");
                fixture.screenshot("confirmation-" + action);
                if (action.equals("button")) button(dialog, "Cancel").click();
                else fixture.page.keyboard().press("Escape");
                assertThat(dialog).hasCount(0);
                assertThat(fixture.button("Restore: " + SELECTED)).isFocused();
            }
            assertTrue(fixture.app.mutations().isEmpty());
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BrowserVariant.class)
    void blocksDuplicateRestoreAndRefreshesAfterSuccess(BrowserVariant variant) {
        runtime.verify(variant, "en", "blocksDuplicateRestoreAndRefreshesAfterSuccess", fixture -> {
            fixture.app.restore = new BrowserState.Restore(SELECTED);
            fixture.app.restore.paused = true;
            open(fixture);
            confirm(fixture);
            try {
                var status = fixture.page.locator("main").getByRole(AriaRole.STATUS);
                assertThat(status).containsText("Restoring backup: " + SELECTED);
                assertThat(status).isInViewport();
                assertThat(fixture.button("Restore: " + SELECTED)).isDisabled();
                assertThat(fixture.button("Restore: " + OTHER)).isDisabled();
                assertThat(fixture.button("Apply Config")).isDisabled();
                assertThat(fixture.button("Create backup")).isDisabled();
                assertThat(fixture.button("Reset Defaults")).isDisabled();
                fixture.button("Restore: " + OTHER).evaluate("button => button.click()");
                fixture.screenshot("pending");
                assertEquals(1, fixture.app.mutations().size());
                assertEquals("POST", fixture.app.mutations().getFirst().method());
                assertEquals(
                        "/api/config/backups/" + SELECTED + "/restore",
                        fixture.app.mutations().getFirst().path());
            } finally {
                fixture.app.finishRestore();
            }
            assertThat(restored(fixture)).containsText(SELECTED);
            assertThat(restored(fixture)).isInViewport();
            assertThat(fixture.page.locator("#config-workgroup")).hasValue("RESTORED");
            assertThat(fixture.page.getByText(
                            "smb.conf.backup_20261025_140000",
                            new com.microsoft.playwright.Page.GetByTextOptions().setExact(true)))
                    .isVisible();
            assertEquals(2, fixture.app.count("/api/config/global"));
            assertEquals(2, fixture.app.count("/api/config/backups"));
            assertEquals(1, fixture.app.mutations().size());
            fixture.screenshot("success");
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("restoreFailures")
    void reportsUncertainRestoreWithoutExposingDiagnostics(BrowserVariant variant, String failure) {
        runtime.verify(variant, "en", "reportsUncertainRestoreWithoutExposingDiagnostics-" + failure, fixture -> {
            fixture.app.restore = new BrowserState.Restore(SELECTED);
            fixture.app.restore.status = 500;
            fixture.app.restore.abort = failure.equals("lost response");
            fixture.app.restore.message = "Samba command failed: " + failure + " synthetic-sensitive-output";
            open(fixture);
            fixture.page.locator("#config-workgroup").fill("UNSAVED");
            confirm(fixture);
            var alert = fixture.page.locator("main").getByRole(AriaRole.ALERT);
            assertThat(alert).containsText("Restore not confirmed");
            assertThat(alert).containsText(SELECTED);
            assertThat(alert).isInViewport();
            assertThat(alert).containsText("Check the server configuration and service status before trying again");
            assertThat(fixture.page.getByText(Pattern.compile("synthetic-sensitive-output")))
                    .hasCount(0);
            assertThat(restored(fixture)).hasCount(0);
            assertThat(fixture.page.locator("#config-workgroup")).hasValue("UNSAVED");
            assertThat(fixture.button("Restore: " + SELECTED)).isEnabled();
            assertEquals(1, fixture.app.mutations().size());
            assertEquals(1, fixture.app.count("/api/config/global"));
            assertTrue(fixture.fits());
            fixture.screenshot("error");
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BrowserVariant.class)
    void warnsWhenRestoreSucceedsButRefreshFails(BrowserVariant variant) {
        runtime.verify(variant, "en", "warnsWhenRestoreSucceedsButRefreshFails", fixture -> {
            fixture.app.restore = new BrowserState.Restore(SELECTED);
            fixture.app.restore.failRefresh = true;
            open(fixture);
            confirm(fixture);
            assertThat(restored(fixture)).containsText(SELECTED);
            assertThat(restored(fixture)).isInViewport();
            assertThat(fixture.page.locator("main").getByRole(AriaRole.ALERT))
                    .containsText("displayed configuration may be out of date");
            assertThat(fixture.page.getByText(
                            "Restore not confirmed",
                            new com.microsoft.playwright.Page.GetByTextOptions().setExact(true)))
                    .hasCount(0);
            assertThat(fixture.page.getByText(Pattern.compile("synthetic-sensitive-refresh-output")))
                    .hasCount(0);
            assertThat(fixture.page.locator("#config-workgroup")).hasValue("TEAM");
            assertEquals(1, fixture.app.mutations().size());
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BrowserVariant.class)
    void identifiesSelectedBackupInRussianFailureMessage(BrowserVariant variant) {
        runtime.verify(variant, "en", "identifiesSelectedBackupInRussianFailureMessage", fixture -> {
            fixture.app.restore = new BrowserState.Restore(SELECTED);
            fixture.app.restore.status = 500;
            fixture.app.restore.message = "synthetic-sensitive-output";
            open(fixture);
            fixture.page
                    .getByRole(
                            AriaRole.BUTTON,
                            new com.microsoft.playwright.Page.GetByRoleOptions()
                                    .setName(Pattern.compile("^en$", Pattern.CASE_INSENSITIVE)))
                    .click();
            fixture.button("Восстановить: " + SELECTED).click();
            var dialog = fixture.page.getByRole(AriaRole.ALERTDIALOG);
            assertThat(dialog).containsText(SELECTED);
            assertThat(dialog).containsText("заменит smb.conf и перезапустит smbd");
            fixture.screenshot("confirmation-ru");
            button(dialog, "Восстановить").click();
            var alert = fixture.page.locator("main").getByRole(AriaRole.ALERT);
            assertThat(alert).containsText("Восстановление не подтверждено");
            assertThat(alert).containsText(SELECTED);
            assertThat(fixture.page.getByText(
                            "synthetic-sensitive-output",
                            new com.microsoft.playwright.Page.GetByTextOptions().setExact(true)))
                    .hasCount(0);
            assertEquals(1, fixture.app.mutations().size());
        });
    }

    static Stream<Arguments> restoreFailures() {
        return Arrays.stream(BrowserVariant.values())
                .flatMap(variant -> Stream.of("validation rejection", "restart failure", "lost response")
                        .map(failure -> Arguments.of(variant, failure)));
    }
}
