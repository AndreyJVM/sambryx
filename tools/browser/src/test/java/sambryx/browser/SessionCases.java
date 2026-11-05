package sambryx.browser;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static sambryx.browser.BrowserFixture.button;

import com.microsoft.playwright.options.AriaRole;
import java.util.List;
import java.util.regex.Pattern;

final class SessionCases {
    static List<BrowserCase> cases() {
        return List.of(
                new BrowserCase("idle warning renews only on explicit continue", SessionCases::warning),
                new BrowserCase("expired renewal redirects without reconnect", SessionCases::expired),
                new BrowserCase("device sleep checks server without renewal", SessionCases::sleep),
                new BrowserCase("renewal and logout propagate across tabs", SessionCases::tabs),
                new BrowserCase("Russian session warning remains usable", "ru", SessionCases::russian),
                new BrowserCase("initial route and mouse movement never renew", SessionCases::background));
    }

    private static void warning(BrowserFixture f) {
        f.app.sessionRemaining = 61000;
        f.page.clock().install();
        f.go("/ui/shares");
        assertThat(f.page.locator("main h1")).isVisible();
        f.page.clock().fastForward(2000);
        var warning = f.page.getByRole(AriaRole.DIALOG);
        assertThat(warning).isVisible();
        assertEquals(0, f.app.count("/api/auth/activity"));
        f.screenshot("session-warning");
        button(warning, "Continue work").click();
        assertThat(warning).isHidden();
        assertEquals(1, f.app.count("/api/auth/activity"));
    }

    private static void expired(BrowserFixture f) {
        f.app.sessionRemaining = 50000;
        f.app.renewalDenied = true;
        f.go("/ui/shares");
        f.button("Continue work").click();
        assertThat(f.page).hasURL(Pattern.compile("/ui/login$"));
        assertEquals(1, f.app.count("/api/auth/activity"));
        assertEquals(0, f.app.count("/api/auth/login"));
    }

    private static void sleep(BrowserFixture f) {
        f.app.sessionRemaining = 61000;
        f.page.clock().install();
        f.go("/ui/shares");
        assertThat(f.page.locator("main h1")).isVisible();
        f.app.authenticated = false;
        f.page.clock().fastForward(62000);
        assertThat(f.page).hasURL(Pattern.compile("/ui/login$"));
        assertEquals(0, f.app.count("/api/auth/activity"));
        assertEquals(0, f.app.count("/api/auth/login"));
    }

    private static void tabs(BrowserFixture f) {
        f.app.sessionRemaining = 50000;
        f.go("/ui/shares");
        try (var second = f.context.newPage()) {
            second.navigate("/ui/shares");
            var warning = f.page.getByRole(AriaRole.DIALOG);
            assertThat(warning).isVisible();
            second.getByRole(
                            AriaRole.BUTTON,
                            new com.microsoft.playwright.Page.GetByRoleOptions()
                                    .setName("Continue work")
                                    .setExact(true))
                    .click();
            assertThat(warning).isHidden();
            assertEquals(1, f.app.count("/api/auth/activity"));
            second.getByRole(
                            AriaRole.BUTTON,
                            new com.microsoft.playwright.Page.GetByRoleOptions()
                                    .setName("Sign Out")
                                    .setExact(true))
                    .click();
            assertThat(second).hasURL(Pattern.compile("/ui/login$"));
            assertThat(f.page).hasURL(Pattern.compile("/ui/login$"));
            assertEquals(0, f.app.count("/api/auth/login"));
        }
    }

    private static void russian(BrowserFixture f) {
        f.app.sessionRemaining = 50000;
        f.go("/ui/shares");
        var warning = f.page.getByRole(AriaRole.DIALOG);
        assertThat(warning).containsText("Сессия скоро завершится");
        assertThat(button(warning, "Продолжить работу")).isVisible();
        f.screenshot("session-warning-ru");
    }

    private static void background(BrowserFixture f) {
        f.go("/ui/");
        assertThat(f.page).hasURL(Pattern.compile("/ui/shares$"));
        f.page.waitForCondition(() -> f.app.count("/api/auth/session") > 0);
        f.page.mouse().move(20, 20);
        assertEquals(0, f.app.count("/api/auth/activity"));
    }
}
