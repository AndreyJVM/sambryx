package sambryx.browser;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sambryx.browser.BrowserFixture.button;

import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.AriaRole;
import java.net.URI;
import java.util.ArrayList;
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
class ShareAccessIT {
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
    @EnumSource(BrowserVariant.class)
    void requiresExplicitIdentityForReadOnlyAccessCheck(BrowserVariant variant) {
        runtime.verify(variant, "en", "requiresExplicitIdentityForReadOnlyAccessCheck", fixture -> {
            var pending = new ArrayList<Route>();
            fixture.page.route("**/api/shares/*/access-check?*", pending::add);
            fixture.go("/ui/shares");
            var trigger = fixture.button("Check access: team");
            trigger.click();
            var dialog = fixture.page.getByRole(AriaRole.DIALOG);
            var run = button(dialog, "Run check");
            assertThat(run).isDisabled();
            dialog.getByLabel("Local username").fill("bob");
            run.click();
            assertThat(dialog.getByRole(AriaRole.STATUS)).hasText("Checking…");
            assertThat(run).isDisabled();
            fixture.page.waitForCondition(() -> !pending.isEmpty());
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
            assertTrue(fixture.fits());
            fixture.screenshot("access-report");
            assertEquals(1, pending.size());
            var request = pending.getFirst().request();
            var url = URI.create(request.url());
            assertEquals("username=bob", url.getQuery());
            assertEquals("/api/shares/team/access-check", url.getPath());
            assertEquals("GET", request.method());
            assertTrue(fixture.app.mutations().isEmpty());
            fixture.page.keyboard().press("Escape");
            assertThat(dialog).hasCount(0);
            assertThat(trigger).isFocused();
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BrowserVariant.class)
    void retriesAccessCheckOnlyAfterExplicitAction(BrowserVariant variant) {
        runtime.verify(variant, "en", "retriesAccessCheckOnlyAfterExplicitAction", fixture -> {
            var calls = new ArrayList<Route>();
            fixture.page.route("**/api/shares/*/access-check?*", route -> {
                calls.add(route);
                BrowserState.reply(route, null, 503, "Fixture unavailable");
            });
            fixture.go("/ui/shares");
            fixture.button("Check access: archive").click();
            var dialog = fixture.page.getByRole(AriaRole.DIALOG);
            dialog.getByLabel("Local username").fill("alice");
            button(dialog, "Run check").click();
            assertThat(dialog.getByRole(AriaRole.ALERT)).hasText("Unable to check access. Try again.");
            assertEquals(1, calls.size());
            button(dialog, "Run check").click();
            fixture.page.waitForCondition(() -> calls.size() == 2);
            assertTrue(fixture.app.mutations().isEmpty());
        });
    }

    @SuppressWarnings("unchecked")
    @ParameterizedTest(name = "{0}")
    @MethodSource("localizedVariants")
    void keepsShareActionsWithinTheirCells(BrowserVariant variant, String language, String label) {
        runtime.verify(variant, language, "keepsShareActionsWithinTheirCells-" + language, fixture -> {
            for (int width : new int[] {1440, 1200, 1101, 360, 320}) {
                fixture.page.setViewportSize(width, 1000);
                fixture.go("/ui/shares");
                var trigger = fixture.button(label + ": team");
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
                fixture.screenshot("geometry-" + width);
                trigger.click();
                assertThat(fixture.page.getByRole(AriaRole.DIALOG)).isVisible();
                fixture.page.keyboard().press("Escape");
                assertThat(trigger).isFocused();
            }
        });
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("localizedVariants")
    void containsKeyboardFocusAndCancelsShareDialogs(BrowserVariant variant, String language, String ignored) {
        runtime.verify(variant, language, "shareDialogKeyboard-" + language, fixture -> {
            boolean russian = language.equals("ru");
            String cancel = russian ? "Отмена" : "Cancel";
            fixture.go("/ui/shares");
            for (String action :
                    List.of(russian ? "Новый ресурс" : "New Share", russian ? "Настроить: team" : "Configure: team")) {
                var opener = fixture.button(action);
                opener.focus();
                fixture.page.keyboard().press("Enter");
                var editor = fixture.page.getByRole(AriaRole.DIALOG);
                assertThat(editor).isVisible();
                if (action.endsWith(": team"))
                    assertThat(editor.locator("#share-name")).isDisabled();
                cycleFocus(fixture, editor);
                var browse = button(editor, russian ? "Обзор..." : "Browse...");
                browse.focus();
                fixture.page.waitForResponse(
                        response -> response.url().contains("/api/fs/browse?"),
                        () -> fixture.page.keyboard().press("Enter"));
                var picker = fixture.page.getByRole(
                        AriaRole.DIALOG,
                        new com.microsoft.playwright.Page.GetByRoleOptions()
                                .setName(russian ? "Диски и Файлы" : "Storage & Explorer")
                                .setExact(true));
                assertThat(fixture.page.getByRole(AriaRole.DIALOG)).hasCount(2);
                assertThat(picker.locator(".animate-spin")).hasCount(0);
                assertThat(button(picker, "team").last()).isVisible();
                cycleFocus(fixture, picker);
                fixture.page.keyboard().press("Escape");
                assertThat(fixture.page.getByRole(AriaRole.DIALOG)).hasCount(1);
                assertThat(browse).isFocused();
                fixture.page.keyboard().press("Escape");
                assertThat(editor).hasCount(0);
                assertThat(opener).isFocused();
                fixture.page.keyboard().press("Enter");
                button(editor, cancel).focus();
                fixture.page.keyboard().press("Enter");
                assertThat(editor).hasCount(0);
                assertThat(opener).isFocused();
            }
            var delete = fixture.button(russian ? "Удалить: archive" : "Delete: archive");
            for (String key : List.of("Escape", "Enter")) {
                delete.focus();
                fixture.page.keyboard().press("Enter");
                var confirmation = fixture.page.getByRole(AriaRole.ALERTDIALOG);
                assertThat(button(confirmation, cancel)).isFocused();
                cycleFocus(fixture, confirmation);
                button(confirmation, cancel).focus();
                fixture.page.keyboard().press(key);
                assertThat(confirmation).hasCount(0);
                assertThat(delete).isFocused();
            }
            assertTrue(fixture.app.mutations().isEmpty(), "Dismissal must not mutate resources");
        });
    }

    private static void cycleFocus(BrowserFixture fixture, com.microsoft.playwright.Locator dialog) {
        assertThat(dialog).isVisible();
        var controls = dialog.locator(
                "button:visible:enabled, input:visible:enabled, select:visible:enabled, textarea:visible:enabled, a[href]:visible, [tabindex='0']:visible");
        int count = controls.count();
        assertTrue(count > 0);
        controls.first().focus();
        for (String key : List.of("Shift+Tab", "Tab")) {
            for (int i = 0; i < count; i++) {
                fixture.page.keyboard().press(key);
                int expected = key.equals("Tab") ? (i + 1) % count : (count - 1 - i);
                assertThat(controls.nth(expected)).isFocused();
                assertTrue(
                        (Boolean)
                                dialog.evaluate(
                                        "element => { const active=document.activeElement; const style=getComputedStyle(active); return active.tagName !== 'BUTTON' || (active.matches(':focus-visible') && (parseFloat(style.outlineWidth) > 0 || style.boxShadow !== 'none')); }"),
                        "Keyboard-focused buttons must have a visible indicator");
                assertTrue((Boolean) dialog.evaluate("element => element.contains(document.activeElement)"));
                assertTrue(
                        (Boolean)
                                dialog.evaluate(
                                        "element => { const active=document.activeElement; return !active.matches(':disabled') && active.getClientRects().length > 0; }"));
            }
            assertThat(controls.first()).isFocused();
        }
    }

    private static double number(Map<String, Object> value, String field) {
        return ((Number) value.get(field)).doubleValue();
    }

    static Stream<Arguments> localizedVariants() {
        return Arrays.stream(BrowserVariant.values())
                .flatMap(variant -> Stream.of(
                        Arguments.of(variant, "ru", "Проверить доступ"), Arguments.of(variant, "en", "Check access")));
    }
}
