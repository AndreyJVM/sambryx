package sambryx.browser;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.AriaRole;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BackupCreationIT {
    private static final String CREATED_BACKUP = "smb.conf.backup_20261106_180000";
    private BrowserTestRuntime runtime;

    @BeforeAll
    void startBrowser() {
        runtime = new BrowserTestRuntime();
    }

    @AfterAll
    void closeBrowser() {
        if (runtime != null) runtime.close();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BrowserVariant.class)
    void createsFirstBackupWithoutSavingFormOrSendingDuplicateRequests(BrowserVariant variant) {
        runtime.verify(variant, "en", "createsFirstBackup", fixture -> {
            var pending = new AtomicReference<Route>();
            var requests = new AtomicInteger();
            fixture.page.route("**/api/config/backups", route -> {
                if (route.request().method().equals("POST")) {
                    requests.incrementAndGet();
                    pending.set(route);
                } else route.fallback();
            });
            fixture.go("/ui/config");
            assertThat(fixture.page.getByRole(
                            AriaRole.HEADING,
                            new com.microsoft.playwright.Page.GetByRoleOptions().setName("Configuration Backups")))
                    .isVisible();
            assertThat(fixture.page.getByText("No backups found.")).isVisible();
            fixture.page.locator("#config-workgroup").fill("UNSAVED");
            fixture.button("Create backup").click();
            fixture.page.waitForCondition(() -> pending.get() != null);
            try {
                assertThat(fixture.button("Creating backup…")).isDisabled();
                assertThat(fixture.button("Apply Config")).isDisabled();
                assertThat(fixture.button("Reset Defaults")).isDisabled();
                fixture.button("Creating backup…").evaluate("button => button.click()");
                assertEquals(1, requests.get());
                assertEquals(
                        "browser-fixture", pending.get().request().headers().get("x-xsrf-token"));
                fixture.app.backups = BrowserState.JSON.valueToTree(java.util.List.of(
                        Map.of("filename", CREATED_BACKUP, "createdAt", "2026-11-06 18:00:00", "size", "2 KB")));
            } finally {
                BrowserState.reply(pending.get(), null);
            }
            assertThat(fixture.page.getByRole(AriaRole.STATUS)).containsText("Backup created");
            assertThat(fixture.button("Restore: " + CREATED_BACKUP)).isVisible();
            assertThat(fixture.page.locator("#config-workgroup")).hasValue("UNSAVED");
            assertEquals(1, fixture.app.count("/api/config/global"));
            assertEquals(2, fixture.app.count("/api/config/backups"));
            assertTrue(fixture.app.mutations().isEmpty());
            assertTrue(fixture.fits());
            fixture.screenshot("created");
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BrowserVariant.class)
    void reportsCreationFailureWithoutExposingServerOutputOrRetrying(BrowserVariant variant) {
        runtime.verify(variant, "en", "reportsBackupCreationFailure", fixture -> {
            var requests = new AtomicInteger();
            fixture.page.route("**/api/config/backups", route -> {
                if (route.request().method().equals("POST")) {
                    requests.incrementAndGet();
                    BrowserState.reply(route, null, 500, "synthetic-sensitive-command-output");
                } else route.fallback();
            });
            fixture.go("/ui/config");
            fixture.page.locator("#config-workgroup").fill("UNSAVED");
            fixture.button("Create backup").click();
            assertThat(fixture.page.getByRole(AriaRole.ALERT)).containsText("Backup creation not confirmed");
            assertThat(fixture.page.getByText("synthetic-sensitive-command-output"))
                    .hasCount(0);
            assertThat(fixture.button("Create backup")).isEnabled();
            assertThat(fixture.page.locator("#config-workgroup")).hasValue("UNSAVED");
            assertEquals(1, requests.get());
            assertEquals(1, fixture.app.count("/api/config/backups"));
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BrowserVariant.class)
    void distinguishesSuccessfulCreationFromFailedListRefresh(BrowserVariant variant) {
        runtime.verify(variant, "en", "warnsOnBackupListRefreshFailure", fixture -> {
            var created = new AtomicBoolean();
            fixture.page.route("**/api/config/backups", route -> {
                if (route.request().method().equals("POST")) {
                    created.set(true);
                    BrowserState.reply(route, null);
                } else if (created.get()) BrowserState.reply(route, null, 503, "synthetic-sensitive-output");
                else route.fallback();
            });
            fixture.go("/ui/config");
            fixture.button("Create backup").click();
            assertThat(fixture.page
                            .getByRole(AriaRole.STATUS)
                            .filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText("Backup created")))
                    .isVisible();
            assertThat(fixture.page.getByText(
                            "The backup list is unavailable. This does not mean there are no backups."))
                    .isVisible();
            assertThat(fixture.page.getByText("No backups found.")).hasCount(0);
            assertThat(fixture.page.getByRole(AriaRole.ALERT)).hasCount(0);
            assertThat(fixture.page.getByText("synthetic-sensitive-output")).hasCount(0);
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BrowserVariant.class)
    void keepsBackupSectionVisibleWhenListingFails(BrowserVariant variant) {
        runtime.verify(variant, "en", "showsUnavailableBackupList", fixture -> {
            fixture.page.route(
                    "**/api/config/backups", route -> BrowserState.reply(route, null, 503, "Fixture unavailable"));
            fixture.go("/ui/config");
            assertThat(fixture.button("Create backup")).isVisible();
            assertThat(fixture.page.getByText("No backups found.")).hasCount(0);
            assertThat(fixture.page.getByText(
                            "The backup list is unavailable. This does not mean there are no backups."))
                    .isVisible();
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BrowserVariant.class)
    void showsBackupCreationInRussian(BrowserVariant variant) {
        runtime.verify(variant, "ru", "createsBackupInRussian", fixture -> {
            fixture.page.route("**/api/config/backups", route -> {
                if (route.request().method().equals("POST")) BrowserState.reply(route, null);
                else route.fallback();
            });
            fixture.go("/ui/config");
            fixture.button("Создать резервную копию").click();
            assertThat(fixture.page.getByRole(AriaRole.STATUS)).containsText("Резервная копия создана");
            assertTrue(fixture.fits());
        });
    }
}
