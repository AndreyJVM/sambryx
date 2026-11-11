package mari.samba.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.time.Duration;
import java.util.List;
import mari.samba.config.SambaProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@Epic("Authentication and sessions")
@Feature("Unit: IP and username lockout")
class BruteForceProtectionServiceImplTest {

    @Mock
    private SambaProperties properties;

    private BruteForceProtectionServiceImpl bruteForceService;

    @BeforeEach
    void setUp() {
        SambaProperties.Security security = new SambaProperties.Security(List.of(), 3, Duration.ofMinutes(5));
        when(properties.security()).thenReturn(security);
        bruteForceService = new BruteForceProtectionServiceImpl(properties);
    }

    @DisplayName("Register failed login should block after max attempts")
    @Test
    void testRegisterFailedLogin_ShouldBlockAfterMaxAttempts() {
        String ip = "192.168.1.100";
        String user = "admin";

        bruteForceService.registerFailedLogin(ip, user); // 1
        assertThat(bruteForceService.isBlocked(ip, user)).isFalse();

        bruteForceService.registerFailedLogin(ip, user); // 2
        assertThat(bruteForceService.isBlocked(ip, user)).isFalse();

        bruteForceService.registerFailedLogin(ip, user); // 3 (Max)
        assertThat(bruteForceService.isBlocked(ip, user)).isTrue();
    }

    @DisplayName("Reset failed login should unblock")
    @Test
    void testResetFailedLogin_ShouldUnblock() {
        String ip = "10.0.0.1";
        String user = "testuser";
        bruteForceService.registerFailedLogin(ip, user);
        bruteForceService.registerFailedLogin(ip, user);
        bruteForceService.registerFailedLogin(ip, user);

        assertThat(bruteForceService.isBlocked(ip, user)).isTrue();

        bruteForceService.resetFailedLogin(ip, user);

        assertThat(bruteForceService.isBlocked(ip, user)).isFalse();
    }

    @DisplayName("Different ips should not affect each other")
    @Test
    void testDifferentIps_ShouldNotAffectEachOther() {
        String ip1 = "10.0.0.10";
        String ip2 = "10.0.0.11";
        String user = "user1";

        bruteForceService.registerFailedLogin(ip1, user);
        bruteForceService.registerFailedLogin(ip1, user);
        bruteForceService.registerFailedLogin(ip1, user); // Block IP1

        assertThat(bruteForceService.isBlocked(ip1, user)).isTrue();

        // Actually, since we block by username too now, user1 is also blocked!
        // So checking isBlocked(ip2, "user1") will be true.
        // Let's check with another user for ip2 to verify IP independent blocking.
        assertThat(bruteForceService.isBlocked(ip2, "user2")).isFalse();
    }

    @DisplayName("Username lockout")
    @Test
    void testUsernameLockout() {
        String user = "targetUser";

        bruteForceService.registerFailedLogin("1.1.1.1", user);
        bruteForceService.registerFailedLogin("2.2.2.2", user);
        bruteForceService.registerFailedLogin("3.3.3.3", user);

        // The user is blocked due to 3 failed attempts, regardless of IP
        assertThat(bruteForceService.isBlocked("4.4.4.4", user)).isTrue();
    }
}
