package sambryx.browser;

import static org.junit.jupiter.api.Assertions.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.ColorScheme;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BrandingIT {
    private Playwright playwright;
    private Browser browser;

    @BeforeAll
    void start() {
        playwright = Playwright.create(
                new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
        var options = new BrowserType.LaunchOptions();
        String channel = System.getenv("PLAYWRIGHT_CHANNEL");
        if (channel != null && !channel.isBlank()) options.setChannel(channel);
        browser = playwright.chromium().launch(options);
    }

    @AfterAll
    void stop() {
        if (playwright != null) playwright.close();
    }

    @TestFactory
    Stream<DynamicTest> logosFitDesktopAndMobile() {
        return List.of(320, 1440).stream().flatMap(width -> List.of("light", "dark").stream()
                .map(theme -> DynamicTest.dynamicTest(theme + " branding at " + width, () -> {
                    Path output = Path.of("target/browser-artifacts/branding");
                    Files.createDirectories(output);
                    try (var context = browser.newContext(new Browser.NewContextOptions()
                            .setViewportSize(width, 400)
                            .setColorScheme(theme.equals("dark") ? ColorScheme.DARK : ColorScheme.LIGHT))) {
                        var page = context.newPage();
                        page.route("**/*", route -> route.abort());
                        var html = new StringBuilder("<body style='margin:16px;background:"
                                + (theme.equals("dark") ? "#14181b" : "#fff") + "'>");
                        for (String name : List.of("logo-full-" + theme, "logo-mark", "favicon")) {
                            byte[] svg = Files.readAllBytes(Path.of(
                                    System.getProperty("browser.root"), "frontend/public/sambryx", name + ".svg"));
                            html.append("<div><img alt='")
                                    .append(name)
                                    .append("' src='data:image/svg+xml;base64,")
                                    .append(Base64.getEncoder().encodeToString(svg))
                                    .append("' style='max-width:100%;height:64px'></div>");
                        }
                        page.setContent(html.toString());
                        page.locator("img").evaluateAll("images => Promise.all(images.map(image => image.decode()))");
                        assertEquals(3, page.locator("img").count());
                        for (var image : page.locator("img").all()) {
                            var box = image.boundingBox();
                            assertNotNull(box);
                            assertTrue(box.width > 0 && box.height > 0 && box.x >= 0 && box.x + box.width <= width);
                        }
                        assertTrue((Boolean) page.evaluate("() => document.documentElement.scrollWidth <= innerWidth"));
                        page.screenshot(
                                new Page.ScreenshotOptions().setPath(output.resolve(theme + "-" + width + ".png")));
                    }
                })));
    }
}
