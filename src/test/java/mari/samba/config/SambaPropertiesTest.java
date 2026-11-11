package mari.samba.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;

@SpringBootTest(classes = SambaPropertiesTest.TestConfiguration.class)
@Epic("Application configuration and HTTP errors")
@Feature("Spring component: default property binding")
class SambaPropertiesTest {

    @Autowired
    private SambaProperties properties;

    @Test
    @DisplayName("Spring binds the default SSH, path, security and group properties")
    void testDefaultValuesAreBoundCorrectly() {
        assertThat(properties).isNotNull();

        // Check Ssh defaults
        assertThat(properties.ssh().connectTimeout()).isEqualTo(Duration.ofMillis(7000));
        assertThat(properties.ssh().commandTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.ssh().serverAliveInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.ssh().serverAliveCountMax()).isEqualTo(3);

        // Check Paths defaults
        assertThat(properties.paths().config()).isEqualTo("/etc/samba/smb.conf");
        assertThat(properties.paths().backups()).isEqualTo("/etc/samba/backups");
        assertThat(properties.paths().log()).isEqualTo("/var/log/samba/log.smbd");

        // Check Security defaults
        assertThat(properties.security().allowedRoots())
                .containsExactlyElementsOf(List.of("/mnt", "/media", "/srv", "/data", "/home"));
        assertThat(properties.security().maxLoginAttempts()).isEqualTo(5);
        assertThat(properties.security().loginBlockDuration()).isEqualTo(Duration.ofMinutes(3));

        // Check Group defaults
        assertThat(properties.group().prefix()).isEqualTo("smb_");
    }

    @Configuration
    @EnableConfigurationProperties(SambaProperties.class)
    static class TestConfiguration {}
}
