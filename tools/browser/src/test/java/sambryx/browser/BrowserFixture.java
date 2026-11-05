package sambryx.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class BrowserFixture implements AutoCloseable {
    record Variant(String theme, boolean mobile) {
        String label() {
            return (mobile ? "mobile-" : "desktop-") + theme;
        }
    }

    final BrowserContext context;
    final Page page;
    final BrowserState app;
    final Variant variant;
    final Path output;
    boolean failed;

    BrowserFixture(Browser browser, String origin, Variant variant, BrowserCase scenario) {
        this.variant = variant;
        output = Path.of(
                "target",
                "browser-artifacts",
                scenario.name().replaceAll("[^a-zA-Z0-9-]", "-") + "-" + variant.label());
        try {
            Files.createDirectories(output);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        context = browser.newContext(new Browser.NewContextOptions()
                .setBaseURL(origin)
                .setLocale("en-US")
                .setViewportSize(variant.mobile() ? 320 : 1440, variant.mobile() ? 844 : 1000)
                .setIsMobile(variant.mobile())
                .setHasTouch(variant.mobile())
                .setColorScheme(variant.theme().equals("dark") ? ColorScheme.DARK : ColorScheme.LIGHT)
                .setServiceWorkers(ServiceWorkerPolicy.BLOCK));
        context.setDefaultTimeout(5000);
        context.setDefaultNavigationTimeout(20000);
        app = new BrowserState(origin);
        context.onPage(p -> p.onPageError(app.browserErrors::add));
        context.addInitScript("if (location.origin === '" + origin + "') { localStorage.setItem('samba_lang','"
                + scenario.language() + "'); if(!localStorage.getItem('theme')) localStorage.setItem('theme','"
                + variant.theme() + "'); }");
        context.route("**/*", app::route);
        context.tracing()
                .start(new Tracing.StartOptions()
                        .setScreenshots(true)
                        .setSnapshots(true)
                        .setSources(true));
        page = context.newPage();
    }

    void go(String route) {
        page.navigate(route);
    }

    Locator button(String name) {
        return page.getByRole(
                AriaRole.BUTTON, new Page.GetByRoleOptions().setName(name).setExact(true));
    }

    static Locator button(Locator scope, String name) {
        return scope.getByRole(
                AriaRole.BUTTON, new Locator.GetByRoleOptions().setName(name).setExact(true));
    }

    void screenshot(String name) {
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(output.resolve(name + ".png"))
                .setFullPage(true));
    }

    boolean fits() {
        return (Boolean) page.evaluate("() => document.documentElement.scrollWidth <= innerWidth");
    }

    void loginFields(String authType, String credential) {
        page.locator("#login-host").fill("files.example.test");
        page.locator("#login-username").fill("operator");
        if (authType.equals("key")) {
            button("SSH-ключ").click();
            page.locator("textarea").fill(credential);
        } else page.locator("#login-password").fill(credential);
    }

    @Override
    public void close() {
        failed = failed || !app.unexpected.isEmpty() || !app.browserErrors.isEmpty();
        try {
            if (failed && !page.isClosed()) screenshot("failure");
            context.tracing()
                    .stop(
                            failed
                                    ? new Tracing.StopOptions().setPath(output.resolve("trace.zip"))
                                    : new Tracing.StopOptions());
        } finally {
            context.close();
        }
        assertEquals(java.util.List.of(), app.unexpected, "No unexpected API or external requests");
        assertEquals(java.util.List.of(), app.browserErrors, "No uncaught browser errors");
    }
}
