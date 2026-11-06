package sambryx.browser;

enum BrowserVariant {
    DESKTOP_LIGHT("light", false),
    MOBILE_LIGHT("light", true),
    DESKTOP_DARK("dark", false),
    MOBILE_DARK("dark", true);

    private final String theme;
    private final boolean mobile;

    BrowserVariant(String theme, boolean mobile) {
        this.theme = theme;
        this.mobile = mobile;
    }

    String theme() {
        return theme;
    }

    boolean mobile() {
        return mobile;
    }

    String label() {
        return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
}
