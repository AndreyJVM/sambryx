package mari.samba.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import mari.samba.config.SambaProperties;
import mari.samba.infra.CommandExecutor;
import mari.samba.infra.LinuxCommands;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@Epic("Monitoring and readiness")
@Feature("Unit: log parsing and retrieval (mock commands)")
class SambaLogServiceTest {

    @Mock
    private CommandExecutor commandExecutor;

    @Mock
    private SambaProperties properties;

    @InjectMocks
    private SambaLogService sambaLogService;

    @Nested
    class GetRecentLogs {

        @Test
        @DisplayName("An allowed line count retrieves the requested log tail")
        void getRecentLogs_withAllowedLinesCount_executesTailWithRequestedLines() throws Exception {
            // given
            String sessionId = "test-session";
            int requestedLines = 200;
            String expectedLog = "line 1\nline 2";
            String expectedCmd = LinuxCommands.tail("/var/log/samba/log.smbd", 200);
            when(commandExecutor.execute(eq(sessionId), eq(expectedCmd))).thenReturn(expectedLog);

            // when
            String actualLog = sambaLogService.getRecentLogs(sessionId, requestedLines);

            // then
            assertThat(actualLog).isEqualTo(expectedLog);
        }

        @Test
        @DisplayName("An unsupported line count retrieves the default 50-line tail")
        void getRecentLogs_withDisallowedLinesCount_defaultsTo50Lines() throws Exception {
            // given
            String sessionId = "test-session";
            int invalidLines = 999;
            String expectedLog = "default log content";
            String expectedCmd = LinuxCommands.tail("/var/log/samba/log.smbd", 50);
            when(commandExecutor.execute(eq(sessionId), eq(expectedCmd))).thenReturn(expectedLog);

            // when
            String actualLog = sambaLogService.getRecentLogs(sessionId, invalidLines);

            // then
            assertThat(actualLog).isEqualTo(expectedLog);
        }

        @Test
        @DisplayName("A failed tail command returns the log accessibility message")
        void getRecentLogs_whenCommandThrows_returnsFriendlyErrorMessage() throws Exception {
            // given
            String sessionId = "test-session";
            when(commandExecutor.execute(eq(sessionId), anyString())).thenThrow(new RuntimeException("Access denied"));

            // when
            String actualLog = sambaLogService.getRecentLogs(sessionId, 50);

            // then
            assertThat(actualLog).contains("Log file empty or not accessible");
            assertThat(actualLog).contains("/var/log/samba/log.smbd");
        }
    }
}
