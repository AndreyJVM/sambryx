package sambryx.browser;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static sambryx.browser.BrowserFixture.button;

import com.microsoft.playwright.options.AriaRole;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdministratorSessionIT {
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
    void renewsSessionOnlyAfterExplicitConfirmation(BrowserVariant variant) {
        runtime.verify(variant, "en", "renewsSessionOnlyAfterExplicitConfirmation", fixture -> {
            fixture.app.sessionRemaining = 61000;
            fixture.page.clock().install();
            fixture.go("/ui/shares");
            assertThat(fixture.page.locator("main h1")).isVisible();
            fixture.page.clock().fastForward(2000);
            var warning = fixture.page.getByRole(AriaRole.DIALOG);
            assertThat(warning).isVisible();
            assertEquals(0, fixture.app.count("/api/auth/activity"));
            fixture.screenshot("session-warning");
            button(warning, "Continue work").click();
            assertThat(warning).isHidden();
            assertEquals(1, fixture.app.count("/api/auth/activity"));
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BrowserVariant.class)
    void redirectsWhenSessionRenewalIsRejected(BrowserVariant variant) {
        runtime.verify(variant, "en", "redirectsWhenSessionRenewalIsRejected", fixture -> {
            fixture.app.sessionRemaining = 50000;
            fixture.app.renewalDenied = true;
            fixture.go("/ui/shares");
            fixture.button("Continue work").click();
            assertThat(fixture.page).hasURL(Pattern.compile("/ui/login$"));
            assertEquals(1, fixture.app.count("/api/auth/activity"));
            assertEquals(0, fixture.app.count("/api/auth/login"));
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BrowserVariant.class)
    void checksExpiredSessionAfterDeviceSleepWithoutRenewal(BrowserVariant variant) {
        runtime.verify(variant, "en", "checksExpiredSessionAfterDeviceSleepWithoutRenewal", fixture -> {
            fixture.app.sessionRemaining = 61000;
            fixture.page.clock().install();
            fixture.go("/ui/shares");
            assertThat(fixture.page.locator("main h1")).isVisible();
            fixture.app.authenticated = false;
            fixture.page.clock().fastForward(62000);
            assertThat(fixture.page).hasURL(Pattern.compile("/ui/login$"));
            assertEquals(0, fixture.app.count("/api/auth/activity"));
            assertEquals(0, fixture.app.count("/api/auth/login"));
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BrowserVariant.class)
    void propagatesRenewalAndLogoutAcrossTabs(BrowserVariant variant) {
        runtime.verify(variant, "en", "propagatesRenewalAndLogoutAcrossTabs", fixture -> {
            fixture.app.sessionRemaining = 50000;
            fixture.go("/ui/shares");
            try (var second = fixture.context.newPage()) {
                second.navigate("/ui/shares");
                var warning = fixture.page.getByRole(AriaRole.DIALOG);
                assertThat(warning).isVisible();
                second.getByRole(
                                AriaRole.BUTTON,
                                new com.microsoft.playwright.Page.GetByRoleOptions()
                                        .setName("Continue work")
                                        .setExact(true))
                        .click();
                assertThat(warning).isHidden();
                assertEquals(1, fixture.app.count("/api/auth/activity"));
                second.getByRole(
                                AriaRole.BUTTON,
                                new com.microsoft.playwright.Page.GetByRoleOptions()
                                        .setName("Sign Out")
                                        .setExact(true))
                        .click();
                assertThat(second).hasURL(Pattern.compile("/ui/login$"));
                assertThat(fixture.page).hasURL(Pattern.compile("/ui/login$"));
                assertEquals(0, fixture.app.count("/api/auth/login"));
            }
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BrowserVariant.class)
    void showsRussianSessionWarning(BrowserVariant variant) {
        runtime.verify(variant, "ru", "showsRussianSessionWarning", fixture -> {
            fixture.app.sessionRemaining = 50000;
            fixture.go("/ui/shares");
            var warning = fixture.page.getByRole(AriaRole.DIALOG);
            assertThat(warning).containsText("Сессия скоро завершится");
            assertThat(button(warning, "Продолжить работу")).isVisible();
            fixture.screenshot("session-warning-ru");
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BrowserVariant.class)
    void doesNotRenewOnNavigationOrMouseMovement(BrowserVariant variant) {
        runtime.verify(variant, "en", "doesNotRenewOnNavigationOrMouseMovement", fixture -> {
            fixture.go("/ui/");
            assertThat(fixture.page).hasURL(Pattern.compile("/ui/shares$"));
            fixture.page.waitForCondition(() -> fixture.app.count("/api/auth/session") > 0);
            fixture.page.mouse().move(20, 20);
            assertEquals(0, fixture.app.count("/api/auth/activity"));
        });
    }
}
