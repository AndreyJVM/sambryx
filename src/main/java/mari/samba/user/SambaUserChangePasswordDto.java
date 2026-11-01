package mari.samba.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record SambaUserChangePasswordDto(
        @NotBlank(message = "New password must not be blank") @JsonProperty("newPassword") String newPassword) {}
