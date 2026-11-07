package sambryx.browser;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.AriaRole;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserDeletionIT {
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
    @ValueSource(strings = {"en", "ru"})
    void confirmsAccountDeletionWithFilesPreservedAndCancelSendsNoRequest(String language) {
        runtime.verify(BrowserVariant.DESKTOP_LIGHT, language, "user-deletion-" + language, fixture -> {
            var mutations = new ArrayList<String>();
            fixture.page.route("**/api/users/*", route -> {
                mutations.add(route.request().method() + " " + route.request().url());
                BrowserState.reply(route, null);
            });
            fixture.go("/ui/users");
            String delete = language.equals("ru") ? "Удалить" : "Delete";
            String cancel = language.equals("ru") ? "Отмена" : "Cancel";
            fixture.button(delete + ": alice").click();
            var dialog = fixture.page.getByRole(AriaRole.ALERTDIALOG);
            assertThat(dialog).containsText("alice");
            assertThat(dialog).containsText("Linux");
            assertThat(dialog).containsText("Samba");
            assertThat(dialog)
                    .containsText(
                            language.equals("ru")
                                    ? "Домашний каталог, почтовые файлы и данные ресурсов сохраняются"
                                    : "Home directory, mail files and share data are preserved");
            dialog.getByRole(
                            AriaRole.BUTTON,
                            new Locator.GetByRoleOptions().setName(cancel).setExact(true))
                    .click();
            assertThat(dialog).not().isVisible();
            assertEquals(List.of(), mutations);
            fixture.button(delete + ": alice").click();
            dialog.getByRole(
                            AriaRole.BUTTON,
                            new Locator.GetByRoleOptions().setName(delete).setExact(true))
                    .click();
            fixture.page.waitForCondition(() -> mutations.size() == 1);
            assertEquals(List.of("DELETE " + fixture.app.origin + "/api/users/alice"), mutations);
            assertThat(dialog).not().isVisible();
        });
    }
}
