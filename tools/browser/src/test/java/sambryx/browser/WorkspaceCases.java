package sambryx.browser;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static sambryx.browser.BrowserFixture.button;

import com.microsoft.playwright.options.AriaRole;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class WorkspaceCases {
    static List<BrowserCase> cases() {
        var cases = new ArrayList<BrowserCase>();
        for (String auth : List.of("password", "key")) {
            cases.add(
                    new BrowserCase("login sends " + auth + " credentials after CSRF bootstrap", f -> login(f, auth)));
            cases.add(new BrowserCase("rejected " + auth + " login allows another attempt", f -> rejection(f, auth)));
        }
        cases.add(new BrowserCase("changed host key gives actionable warning", WorkspaceCases::hostKey));
        cases.add(new BrowserCase("all workspace sections are reachable", WorkspaceCases::navigation));
        cases.add(new BrowserCase("theme survives reload", WorkspaceCases::theme));
        cases.add(new BrowserCase("share search distinguishes no matches and empty server", WorkspaceCases::search));
        cases.add(new BrowserCase("failed reads retain stale shares", WorkspaceCases::stale));
        cases.add(new BrowserCase("nested dialogs trap Tab and restore focus", WorkspaceCases::dialogs));
        cases.add(new BrowserCase("cancel share deletion and service stop", WorkspaceCases::cancelService));
        cases.add(new BrowserCase("cancel account deletion and member removal", WorkspaceCases::cancelAccount));
        cases.add(new BrowserCase("unavailable dashboard disables service actions", WorkspaceCases::dashboard));
        return cases;
    }

    private static void login(BrowserFixture f, String auth) {
        f.app.authenticated = false;
        f.go("/ui/login");
        String credential = auth.equals("key") ? "synthetic-key-not-a-real-private-key" : "synthetic-password";
        f.loginFields(auth, credential);
        f.button("Подключиться").click();
        assertThat(f.page).hasURL(Pattern.compile("/ui/shares$"));
        assertEquals(1, f.app.count("/api/auth/login"));
        var call = f.app.requests.stream()
                .filter(r -> r.path().equals("/api/auth/login"))
                .findFirst()
                .orElseThrow();
        assertEquals("browser-fixture", call.headers().get("x-xsrf-token"));
        assertEquals(
                BrowserState.json(
                        "{\"host\":\"files.example.test\",\"port\":22,\"username\":\"operator\",\"authType\":\"" + auth
                                + "\",\"" + (auth.equals("key") ? "privateKey" : "password") + "\":\"" + credential
                                + "\"}"),
                call.body());
        assertTrue(f.app.requests.stream().map(BrowserState.Call::path).toList().indexOf("/api/auth/environment")
                < f.app.requests.indexOf(call));
    }

    private static void rejection(BrowserFixture f, String auth) {
        f.app.authenticated = false;
        f.app.loginRejected = true;
        f.go("/ui/login");
        f.loginFields(auth, "synthetic-rejected-credential");
        f.button("Подключиться").click();
        assertThat(f.page.getByRole(AriaRole.ALERT)).containsText("Authentication failed: SSH connection failed.");
        assertThat(f.page.getByRole(AriaRole.ALERT)).not().containsText("Сессия истекла");
        assertThat(f.page).hasURL(Pattern.compile("/ui/login$"));
        assertFalse(f.app.authenticated);
        assertEquals(1, f.app.count("/api/auth/login"));
        f.app.loginRejected = false;
        f.page.locator(auth.equals("key") ? "textarea" : "#login-password").fill("synthetic-accepted-credential");
        f.button("Подключиться").click();
        assertThat(f.page).hasURL(Pattern.compile("/ui/shares$"));
        assertThat(f.page.locator("main h1")).isVisible();
        assertEquals(2, f.app.count("/api/auth/login"));
    }

    private static void hostKey(BrowserFixture f) {
        f.app.authenticated = false;
        f.app.loginFailure = true;
        f.go("/ui/login");
        f.loginFields("password", "synthetic-password");
        f.button("Подключиться").click();
        assertThat(f.page.getByRole(AriaRole.ALERT)).containsText("возможна подмена сервера");
        assertThat(f.page.getByRole(AriaRole.ALERT)).containsText("SHA256:synthetic-browser-fixture");
        assertThat(f.page).hasURL(Pattern.compile("/ui/login$"));
        assertEquals(1, f.app.count("/api/auth/login"));
    }

    private static void navigation(BrowserFixture f) {
        f.go("/ui/dashboard");
        var nav = f.page.getByRole(
                AriaRole.NAVIGATION, new com.microsoft.playwright.Page.GetByRoleOptions().setName("Main navigation"));
        for (String route : List.of("shares", "users", "groups", "config", "ad", "fstec", "dashboard")) {
            nav.locator("a[href='/ui/" + route + "']").click();
            assertThat(f.page).hasURL(Pattern.compile("/ui/" + route + "$"));
            assertThat(f.page.locator("main h1")).isVisible();
            assertThat(nav.locator("[aria-current=page]")).hasAttribute("href", "/ui/" + route);
            assertTrue(f.fits());
        }
    }

    private static void theme(BrowserFixture f) {
        f.go("/ui/dashboard");
        boolean dark = f.variant.theme().equals("dark");
        f.button(dark ? "Light mode" : "Dark mode").click();
        Pattern expected = Pattern.compile(dark ? "^$" : "dark");
        assertThat(f.page.locator("html")).hasClass(expected);
        f.page.reload();
        assertThat(f.page.locator("html")).hasClass(expected);
    }

    private static void search(BrowserFixture f) {
        f.go("/ui/shares");
        assertThat(f.page.getByText("team", new com.microsoft.playwright.Page.GetByTextOptions().setExact(true)))
                .isVisible();
        var search = f.page.getByRole(AriaRole.SEARCHBOX);
        for (String query : List.of("TEAM", "/srv/samba/team", "Project documents")) {
            search.fill(query);
            assertThat(f.page.getByText("team", new com.microsoft.playwright.Page.GetByTextOptions().setExact(true)))
                    .isVisible();
            assertThat(f.page.getByText("archive", new com.microsoft.playwright.Page.GetByTextOptions().setExact(true)))
                    .hasCount(0);
        }
        search.fill("not-found");
        assertThat(f.page.getByRole(
                        AriaRole.HEADING,
                        new com.microsoft.playwright.Page.GetByRoleOptions().setName("No matching shares")))
                .isVisible();
        f.button("Clear search").click();
        f.app.shares = BrowserState.json("[]");
        button(f.page.locator("main"), "Refresh").click();
        assertThat(f.page.getByRole(
                        AriaRole.HEADING,
                        new com.microsoft.playwright.Page.GetByRoleOptions().setName("No shares available")))
                .isVisible();
    }

    private static void stale(BrowserFixture f) {
        f.go("/ui/shares");
        assertThat(f.page.getByText("team", new com.microsoft.playwright.Page.GetByTextOptions().setExact(true)))
                .isVisible();
        f.app.failShares = true;
        button(f.page.locator("main"), "Refresh").click();
        assertThat(f.page.getByRole(AriaRole.ALERT)).containsText("Showing the previous list");
        assertThat(f.page.getByText("team", new com.microsoft.playwright.Page.GetByTextOptions().setExact(true)))
                .isVisible();
        f.page.reload();
        assertThat(f.page.getByRole(AriaRole.ALERT)).containsText("Could not load shares");
        assertThat(f.page.getByRole(
                        AriaRole.HEADING,
                        new com.microsoft.playwright.Page.GetByRoleOptions().setName("No shares available")))
                .hasCount(0);
        f.app.failShares = false;
        button(f.page.locator("main"), "Refresh").click();
        assertThat(f.page.getByText("team", new com.microsoft.playwright.Page.GetByTextOptions().setExact(true)))
                .isVisible();
        assertThat(f.page.getByRole(AriaRole.ALERT)).hasCount(0);
    }

    private static void dialogs(BrowserFixture f) {
        f.go("/ui/shares");
        var trigger = f.button("New Share");
        trigger.click();
        var editor = f.page.getByRole(
                AriaRole.DIALOG,
                new com.microsoft.playwright.Page.GetByRoleOptions()
                        .setName("New Share")
                        .setExact(true));
        button(editor, "Save").focus();
        f.page.keyboard().press("Tab");
        assertThat(button(editor, "Close")).isFocused();
        var browse = button(editor, "Browse...");
        browse.click();
        assertThat(f.page.getByRole(AriaRole.DIALOG)).hasCount(2);
        f.page.keyboard().press("Escape");
        assertThat(f.page.getByRole(AriaRole.DIALOG)).hasCount(1);
        assertThat(browse).isFocused();
        f.page.keyboard().press("Escape");
        assertThat(f.page.getByRole(AriaRole.DIALOG)).hasCount(0);
        assertThat(trigger).isFocused();
    }

    private static void cancelService(BrowserFixture f) {
        f.go("/ui/shares");
        f.button("Delete: archive").click();
        button(f.page.getByRole(AriaRole.ALERTDIALOG), "Cancel").click();
        assertThat(f.page.getByText("archive", new com.microsoft.playwright.Page.GetByTextOptions().setExact(true)))
                .isVisible();
        f.go("/ui/dashboard");
        button(f.page.locator("main"), "Stop").click();
        assertThat(f.page.getByRole(AriaRole.ALERTDIALOG)).containsText("Stop Samba?");
        f.page.keyboard().press("Escape");
        assertThat(f.page.getByRole(AriaRole.ALERTDIALOG)).hasCount(0);
        assertTrue(f.app.requests.stream().allMatch(r -> r.method().equals("GET")));
    }

    private static void cancelAccount(BrowserFixture f) {
        f.go("/ui/users");
        f.button("Delete: alice").click();
        button(f.page.getByRole(AriaRole.ALERTDIALOG), "Cancel").click();
        f.go("/ui/groups");
        f.button("Remove: alice (engineering)").click();
        button(f.page.getByRole(AriaRole.ALERTDIALOG), "Cancel").click();
        assertThat(f.page.getByText("alice", new com.microsoft.playwright.Page.GetByTextOptions().setExact(true)))
                .isVisible();
        assertTrue(f.app.requests.stream().allMatch(r -> r.method().equals("GET")));
    }

    private static void dashboard(BrowserFixture f) {
        f.go("/ui/dashboard");
        assertThat(button(f.page.locator("main"), "Stop")).isEnabled();
        f.app.failDashboard = true;
        button(f.page.locator("main"), "Refresh").click();
        assertThat(f.page.getByRole(AriaRole.ALERT)).isVisible();
        assertThat(button(f.page.locator("main"), "Stop")).isDisabled();
        assertThat(button(f.page.locator("main"), "Restart")).isDisabled();
    }
}
