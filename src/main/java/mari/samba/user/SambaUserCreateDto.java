package mari.samba.user;

import jakarta.validation.constraints.NotBlank;

public record SambaUserCreateDto(
        @NotBlank(message = "Validation failed") String username,
        String fullName,
        @NotBlank(message = "Validation failed") String password) {}
