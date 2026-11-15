package mari.samba.filesystem;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record CreateDirectoryDto(
        @NotBlank(message = "Parent path must not be blank") @JsonProperty("parentPath") String parentPath,
        @NotBlank(message = "Directory name must not be blank") @JsonProperty("dirName") @JsonAlias({"name", "dirName"})
                String dirName,
        PrivatePreparation preparation) {
    public CreateDirectoryDto(String parentPath, String dirName) {
        this(parentPath, dirName, null);
    }

    public record PrivatePreparation(String owner, String group, String mode, boolean confirmed) {}
}
