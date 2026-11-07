package sambryx.browser;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BackupComparisonIT {
    private static final String NAME = "smb.conf.backup_20261106_180000";
    private BrowserTestRuntime runtime;

    @BeforeAll
    void startBrowser() {
        runtime = new BrowserTestRuntime();
    }

    @AfterAll
    void closeBrowser() {
        if (runtime != null) runtime.close();
    }

    @ParameterizedTest
    @EnumSource(BrowserVariant.class)
    void blocksDuplicateReadsAndShowsRussianResult(BrowserVariant variant) {
        runtime.verify(variant, "ru", "comparesBackupInRussian", fixture -> {
            fixture.app.backups =
                    BrowserState.JSON.valueToTree(List.of(new BackupEntry(NAME, "2026-11-06 18:00:00", "2 KB")));
            var pending = new java.util.concurrent.atomic.AtomicReference<com.microsoft.playwright.Route>();
            var reads = new AtomicInteger();
            fixture.page.route("**/api/config/backups/*/compare", route -> {
                reads.incrementAndGet();
                pending.set(route);
            });
            fixture.go("/ui/config");
            fixture.button("Сравнить: " + NAME).click();
            fixture.page.waitForCondition(() -> pending.get() != null);
            try {
                assertThat(fixture.button("Сравнить: " + NAME)).isDisabled();
                assertThat(fixture.button("Восстановить: " + NAME)).isDisabled();
                fixture.button("Сравнить: " + NAME).evaluate("button => button.click()");
                assertEquals(1, reads.get());
            } finally {
                BrowserState.reply(pending.get(), Map.of("filename", NAME, "equal", true, "changes", List.of()));
            }
            assertThat(fixture.page.getByText("Файлы одинаковы.")).isVisible();
            assertThat(fixture.page.getByText("Текущий серверный smb.conf → выбранная копия"))
                    .isVisible();
            assertThat(fixture.button("Восстановить: " + NAME)).isEnabled();
            assertTrue(fixture.app.mutations().isEmpty());
            assertTrue(fixture.fits());
        });
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "null",
                "{}",
                "{\"filename\":\"smb.conf.backup_20261106_180000\",\"equal\":true,\"changes\":[{}]}"
            })
    void rejectsMalformedComparison(String payload) {
        runtime.verify(BrowserVariant.DESKTOP_LIGHT, "en", "rejectsMalformedComparison", fixture -> {
            fixture.app.backups =
                    BrowserState.JSON.valueToTree(List.of(new BackupEntry(NAME, "2026-11-06 18:00:00", "2 KB")));
            fixture.page.route(
                    "**/api/config/backups/*/compare", route -> BrowserState.reply(route, BrowserState.json(payload)));
            fixture.go("/ui/config");
            fixture.button("Compare: " + NAME).click();
            assertThat(fixture.page.getByRole(com.microsoft.playwright.options.AriaRole.ALERT))
                    .containsText("Comparison unavailable");
            assertThat(fixture.page.getByText("Files are identical.")).hasCount(0);
            assertTrue(fixture.app.mutations().isEmpty());
        });
    }

    @ParameterizedTest
    @EnumSource(BrowserVariant.class)
    void comparesLiteralTextThenEqualityAndSafeFailureWithoutMutations(BrowserVariant variant) {
        runtime.verify(variant, "en", "comparesBackup", fixture -> {
            fixture.app.backups =
                    BrowserState.JSON.valueToTree(List.of(new BackupEntry(NAME, "2026-11-06 18:00:00", "2 KB")));
            var reads = new AtomicInteger();
            String html = "<img src=x onerror=alert(1)>" + "x".repeat(500);
            fixture.page.route("**/api/config/backups/*/compare", route -> {
                assertEquals("GET", route.request().method());
                switch (reads.incrementAndGet()) {
                    case 1 -> BrowserState.reply(
                            route,
                            Map.of(
                                    "filename",
                                    NAME,
                                    "equal",
                                    false,
                                    "changes",
                                    List.of(
                                            Map.of("type", "REMOVED", "lineNumber", 2, "text", "name = current\n"),
                                            Map.of("type", "ADDED", "lineNumber", 2, "text", html))));
                    case 2 -> BrowserState.reply(route, Map.of("filename", NAME, "equal", true, "changes", List.of()));
                    default -> BrowserState.reply(route, null, 500, "synthetic-sensitive-output");
                }
            });
            fixture.go("/ui/config");
            fixture.page.locator("#config-workgroup").fill("UNSAVED");
            fixture.button("Compare: " + NAME).click();
            assertThat(fixture.page.getByText("Current server smb.conf → selected backup"))
                    .isVisible();
            assertThat(fixture.page.getByText(
                            html, new com.microsoft.playwright.Page.GetByTextOptions().setExact(false)))
                    .isVisible();
            assertThat(fixture.page.locator("[data-comparison] img")).hasCount(0);
            assertThat(fixture.page.locator("[data-comparison]")).containsText("− 2: name = current");
            assertTrue(fixture.fits());
            fixture.screenshot("differences");
            fixture.button("Compare: " + NAME).click();
            assertThat(fixture.page.getByText("Files are identical.")).isVisible();
            assertThat(fixture.page.getByText(html)).hasCount(0);
            fixture.button("Compare: " + NAME).click();
            assertThat(fixture.page.getByRole(com.microsoft.playwright.options.AriaRole.ALERT))
                    .containsText("Comparison unavailable");
            assertThat(fixture.page.getByText("Files are identical.")).hasCount(0);
            assertThat(fixture.page.getByText("synthetic-sensitive-output")).hasCount(0);
            assertThat(fixture.page.locator("#config-workgroup")).hasValue("UNSAVED");
            assertTrue(fixture.app.mutations().isEmpty());
            assertEquals(1, fixture.app.count("/api/config/global"));
            assertEquals(3, reads.get());
        });
    }
}
