package sambryx.browser;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static sambryx.browser.BrowserFixture.button;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.AriaRole;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class BackupCases {
    private static final String SELECTED = "smb.conf.backup_20261024_123456";
    private static final String OTHER = "smb.conf.backup_20261023_123456";

    static List<BrowserCase> cases() {
        var cases = new ArrayList<BrowserCase>();
        cases.add(new BrowserCase("restore confirmation cancellation preserves state and focus", BackupCases::cancel));
        cases.add(new BrowserCase("pending restore blocks duplicates and refreshes on success", BackupCases::pending));
        for (String failure : List.of("validation rejection", "restart failure", "lost response"))
            cases.add(new BrowserCase(failure + " gives safe uncertain restore result", f -> failure(f, failure)));
        cases.add(new BrowserCase("successful restore warns on failed refresh", BackupCases::refresh));
        cases.add(new BrowserCase("Russian restore identifies backup and uncertain failure", BackupCases::russian));
        return cases;
    }

    private static void open(BrowserFixture f) {
        f.app.backups = BrowserState.json("[{\"filename\":\"" + SELECTED
                + "\",\"createdAt\":\"2026-10-24 12:34:56\",\"size\":\"2 KB\"},{\"filename\":\"" + OTHER
                + "\",\"createdAt\":\"2026-10-24 12:34:56\",\"size\":\"2 KB\"}]");
        f.go("/ui/config");
        assertThat(f.button("Restore: " + SELECTED)).isVisible();
    }

    private static void confirm(BrowserFixture f) {
        f.button("Restore: " + SELECTED).click();
        button(f.page.getByRole(AriaRole.ALERTDIALOG), "Restore").click();
    }

    private static Locator restored(BrowserFixture f) {
        return f.page.getByRole(AriaRole.STATUS).filter(new Locator.FilterOptions().setHasText("Backup restored"));
    }

    private static void cancel(BrowserFixture f) {
        open(f);
        for (String action : List.of("button", "escape")) {
            f.button("Restore: " + SELECTED).click();
            var dialog = f.page.getByRole(AriaRole.ALERTDIALOG);
            assertThat(dialog).containsText(SELECTED);
            assertThat(dialog).not().containsText(OTHER);
            assertThat(dialog).containsText("replace smb.conf");
            assertThat(dialog).containsText("restart smbd");
            assertThat(dialog).containsText("Unsaved form changes");
            f.screenshot("confirmation-" + action);
            if (action.equals("button")) button(dialog, "Cancel").click();
            else f.page.keyboard().press("Escape");
            assertThat(dialog).hasCount(0);
            assertThat(f.button("Restore: " + SELECTED)).isFocused();
        }
        assertTrue(f.app.mutations().isEmpty());
    }

    private static void pending(BrowserFixture f) {
        f.app.restore = new BrowserState.Restore(SELECTED);
        f.app.restore.paused = true;
        open(f);
        confirm(f);
        try {
            var status = f.page.locator("main").getByRole(AriaRole.STATUS);
            assertThat(status).containsText("Restoring backup: " + SELECTED);
            assertThat(status).isInViewport();
            assertThat(f.button("Restore: " + SELECTED)).isDisabled();
            assertThat(f.button("Restore: " + OTHER)).isDisabled();
            assertThat(f.button("Apply Config")).isDisabled();
            assertThat(f.button("Reset Defaults")).isDisabled();
            f.button("Restore: " + OTHER).evaluate("button => button.click()");
            f.screenshot("pending");
            assertEquals(1, f.app.mutations().size());
            assertEquals("POST", f.app.mutations().getFirst().method());
            assertEquals(
                    "/api/config/backups/" + SELECTED + "/restore",
                    f.app.mutations().getFirst().path());
        } finally {
            f.app.finishRestore();
        }
        assertThat(restored(f)).containsText(SELECTED);
        assertThat(restored(f)).isInViewport();
        assertThat(f.page.locator("#config-workgroup")).hasValue("RESTORED");
        assertThat(f.page.getByText(
                        "smb.conf.backup_20261025_140000",
                        new com.microsoft.playwright.Page.GetByTextOptions().setExact(true)))
                .isVisible();
        assertEquals(2, f.app.count("/api/config/global"));
        assertEquals(2, f.app.count("/api/config/backups"));
        assertEquals(1, f.app.mutations().size());
        f.screenshot("success");
    }

    private static void failure(BrowserFixture f, String failure) {
        f.app.restore = new BrowserState.Restore(SELECTED);
        f.app.restore.status = 500;
        f.app.restore.abort = failure.equals("lost response");
        f.app.restore.message = "Samba command failed: " + failure + " synthetic-sensitive-output";
        open(f);
        f.page.locator("#config-workgroup").fill("UNSAVED");
        confirm(f);
        var alert = f.page.locator("main").getByRole(AriaRole.ALERT);
        assertThat(alert).containsText("Restore not confirmed");
        assertThat(alert).containsText(SELECTED);
        assertThat(alert).isInViewport();
        assertThat(alert).containsText("Check the server configuration and service status before trying again");
        assertThat(f.page.getByText(Pattern.compile("synthetic-sensitive-output")))
                .hasCount(0);
        assertThat(restored(f)).hasCount(0);
        assertThat(f.page.locator("#config-workgroup")).hasValue("UNSAVED");
        assertThat(f.button("Restore: " + SELECTED)).isEnabled();
        assertEquals(1, f.app.mutations().size());
        assertEquals(1, f.app.count("/api/config/global"));
        assertTrue(f.fits());
        f.screenshot("error");
    }

    private static void refresh(BrowserFixture f) {
        f.app.restore = new BrowserState.Restore(SELECTED);
        f.app.restore.failRefresh = true;
        open(f);
        confirm(f);
        assertThat(restored(f)).containsText(SELECTED);
        assertThat(restored(f)).isInViewport();
        assertThat(f.page.locator("main").getByRole(AriaRole.ALERT))
                .containsText("displayed configuration may be out of date");
        assertThat(f.page.getByText(
                        "Restore not confirmed", new com.microsoft.playwright.Page.GetByTextOptions().setExact(true)))
                .hasCount(0);
        assertThat(f.page.getByText(Pattern.compile("synthetic-sensitive-refresh-output")))
                .hasCount(0);
        assertThat(f.page.locator("#config-workgroup")).hasValue("TEAM");
        assertEquals(1, f.app.mutations().size());
    }

    private static void russian(BrowserFixture f) {
        f.app.restore = new BrowserState.Restore(SELECTED);
        f.app.restore.status = 500;
        f.app.restore.message = "synthetic-sensitive-output";
        open(f);
        f.page.getByRole(
                        AriaRole.BUTTON,
                        new com.microsoft.playwright.Page.GetByRoleOptions()
                                .setName(Pattern.compile("^en$", Pattern.CASE_INSENSITIVE)))
                .click();
        f.button("Восстановить: " + SELECTED).click();
        var dialog = f.page.getByRole(AriaRole.ALERTDIALOG);
        assertThat(dialog).containsText(SELECTED);
        assertThat(dialog).containsText("заменит smb.conf и перезапустит smbd");
        f.screenshot("confirmation-ru");
        button(dialog, "Восстановить").click();
        var alert = f.page.locator("main").getByRole(AriaRole.ALERT);
        assertThat(alert).containsText("Восстановление не подтверждено");
        assertThat(alert).containsText(SELECTED);
        assertThat(f.page.getByText(
                        "synthetic-sensitive-output",
                        new com.microsoft.playwright.Page.GetByTextOptions().setExact(true)))
                .hasCount(0);
        assertEquals(1, f.app.mutations().size());
    }
}
