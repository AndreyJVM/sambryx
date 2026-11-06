package mari.samba.share;

import java.util.Optional;

final class ShareDirectoryPolicy {
    private ShareDirectoryPolicy() {}

    static String permissions(SambaShareCreateDto request) {
        if (request.getDirectoryMask() != null && !request.getDirectoryMask().isBlank()) {
            return request.getDirectoryMask().trim();
        }
        if (request.isGuestOk() && !request.isReadOnly()) return "0777";
        return request.isReadOnly() ? "0755" : "0775";
    }

    static Optional<String> owner(SambaShareCreateDto request) {
        String user = trimmedOrNull(request.getForceUser());
        String group = trimmedOrNull(request.getForceGroup());

        if (user == null
                && request.getValidUsers() != null
                && !request.getValidUsers().isBlank()) {
            String firstUser = request.getValidUsers().split(",")[0].trim();
            if (!firstUser.startsWith("@")) user = firstUser;
            else if (group == null) group = firstUser.substring(1);
        }

        if (user != null && group != null) return Optional.of(user + ":" + group);
        if (group != null) return Optional.of(":" + group);
        return Optional.ofNullable(user).filter(value -> !value.isBlank());
    }

    private static String trimmedOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
