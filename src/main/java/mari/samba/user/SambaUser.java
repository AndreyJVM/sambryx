package mari.samba.user;

public record SambaUser(
        String username, String fullName, boolean accountEnabled, String lastChange, String passwordHash) {

    public SambaUser(String username) {
        this(username, "-", true, null, null);
    }

    public String getUsername() {
        return username;
    }

    public String getFullName() {
        return fullName;
    }

    public boolean isAccountEnabled() {
        return accountEnabled;
    }

    public String getLastChange() {
        return lastChange;
    }

    public String getPasswordHash() {
        return passwordHash;
    }
}
