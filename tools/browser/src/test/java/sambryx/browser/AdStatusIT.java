package sambryx.browser;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.options.AriaRole;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AdStatusIT {
    @ParameterizedTest
    @EnumSource(BrowserVariant.class)
    void unknownConfirmedRejectedAndFailedStatusRemainDistinct(BrowserVariant variant) {
        try (var runtime = new BrowserTestRuntime()) {
            for (String language : List.of("en", "ru")) {
                runtime.verify(variant, language, "ad-status-" + language, fixture -> {
                    var phase = new java.util.concurrent.atomic.AtomicInteger();
                    fixture.page.route("**/api/ad/status", route -> {
                        if (phase.get() == 3) BrowserState.reply(route, null, 500, "Status command failed.");
                        else
                            BrowserState.reply(
                                    route,
                                    BrowserState.json("{\"isJoined\":"
                                            + (phase.get() == 0 ? "null" : phase.get() == 1 ? "true" : "false")
                                            + ",\"domainName\":null,\"dnsReachable\":"
                                            + (phase.get() == 0 ? "null" : phase.get() == 1 ? "true" : "false")
                                            + ",\"kerberosWorking\":null,\"winbindWorking\":null}"));
                    });
                    fixture.go("/ui/ad");
                    String unknown = language.equals("ru") ? "Не проверено" : "Not checked";
                    assertThat(fixture.page.getByText(
                                    unknown, new com.microsoft.playwright.Page.GetByTextOptions().setExact(true)))
                            .hasCount(4);
                    assertThat(fixture.page.locator(".ad-domain")).hasCount(0);
                    String refresh = language.equals("ru") ? "Обновить" : "Refresh";
                    phase.set(1);
                    fixture.button(refresh).click();
                    assertThat(fixture.page.getByText(
                                    language.equals("ru") ? "В домене" : "Joined",
                                    new com.microsoft.playwright.Page.GetByTextOptions().setExact(true)))
                            .isVisible();
                    assertThat(fixture.page.getByText(
                                    language.equals("ru") ? "В норме" : "OK",
                                    new com.microsoft.playwright.Page.GetByTextOptions().setExact(true)))
                            .isVisible();
                    phase.set(2);
                    fixture.button(refresh).click();
                    assertThat(fixture.page.getByText(
                                    language.equals("ru") ? "Вне домена" : "Not joined",
                                    new com.microsoft.playwright.Page.GetByTextOptions().setExact(true)))
                            .isVisible();
                    assertThat(fixture.page.getByText(
                                    language.equals("ru") ? "Обнаружены ошибки" : "Errors Detected",
                                    new com.microsoft.playwright.Page.GetByTextOptions().setExact(true)))
                            .isVisible();
                    phase.set(3);
                    fixture.button(refresh).click();
                    assertThat(fixture.page.getByRole(AriaRole.ALERT)).isVisible();
                    assertThat(fixture.page.getByText(
                                    language.equals("ru") ? "Вне домена" : "Not joined",
                                    new com.microsoft.playwright.Page.GetByTextOptions().setExact(true)))
                            .hasCount(0);
                    assertTrue(fixture.fits());
                    assertTrue(fixture.app.mutations().isEmpty());
                    fixture.screenshot("unavailable");
                });
            }
        }
    }
}
