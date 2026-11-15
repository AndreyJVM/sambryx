package sambryx.browser;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static sambryx.browser.BrowserFixture.button;

import com.microsoft.playwright.options.AriaRole;
import java.util.ArrayList;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PrivateDirectoryIT {
    @ParameterizedTest
    @EnumSource(BrowserVariant.class)
    void privateCreationRequiresExplicitChoiceReviewAndHandlesFailure(BrowserVariant variant) {
        try (var runtime = new BrowserTestRuntime()) {
            runtime.verify(variant, "en", "private-directory", fixture -> {
                var requests = new ArrayList<String>();
                fixture.page.route("**/api/fs/mkdir", route -> {
                    requests.add(route.request().postData());
                    BrowserState.reply(
                            route, null, 500, "Private directory creation failed. A new directory may remain.");
                });
                fixture.go("/ui/shares");
                fixture.button("New Share").click();
                fixture.button("Browse...").click();
                var picker = fixture.page.getByRole(AriaRole.DIALOG).last();
                button(picker, "New Directory").click();
                var optIn = picker.getByLabel("Prepare a private directory (0700)");
                assertThat(optIn).not().isChecked();
                picker.getByPlaceholder("Directory name").fill("private");
                optIn.check();
                picker.getByLabel("Local owner").fill("alice");
                picker.getByLabel("Local group").fill("private");
                button(picker, "Save").click();
                var review = fixture.page.getByRole(AriaRole.DIALOG).last();
                assertThat(review).containsText("alice:private");
                assertThat(review).containsText("0700");
                assertTrue(requests.isEmpty());
                button(review, "Cancel").click();
                assertTrue(requests.isEmpty());
                button(picker, "Save").click();
                button(fixture.page.getByRole(AriaRole.DIALOG).last(), "Create private directory")
                        .click();
                assertThat(picker.getByRole(AriaRole.ALERT)).containsText("A new directory may remain");
                assertEquals(1, requests.size());
                var body = BrowserState.json(requests.getFirst());
                assertEquals("alice", body.path("preparation").path("owner").asText());
                assertEquals("0700", body.path("preparation").path("mode").asText());
                assertTrue(body.path("preparation").path("confirmed").asBoolean());
                assertThat(picker.getByPlaceholder("Directory name")).hasValue("private");
                assertTrue(fixture.fits());
                fixture.screenshot("failed-preparation");
            });
        }
    }

    @ParameterizedTest
    @EnumSource(BrowserVariant.class)
    void defaultCreationOmitsPreparationAndShowsSuccess(BrowserVariant variant) {
        try (var runtime = new BrowserTestRuntime()) {
            runtime.verify(variant, "en", "ordinary-directory", fixture -> {
                var requests = new ArrayList<String>();
                fixture.page.route("**/api/fs/mkdir", route -> {
                    requests.add(route.request().postData());
                    BrowserState.reply(route, null);
                });
                fixture.go("/ui/shares");
                fixture.button("New Share").click();
                fixture.button("Browse...").click();
                var picker = fixture.page.getByRole(AriaRole.DIALOG).last();
                button(picker, "New Directory").click();
                assertThat(picker.getByLabel("Prepare a private directory (0700)"))
                        .not()
                        .isChecked();
                picker.getByPlaceholder("Directory name").fill("ordinary");
                button(picker, "Save").click();
                assertThat(fixture.page
                                .getByRole(AriaRole.STATUS)
                                .filter(new com.microsoft.playwright.Locator.FilterOptions()
                                        .setHasText("Folder created: ordinary")))
                        .isVisible();
                assertEquals(1, requests.size());
                assertFalse(BrowserState.json(requests.getFirst()).has("preparation"));
                assertThat(fixture.page.getByRole(AriaRole.DIALOG)).hasCount(2);
            });
        }
    }

    @ParameterizedTest
    @EnumSource(BrowserVariant.class)
    void russianConfirmationWaitsForCompletionAndPreventsDuplicateCreation(BrowserVariant variant) {
        try (var runtime = new BrowserTestRuntime()) {
            runtime.verify(variant, "ru", "private-directory-ru", fixture -> {
                var pending = new ArrayList<com.microsoft.playwright.Route>();
                fixture.page.route("**/api/fs/mkdir", pending::add);
                fixture.go("/ui/shares");
                fixture.button("Новый ресурс").click();
                fixture.button("Обзор...").click();
                var picker = fixture.page.getByRole(AriaRole.DIALOG).last();
                button(picker, "Новая папка").click();
                picker.getByPlaceholder("имя_папки").fill("private");
                picker.getByLabel("Подготовить личную папку (0700)").check();
                assertThat(picker.getByLabel("Локальный владелец")).hasValue("");
                assertThat(picker.getByLabel("Локальная группа")).hasValue("");
                picker.getByLabel("Локальный владелец").fill("alice");
                picker.getByLabel("Локальная группа").fill("private");
                button(picker, "Сохранить").click();
                var review = fixture.page.getByRole(AriaRole.DIALOG).last();
                assertThat(review).containsText("alice:private — 0700");
                fixture.screenshot("review");
                button(review, "Создать личную папку").click();
                assertThat(button(picker, "Сохранить")).isDisabled();
                button(picker, "Закрыть").click();
                assertThat(picker).isVisible();
                assertThat(fixture.page.getByText("Папка создана: private")).hasCount(0);
                assertEquals(1, pending.size());
                BrowserState.reply(pending.getFirst(), null);
                assertThat(fixture.page
                                .getByRole(AriaRole.STATUS)
                                .filter(new com.microsoft.playwright.Locator.FilterOptions()
                                        .setHasText("Папка создана: private")))
                        .isVisible();
                assertThat(picker.getByPlaceholder("имя_папки")).hasCount(0);
                assertEquals(1, pending.size());
                assertTrue(fixture.fits());
            });
        }
    }
}
