package sambryx.browser;

import com.microsoft.playwright.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BrowserSuiteIT {
    private Playwright playwright;
    private Browser browser;
    private BrowserServer server;
    static final List<BrowserFixture.Variant> VARIANTS = List.of(
            new BrowserFixture.Variant("light", false), new BrowserFixture.Variant("light", true),
            new BrowserFixture.Variant("dark", false), new BrowserFixture.Variant("dark", true));

    @BeforeAll
    void start() {
        server = new BrowserServer(Path.of(System.getProperty("browser.root")).resolve("frontend/dist"));
        playwright = Playwright.create(
                new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
        var options = new BrowserType.LaunchOptions();
        String channel = System.getenv("PLAYWRIGHT_CHANNEL");
        if (channel != null && !channel.isBlank()) options.setChannel(channel);
        browser = playwright.chromium().launch(options);
    }

    @AfterAll
    void stop() {
        try {
            if (playwright != null) playwright.close();
        } finally {
            if (server != null) server.close();
        }
    }

    @TestFactory
    Stream<DynamicTest> workspace() {
        return tests(WorkspaceCases.cases());
    }

    @TestFactory
    Stream<DynamicTest> sessions() {
        return tests(SessionCases.cases());
    }

    @TestFactory
    Stream<DynamicTest> backups() {
        return tests(BackupCases.cases());
    }

    @TestFactory
    Stream<DynamicTest> access() {
        return tests(ShareAccessCases.cases());
    }

    private Stream<DynamicTest> tests(List<BrowserCase> scenarios) {
        return scenarios.stream().flatMap(scenario -> VARIANTS.stream()
                .map(variant -> DynamicTest.dynamicTest(scenario.name() + " [" + variant.label() + "]", () -> {
                    try (var fixture =
                            new BrowserFixture(browser, server.origin().toString(), variant, scenario)) {
                        try {
                            scenario.action().accept(fixture);
                        } catch (RuntimeException | AssertionError error) {
                            fixture.failed = true;
                            throw error;
                        }
                    }
                })));
    }
}
