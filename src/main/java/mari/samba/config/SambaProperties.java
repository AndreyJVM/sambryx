package mari.samba.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "samba")
public record SambaProperties(
        @DefaultValue @Valid @NotNull Ssh ssh,
        @DefaultValue @Valid @NotNull Paths paths,
        @DefaultValue @Valid @NotNull Security security,
        @DefaultValue @Valid @NotNull Group group) {

    public record Ssh(
            @DefaultValue("7000ms") @NotNull Duration connectTimeout,
            @DefaultValue("30s") @NotNull Duration commandTimeout,
            @DefaultValue("30s") @NotNull Duration serverAliveInterval,
            @DefaultValue("3") @Min(1) int serverAliveCountMax,
            @DefaultValue("/etc/sambryx/known_hosts") @NotBlank String knownHosts) {}

    public record Paths(
            @DefaultValue("/etc/samba/smb.conf") @NotBlank String config,
            @DefaultValue("/etc/samba/backups") @NotBlank String backups,
            @DefaultValue("/var/log/samba/log.smbd") @NotBlank String log) {}

    public record Security(
            @DefaultValue({"/mnt", "/media", "/srv", "/data", "/home"}) @NotEmpty List<String> allowedRoots,
            @DefaultValue("5") @Min(1) int maxLoginAttempts,
            @DefaultValue("3m") @NotNull Duration loginBlockDuration) {}

    public record Group(@DefaultValue("smb_") @NotNull String prefix) {}
}
