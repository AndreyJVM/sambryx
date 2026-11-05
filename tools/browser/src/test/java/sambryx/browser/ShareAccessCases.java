package sambryx.browser;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static sambryx.browser.BrowserFixture.button;

import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.AriaRole;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class ShareAccessCases {
    static List<BrowserCase> cases() {
        return List.of(
                new BrowserCase(
                        "access check requires explicit identity and remains read only", ShareAccessCases::report),
                new BrowserCase("access error permits explicit retry without mutation", ShareAccessCases::retry),
                new BrowserCase("share action geometry in Russian", "ru", f -> geometry(f, "Проверить доступ")),
                new BrowserCase("share action geometry in English", f -> geometry(f, "Check access")));
    }

    private static void report(BrowserFixture f) {
        var pending = new ArrayList<Route>();
        f.page.route("**/api/shares/*/access-check?*", pending::add);
        f.go("/ui/shares");
        var trigger = f.button("Check access: team");
        trigger.click();
        var dialog = f.page.getByRole(AriaRole.DIALOG);
        var run = button(dialog, "Run check");
        assertThat(run).isDisabled();
        dialog.getByLabel("Local username").fill("bob");
        run.click();
        assertThat(dialog.getByRole(AriaRole.STATUS)).hasText("Checking…");
        assertThat(run).isDisabled();
        f.page.waitForCondition(() -> !pending.isEmpty());
        BrowserState.reply(
                pending.getFirst(),
                BrowserState.json(
                        """
                {"share":"team","username":"bob","findings":[
                {"code":"UNAVAILABLE","status":"BLOCKING","evidence":"unavailable","nextAction":"reviewConfig"},
                {"code":"LIMITS","status":"UNKNOWN","evidence":"limits","nextAction":"verifyClient"}]}
                """));
        assertThat(dialog.getByText("The checked Samba configuration marks this share as unavailable."))
                .isVisible();
        assertThat(dialog.getByText(
                        "Not checked", new com.microsoft.playwright.Locator.GetByTextOptions().setExact(true)))
                .isVisible();
        assertThat(dialog.getByText(Pattern.compile("SMB authentication"))).isVisible();
        assertTrue((Boolean) dialog.evaluate("element => element.scrollWidth <= element.clientWidth"));
        assertTrue(f.fits());
        f.screenshot("access-report");
        assertEquals(1, pending.size());
        var request = pending.getFirst().request();
        var url = URI.create(request.url());
        assertEquals("username=bob", url.getQuery());
        assertEquals("/api/shares/team/access-check", url.getPath());
        assertEquals("GET", request.method());
        assertTrue(f.app.mutations().isEmpty());
        f.page.keyboard().press("Escape");
        assertThat(dialog).hasCount(0);
        assertThat(trigger).isFocused();
    }

    private static void retry(BrowserFixture f) {
        var calls = new ArrayList<Route>();
        f.page.route("**/api/shares/*/access-check?*", route -> {
            calls.add(route);
            BrowserState.reply(route, null, 503, "Fixture unavailable");
        });
        f.go("/ui/shares");
        f.button("Check access: archive").click();
        var dialog = f.page.getByRole(AriaRole.DIALOG);
        dialog.getByLabel("Local username").fill("alice");
        button(dialog, "Run check").click();
        assertThat(dialog.getByRole(AriaRole.ALERT)).hasText("Unable to check access. Try again.");
        assertEquals(1, calls.size());
        button(dialog, "Run check").click();
        f.page.waitForCondition(() -> calls.size() == 2);
        assertTrue(f.app.mutations().isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static void geometry(BrowserFixture f, String label) {
        for (int width : new int[] {1440, 1200, 1101, 360, 320}) {
            f.page.setViewportSize(width, 1000);
            f.go("/ui/shares");
            var trigger = f.button(label + ": team");
            assertThat(trigger).isVisible();
            var geometry = (List<Map<String, Object>>)
                    trigger.locator("..")
                            .locator("..")
                            .evaluate(
                                    """
                    cell => {
                      const bounds=cell.getBoundingClientRect();
                      return [...cell.querySelectorAll('button')].map(button=>{
                        const rect=button.getBoundingClientRect(); const range=document.createRange(); range.selectNodeContents(button); const content=range.getBoundingClientRect();
                        return {x:rect.x,y:rect.y,right:rect.right,bottom:rect.bottom,width:rect.width,height:rect.height,
                          contained:rect.left>=bounds.left&&rect.right<=bounds.right,contentFits:content.left>=rect.left&&content.right<=rect.right};
                      });
                    }
                    """);
            assertEquals(3, geometry.size());
            for (var rect : geometry) {
                assertEquals(true, rect.get("contained"));
                assertEquals(true, rect.get("contentFits"));
                assertTrue(number(rect, "width") >= 40);
                assertTrue(number(rect, "height") >= 40);
            }
            for (int i = 0; i < geometry.size(); i++)
                for (int j = i + 1; j < geometry.size(); j++) {
                    var a = geometry.get(i);
                    var b = geometry.get(j);
                    assertTrue(number(a, "right") <= number(b, "x")
                            || number(b, "right") <= number(a, "x")
                            || number(a, "bottom") <= number(b, "y")
                            || number(b, "bottom") <= number(a, "y"));
                }
            f.screenshot("geometry-" + width);
            trigger.click();
            assertThat(f.page.getByRole(AriaRole.DIALOG)).isVisible();
            f.page.keyboard().press("Escape");
            assertThat(trigger).isFocused();
        }
    }

    private static double number(Map<String, Object> value, String field) {
        return ((Number) value.get(field)).doubleValue();
    }
}
