package sambryx.browser;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static sambryx.browser.BrowserFixture.button;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class StorageIT {
    @DisplayName("Storage navigation supports keyboard access, active state and Dashboard shortcut")
    @ParameterizedTest
    @EnumSource(BrowserVariant.class)
    void navigatesFromOverview(BrowserVariant variant) {
        try (var runtime = new BrowserTestRuntime()) {
            runtime.verify(variant, "en", "storage-navigation", f -> {
                f.go("/ui/dashboard");
                assertThat(f.page.locator("main h1")).isVisible();
                assertThat(f.page.locator(".file-manager")).hasCount(0);
                assertEquals(0, f.app.count("/api/fs/browse"));
                assertThat(button(f.page.locator("main"), "Stop")).isEnabled();
                f.screenshot("overview");
                var nav = f.page.getByRole(AriaRole.NAVIGATION, new Page.GetByRoleOptions().setName("Main navigation"));
                var link = nav.locator("a[href='/ui/storage']");
                assertThat(link).hasText("Storage and files");
                link.focus();
                assertThat(link).isFocused();
                f.page.keyboard().press("Enter");
                assertThat(f.page).hasURL(Pattern.compile("/ui/storage$"));
                assertThat(link).hasAttribute("aria-current", "page");
                assertThat(f.page.locator(".file-manager")).hasCount(1);
                f.go("/ui/dashboard");
                f.page.locator("main a[href='/ui/storage']").click();
                assertThat(f.page.locator("main h1")).hasText("Storage and files");
                assertTrue(f.fits());
                f.page.locator("main").evaluate("element => element.focus({preventScroll: true})");
                f.page.evaluate("window.scrollTo(0, 0)");
                f.screenshot("page");
            });
        }
    }

    @DisplayName("Russian storage direct entry and reload retain both original path roots")
    @ParameterizedTest
    @EnumSource(BrowserVariant.class)
    void opensDirectlyAndReloads(BrowserVariant variant) {
        try (var runtime = new BrowserTestRuntime()) {
            runtime.verify(variant, "ru", "storage-direct-ru", f -> {
                f.go("/ui/storage");
                assertThat(f.page.locator("main h1")).hasText("Диски и файлы");
                assertThat(f.page.locator(".file-manager table")).hasCount(2);
                assertThat(f.page.locator("nav [aria-current=page]:visible")).hasText("Диски и файлы");
                f.page.reload();
                assertThat(f.page.locator(".file-manager table")).hasCount(2);
                assertThat(f.page.locator(".file-manager")).containsText("home");
                assertTrue(f.app.requests.stream().anyMatch(r -> r.path().equals("/api/fs/browse")));
                assertTrue(f.fits());
                f.page.locator("main").evaluate("element => element.focus({preventScroll: true})");
                f.page.evaluate("window.scrollTo(0, 0)");
                f.screenshot("page");
            });
        }
    }

    @DisplayName("Storage preserves browsing and copy API payloads with synthetic files only")
    @ParameterizedTest
    @EnumSource(BrowserVariant.class)
    void browsesAndCopies(BrowserVariant variant) {
        try (var runtime = new BrowserTestRuntime()) {
            runtime.verify(variant, "en", "storage-browse-copy", f -> {
                var bodies = new ArrayList<String>();
                f.page.route("**/api/fs/copy", route -> {
                    bodies.add(route.request().postData());
                    BrowserState.reply(route, null);
                });
                f.go("/ui/storage");
                var left = f.page.locator(".file-manager > div").first();
                assertThat(left.getByText(
                                "team", new com.microsoft.playwright.Locator.GetByTextOptions().setExact(true)))
                        .isVisible();
                left.getByText("team", new com.microsoft.playwright.Locator.GetByTextOptions().setExact(true))
                        .click();
                assertThat(button(left, "samba")).isVisible();
                button(left, "Select: team").click();
                for (String action : List.of("Download", "Copy", "Move", "Delete"))
                    assertThat(button(left, action)).isVisible();
                button(left, "Copy").click();
                assertThat(f.page.getByRole(AriaRole.STATUS)
                                .filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText("Completed")))
                        .isVisible();
                assertEquals(1, bodies.size());
                var body = BrowserState.json(bodies.getFirst());
                assertEquals("/srv/samba/team", body.path("source").asText());
                assertEquals("/home", body.path("destination").asText());
                assertTrue(f.fits());
            });
        }
    }

    @DisplayName("Storage reports a denied browse without mutating files")
    @ParameterizedTest
    @EnumSource(BrowserVariant.class)
    void reportsDeniedBrowse(BrowserVariant variant) {
        try (var runtime = new BrowserTestRuntime()) {
            runtime.verify(variant, "en", "storage-denied", f -> {
                f.page.route("**/api/fs/browse?**", route -> BrowserState.reply(route, null, 403, "Permission denied"));
                f.go("/ui/storage");
                assertThat(f.page.getByRole(AriaRole.ALERT)
                                .filter(new com.microsoft.playwright.Locator.FilterOptions()
                                        .setHasText("Permission denied"))
                                .first())
                        .isVisible();
                assertThat(f.page.locator("main h1")).hasText("Storage and files");
                assertTrue(f.app.requests.stream().allMatch(r -> r.method().equals("GET")));
            });
        }
    }

    @DisplayName("Storage keeps ordinary directory creation payload and original root")
    @ParameterizedTest
    @EnumSource(BrowserVariant.class)
    void createsDirectoryAtOriginalRoot(BrowserVariant variant) {
        try (var runtime = new BrowserTestRuntime()) {
            runtime.verify(variant, "en", "storage-mkdir", f -> {
                var bodies = new ArrayList<String>();
                var paths = new ArrayList<String>();
                f.page.route("**/api/fs/browse?**", route -> {
                    paths.add(route.request().url());
                    route.fallback();
                });
                f.page.route("**/api/fs/mkdir", route -> {
                    bodies.add(route.request().postData());
                    BrowserState.reply(route, null);
                });
                f.go("/ui/storage");
                var left = f.page.locator(".file-manager > div").first();
                assertThat(left.getByRole(AriaRole.TABLE)).isVisible();
                assertTrue(paths.stream().anyMatch(path -> path.endsWith("path=%2F")));
                assertTrue(paths.stream().anyMatch(path -> path.endsWith("path=%2Fhome")));
                button(left, "New folder").click();
                left.getByPlaceholder("Directory name").fill("synthetic-folder");
                button(left, "Create").click();
                assertThat(left.getByPlaceholder("Directory name")).hasCount(0);
                assertEquals(1, bodies.size());
                var body = BrowserState.json(bodies.getFirst());
                assertEquals("/", body.path("parentPath").asText());
                assertEquals("synthetic-folder", body.path("name").asText());
                assertFalse(body.has("preparation"));
                assertTrue(f.fits());
            });
        }
    }

    @DisplayName("Storage direct entry redirects an unauthenticated session to login")
    @ParameterizedTest
    @EnumSource(BrowserVariant.class)
    void requiresSession(BrowserVariant variant) {
        try (var runtime = new BrowserTestRuntime()) {
            runtime.verify(variant, "en", "storage-auth", f -> {
                f.app.authenticated = false;
                f.go("/ui/storage");
                assertThat(f.page).hasURL(Pattern.compile("/ui/login$"));
                assertThat(f.page.locator(".file-manager")).hasCount(0);
                assertTrue(f.app.requests.stream().allMatch(r -> r.method().equals("GET")));
            });
        }
    }
}
