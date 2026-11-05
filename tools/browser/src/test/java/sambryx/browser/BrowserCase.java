package sambryx.browser;

import java.util.function.Consumer;

record BrowserCase(String name, String language, Consumer<BrowserFixture> action) {
    BrowserCase(String name, Consumer<BrowserFixture> action) {
        this(name, "en", action);
    }
}
