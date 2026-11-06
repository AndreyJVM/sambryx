package sambryx.browser;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.options.AriaRole;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServerReadinessIT {
    private BrowserTestRuntime runtime;

    @BeforeAll
    void start() {
        runtime = new BrowserTestRuntime();
    }

    @AfterAll
    void close() {
        if (runtime != null) runtime.close();
    }

    @ParameterizedTest
    @EnumSource(BrowserVariant.class)
    void sshLoginDoesNotImplyReadinessAndRejectedProbeHasNextAction(BrowserVariant variant) {
        runtime.verify(variant, "en", "readinessRejected", fixture -> {
            fixture.page.route(
                    "**/api/monitoring/readiness",
                    route -> BrowserState.reply(
                            route,
                            Map.of(
                                    "ready",
                                    false,
                                    "checks",
                                    List.of(
                                            Map.of(
                                                    "id",
                                                    "tools",
                                                    "status",
                                                    "FAILED",
                                                    "reason",
                                                    "rejected",
                                                    "nextAction",
                                                    "reviewTools"),
                                            Map.of(
                                                    "id",
                                                    "administration",
                                                    "status",
                                                    "UNKNOWN",
                                                    "reason",
                                                    "unverified",
                                                    "nextAction",
                                                    "reviewAdministration")))));
            fixture.go("/ui/dashboard");
            assertThat(fixture.page.getByText("SSH login does not confirm Samba readiness."))
                    .isVisible();
            fixture.button("Check readiness").click();
            assertThat(fixture.page.getByText("Full readiness is not confirmed."))
                    .isVisible();
            assertThat(fixture.page.getByText(
                            "Verify sudo, testparm, pdbedit and cat in the SSH account's PATH and sudo environment."))
                    .isVisible();
            assertTrue(fixture.app.mutations().isEmpty());
        });
    }

    @ParameterizedTest
    @EnumSource(BrowserVariant.class)
    void unavailableReportDoesNotExposeApiMessageOrRetainPreviousResults(BrowserVariant variant) {
        runtime.verify(variant, "en", "readinessUnavailable", fixture -> {
            var failed = new java.util.concurrent.atomic.AtomicBoolean();
            fixture.page.route("**/api/monitoring/readiness", route -> {
                if (failed.get()) BrowserState.reply(route, null, 503, "synthetic-sensitive-output");
                else
                    BrowserState.reply(
                            route,
                            Map.of(
                                    "ready",
                                    false,
                                    "checks",
                                    List.of(Map.of(
                                            "id",
                                            "tools",
                                            "status",
                                            "PASSED",
                                            "reason",
                                            "confirmed",
                                            "nextAction",
                                            "reviewTools"))));
            });
            fixture.go("/ui/dashboard");
            fixture.button("Check readiness").click();
            assertThat(fixture.page.getByText(
                            "Confirmed", new com.microsoft.playwright.Page.GetByTextOptions().setExact(true)))
                    .isVisible();
            failed.set(true);
            fixture.button("Check readiness").click();
            assertThat(
                            fixture.page
                                    .getByRole(AriaRole.ALERT)
                                    .filter(
                                            new com.microsoft.playwright.Locator.FilterOptions()
                                                    .setHasText(
                                                            "Readiness report unavailable. Retry; if it persists, check the SSH session and server access.")))
                    .isVisible();
            assertThat(fixture.page.getByText(
                            "Confirmed", new com.microsoft.playwright.Page.GetByTextOptions().setExact(true)))
                    .hasCount(0);
            assertThat(fixture.page.getByText("synthetic-sensitive-output")).hasCount(0);
            assertTrue(fixture.app.mutations().isEmpty());
        });
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "null",
                "{}",
                "{\"ready\":false,\"checks\":[{\"id\":\"tools\",\"status\":\"PASSED\",\"reason\":\"synthetic-sensitive-output\",\"nextAction\":\"reviewTools\"}]}"
            })
    void malformedReportShowsFixedErrorInsteadOfRemoteFields(String payload) {
        runtime.verify(BrowserVariant.DESKTOP_LIGHT, "en", "readinessMalformed", fixture -> {
            fixture.page.route(
                    "**/api/monitoring/readiness", route -> BrowserState.reply(route, BrowserState.json(payload)));
            fixture.go("/ui/dashboard");
            fixture.button("Check readiness").click();
            assertThat(
                            fixture.page
                                    .getByRole(AriaRole.ALERT)
                                    .filter(
                                            new com.microsoft.playwright.Locator.FilterOptions()
                                                    .setHasText(
                                                            "Readiness report unavailable. Retry; if it persists, check the SSH session and server access.")))
                    .isVisible();
            assertThat(fixture.page.getByText("synthetic-sensitive-output")).hasCount(0);
        });
    }

    @ParameterizedTest
    @EnumSource(BrowserVariant.class)
    void pendingProbeCannotBeDuplicatedAndSuccessfulReadsStayPartial(BrowserVariant variant) {
        for (String language : List.of("ru", "en")) {
            runtime.verify(variant, language, "readinessPassed-" + language, fixture -> {
                var pending = new java.util.concurrent.atomic.AtomicReference<com.microsoft.playwright.Route>();
                var requests = new java.util.concurrent.atomic.AtomicInteger();
                fixture.page.route("**/api/monitoring/readiness", route -> {
                    requests.incrementAndGet();
                    pending.set(route);
                });
                fixture.go("/ui/dashboard");
                String run = language.equals("ru") ? "Проверить готовность" : "Check readiness";
                String loading = language.equals("ru") ? "Проверка готовности…" : "Checking readiness…";
                String partial = language.equals("ru")
                        ? "Полная готовность не подтверждена."
                        : "Full readiness is not confirmed.";
                assertThat(fixture.button(run)).isVisible();
                assertEquals(0, requests.get());
                fixture.button(run).click();
                fixture.page.waitForCondition(() -> pending.get() != null);
                try {
                    assertThat(fixture.button(loading)).isDisabled();
                    fixture.button(loading).evaluate("button => button.click()");
                    assertEquals(1, requests.get());
                } finally {
                    BrowserState.reply(
                            pending.get(),
                            BrowserState.json(
                                    """
                    {"ready":false,"checks":[
                      {"id":"tools","status":"PASSED","reason":"confirmed","nextAction":"reviewTools"},
                      {"id":"configRead","status":"PASSED","reason":"confirmed","nextAction":"reviewConfigRead"},
                      {"id":"configValidation","status":"PASSED","reason":"confirmed","nextAction":"reviewConfig"},
                      {"id":"accountsRead","status":"PASSED","reason":"confirmed","nextAction":"reviewAccounts"},
                      {"id":"administration","status":"UNKNOWN","reason":"unverified","nextAction":"reviewAdministration"}
                    ]}
                    """));
                }
                assertThat(fixture.page.getByText(partial)).isVisible();
                assertThat(fixture.button(run)).isEnabled();
                assertEquals(1, requests.get());
                assertTrue(fixture.fits());
                assertTrue(fixture.app.mutations().isEmpty());
                fixture.screenshot("report");
            });
        }
    }
}
