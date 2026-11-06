package sambryx.browser;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sambryx.browser.BrowserFixture.button;

import com.microsoft.playwright.options.AriaRole;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkspaceIT {
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
    @MethodSource("loginVariants")
    void sendsCredentialsAfterCsrfBootstrap(BrowserVariant variant, String auth) {
        runtime.verify(variant, "en", "sendsCredentialsAfterCsrfBootstrap-" + auth, fixture -> {
            fixture.app.authenticated = false;
            fixture.go("/ui/login");
            String credential = auth.equals("key") ? "synthetic-key-not-a-real-private-key" : "synthetic-password";
            fixture.loginFields(auth, credential);
            fixture.button("Подключиться").click();
            assertThat(fixture.page).hasURL(Pattern.compile("/ui/shares$"));
            assertEquals(1, fixture.app.count("/api/auth/login"));
            var call = fixture.app.requests.stream()
                    .filter(r -> r.path().equals("/api/auth/login"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("browser-fixture", call.headers().get("x-xsrf-token"));
            assertEquals(
                    BrowserState.JSON.valueToTree(Map.of(
                            "host",
                            "files.example.test",
                            "port",
                            22,
                            "username",
                            "operator",
                            "authType",
                            auth,
                            auth.equals("key") ? "privateKey" : "password",
                            credential)),
                    call.body());
            assertTrue(fixture.app.requests.stream()
                            .map(BrowserState.Call::path)
                            .toList()
                            .indexOf("/api/auth/environment")
                    < fixture.app.requests.indexOf(call));
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("loginVariants")
    void allowsRetryAfterRejectedLogin(BrowserVariant variant, String auth) {
        runtime.verify(variant, "en", "allowsRetryAfterRejectedLogin-" + auth, fixture -> {
            fixture.app.authenticated = false;
            fixture.app.loginRejected = true;
            fixture.go("/ui/login");
            fixture.loginFields(auth, "synthetic-rejected-credential");
            fixture.button("Подключиться").click();
            assertThat(fixture.page.getByRole(AriaRole.ALERT))
                    .containsText("Authentication failed: SSH connection failed.");
            assertThat(fixture.page.getByRole(AriaRole.ALERT)).not().containsText("Сессия истекла");
            assertThat(fixture.page).hasURL(Pattern.compile("/ui/login$"));
            assertFalse(fixture.app.authenticated);
            assertEquals(1, fixture.app.count("/api/auth/login"));
            fixture.app.loginRejected = false;
            fixture.page
                    .locator(auth.equals("key") ? "textarea" : "#login-password")
                    .fill("synthetic-accepted-credential");
            fixture.button("Подключиться").click();
            assertThat(fixture.page).hasURL(Pattern.compile("/ui/shares$"));
            assertThat(fixture.page.locator("main h1")).isVisible();
            assertEquals(2, fixture.app.count("/api/auth/login"));
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BrowserVariant.class)
    void rejectsChangedHostKeyWithActionableWarning(BrowserVariant variant) {
        runtime.verify(variant, "en", "rejectsChangedHostKeyWithActionableWarning", fixture -> {
            fixture.app.authenticated = false;
            fixture.app.loginFailure = true;
            fixture.go("/ui/login");
            fixture.loginFields("password", "synthetic-password");
            fixture.button("Подключиться").click();
            assertThat(fixture.page.getByRole(AriaRole.ALERT)).containsText("возможна подмена сервера");
            assertThat(fixture.page.getByRole(AriaRole.ALERT)).containsText("SHA256:synthetic-browser-fixture");
            assertThat(fixture.page).hasURL(Pattern.compile("/ui/login$"));
            assertEquals(1, fixture.app.count("/api/auth/login"));
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BrowserVariant.class)
    void opensEveryWorkspaceSection(BrowserVariant variant) {
        runtime.verify(variant, "en", "opensEveryWorkspaceSection", fixture -> {
            fixture.go("/ui/dashboard");
            var nav = fixture.page.getByRole(
                    AriaRole.NAVIGATION,
                    new com.microsoft.playwright.Page.GetByRoleOptions().setName("Main navigation"));
            for (String route : List.of("shares", "users", "groups", "config", "ad", "fstec", "dashboard")) {
                nav.locator("a[href='/ui/" + route + "']").click();
                assertThat(fixture.page).hasURL(Pattern.compile("/ui/" + route + "$"));
                assertThat(fixture.page.locator("main h1")).isVisible();
                assertThat(nav.locator("[aria-current=page]")).hasAttribute("href", "/ui/" + route);
                assertTrue(fixture.fits());
            }
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BrowserVariant.class)
    void preservesThemeAfterReload(BrowserVariant variant) {
        runtime.verify(variant, "en", "preservesThemeAfterReload", fixture -> {
            fixture.go("/ui/dashboard");
            boolean dark = fixture.variant.theme().equals("dark");
            fixture.button(dark ? "Light mode" : "Dark mode").click();
            Pattern expected = Pattern.compile(dark ? "^$" : "dark");
            assertThat(fixture.page.locator("html")).hasClass(expected);
            fixture.page.reload();
            assertThat(fixture.page.locator("html")).hasClass(expected);
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BrowserVariant.class)
    void distinguishesEmptySharesFromSearchMiss(BrowserVariant variant) {
        runtime.verify(variant, "en", "distinguishesEmptySharesFromSearchMiss", fixture -> {
            fixture.go("/ui/shares");
            assertThat(fixture.page.getByText(
                            "team", new com.microsoft.playwright.Page.GetByTextOptions().setExact(true)))
                    .isVisible();
            var search = fixture.page.getByRole(AriaRole.SEARCHBOX);
            for (String query : List.of("TEAM", "/srv/samba/team", "Project documents")) {
                search.fill(query);
                assertThat(fixture.page.getByText(
                                "team", new com.microsoft.playwright.Page.GetByTextOptions().setExact(true)))
                        .isVisible();
                assertThat(fixture.page.getByText(
                                "archive", new com.microsoft.playwright.Page.GetByTextOptions().setExact(true)))
                        .hasCount(0);
            }
            search.fill("not-found");
            assertThat(fixture.page.getByRole(
                            AriaRole.HEADING,
                            new com.microsoft.playwright.Page.GetByRoleOptions().setName("No matching shares")))
                    .isVisible();
            fixture.button("Clear search").click();
            fixture.app.shares = BrowserState.json("[]");
            button(fixture.page.locator("main"), "Refresh").click();
            assertThat(fixture.page.getByRole(
                            AriaRole.HEADING,
                            new com.microsoft.playwright.Page.GetByRoleOptions().setName("No shares available")))
                    .isVisible();
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BrowserVariant.class)
    void retainsStaleSharesAfterRefreshFailure(BrowserVariant variant) {
        runtime.verify(variant, "en", "retainsStaleSharesAfterRefreshFailure", fixture -> {
            fixture.go("/ui/shares");
            assertThat(fixture.page.getByText(
                            "team", new com.microsoft.playwright.Page.GetByTextOptions().setExact(true)))
                    .isVisible();
            fixture.app.failShares = true;
            button(fixture.page.locator("main"), "Refresh").click();
            assertThat(fixture.page.getByRole(AriaRole.ALERT)).containsText("Showing the previous list");
            assertThat(fixture.page.getByText(
                            "team", new com.microsoft.playwright.Page.GetByTextOptions().setExact(true)))
                    .isVisible();
            fixture.page.reload();
            assertThat(fixture.page.getByRole(AriaRole.ALERT)).containsText("Could not load shares");
            assertThat(fixture.page.getByRole(
                            AriaRole.HEADING,
                            new com.microsoft.playwright.Page.GetByRoleOptions().setName("No shares available")))
                    .hasCount(0);
            fixture.app.failShares = false;
            button(fixture.page.locator("main"), "Refresh").click();
            assertThat(fixture.page.getByText(
                            "team", new com.microsoft.playwright.Page.GetByTextOptions().setExact(true)))
                    .isVisible();
            assertThat(fixture.page.getByRole(AriaRole.ALERT)).hasCount(0);
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BrowserVariant.class)
    void restoresFocusAfterClosingNestedDialogs(BrowserVariant variant) {
        runtime.verify(variant, "en", "restoresFocusAfterClosingNestedDialogs", fixture -> {
            fixture.go("/ui/shares");
            var trigger = fixture.button("New Share");
            trigger.click();
            var editor = fixture.page.getByRole(
                    AriaRole.DIALOG,
                    new com.microsoft.playwright.Page.GetByRoleOptions()
                            .setName("New Share")
                            .setExact(true));
            button(editor, "Save").focus();
            fixture.page.keyboard().press("Tab");
            assertThat(button(editor, "Close")).isFocused();
            var browse = button(editor, "Browse...");
            browse.click();
            assertThat(fixture.page.getByRole(AriaRole.DIALOG)).hasCount(2);
            fixture.page.keyboard().press("Escape");
            assertThat(fixture.page.getByRole(AriaRole.DIALOG)).hasCount(1);
            assertThat(browse).isFocused();
            fixture.page.keyboard().press("Escape");
            assertThat(fixture.page.getByRole(AriaRole.DIALOG)).hasCount(0);
            assertThat(trigger).isFocused();
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BrowserVariant.class)
    void cancelsShareDeletionAndServiceStop(BrowserVariant variant) {
        runtime.verify(variant, "en", "cancelsShareDeletionAndServiceStop", fixture -> {
            fixture.go("/ui/shares");
            fixture.button("Delete: archive").click();
            button(fixture.page.getByRole(AriaRole.ALERTDIALOG), "Cancel").click();
            assertThat(fixture.page.getByText(
                            "archive", new com.microsoft.playwright.Page.GetByTextOptions().setExact(true)))
                    .isVisible();
            fixture.go("/ui/dashboard");
            button(fixture.page.locator("main"), "Stop").click();
            assertThat(fixture.page.getByRole(AriaRole.ALERTDIALOG)).containsText("Stop Samba?");
            fixture.page.keyboard().press("Escape");
            assertThat(fixture.page.getByRole(AriaRole.ALERTDIALOG)).hasCount(0);
            assertTrue(fixture.app.requests.stream().allMatch(r -> r.method().equals("GET")));
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BrowserVariant.class)
    void cancelsAccountDeletionAndMemberRemoval(BrowserVariant variant) {
        runtime.verify(variant, "en", "cancelsAccountDeletionAndMemberRemoval", fixture -> {
            fixture.go("/ui/users");
            fixture.button("Delete: alice").click();
            button(fixture.page.getByRole(AriaRole.ALERTDIALOG), "Cancel").click();
            fixture.go("/ui/groups");
            fixture.button("Remove: alice (engineering)").click();
            button(fixture.page.getByRole(AriaRole.ALERTDIALOG), "Cancel").click();
            assertThat(fixture.page.getByText(
                            "alice", new com.microsoft.playwright.Page.GetByTextOptions().setExact(true)))
                    .isVisible();
            assertTrue(fixture.app.requests.stream().allMatch(r -> r.method().equals("GET")));
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BrowserVariant.class)
    void disablesServiceActionsWhenDashboardIsUnavailable(BrowserVariant variant) {
        runtime.verify(variant, "en", "disablesServiceActionsWhenDashboardIsUnavailable", fixture -> {
            fixture.go("/ui/dashboard");
            assertThat(button(fixture.page.locator("main"), "Stop")).isEnabled();
            fixture.app.failDashboard = true;
            button(fixture.page.locator("main"), "Refresh").click();
            assertThat(fixture.page.getByRole(AriaRole.ALERT)).isVisible();
            assertThat(button(fixture.page.locator("main"), "Stop")).isDisabled();
            assertThat(button(fixture.page.locator("main"), "Restart")).isDisabled();
        });
    }

    static Stream<Arguments> loginVariants() {
        return Arrays.stream(BrowserVariant.values())
                .flatMap(variant -> Stream.of("password", "key").map(auth -> Arguments.of(variant, auth)));
    }
}
