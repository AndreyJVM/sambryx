package sambryx.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;

final class BrowserTestRuntime implements AutoCloseable {
    private final BrowserServer server;
    private final Playwright playwright;
    private final Browser browser;

    BrowserTestRuntime() {
        server = new BrowserServer(Path.of(System.getProperty("browser.root")).resolve("frontend/dist"));
        Playwright started = null;
        try {
            started = Playwright.create(
                    new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
            var options = new BrowserType.LaunchOptions();
            String channel = System.getenv("PLAYWRIGHT_CHANNEL");
            if (channel != null && !channel.isBlank()) options.setChannel(channel);
            browser = started.chromium().launch(options);
            playwright = started;
        } catch (RuntimeException | Error failure) {
            if (started != null) started.close();
            server.close();
            throw failure;
        }
    }

    void verify(BrowserVariant variant, String language, String name, Consumer<BrowserFixture> assertions) {
        try (var fixture = new BrowserFixture(browser, server.origin().toString(), variant, name, language)) {
            try {
                assertions.accept(fixture);
            } catch (RuntimeException | AssertionError failure) {
                fixture.failed = true;
                throw failure;
            }
        }
    }

    @Override
    public void close() {
        try {
            playwright.close();
        } finally {
            server.close();
        }
    }
}
