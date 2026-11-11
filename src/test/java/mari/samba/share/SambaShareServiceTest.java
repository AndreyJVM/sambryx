package mari.samba.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.util.Collections;
import mari.samba.filesystem.FileSystemService;
import mari.samba.infra.CommandExecutor;
import mari.samba.infra.LinuxCommands;
import mari.samba.smbconfig.SambaConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@Epic("Shares and access")
@Feature("Unit: share command orchestration (mock collaborators)")
class SambaShareServiceTest {

    @Mock
    private CommandExecutor commandExecutor;

    @Mock
    private SambaConfigService configService;

    @Mock
    private FileSystemService fileSystemService;

    @InjectMocks
    private SambaShareService shareService;

    private final String SESSION_ID = "test-session-id";

    @ParameterizedTest
    @ValueSource(strings = {"0700", "0755", "0770", "0777"})
    void directoryMaskIsOnlyUsedInSambaConfiguration(String directoryMask) {
        SambaShareCreateDto dto = SambaShareCreateDto.builder()
                .name("team")
                .path("/srv/team")
                .createMask("0644")
                .directoryMask(directoryMask)
                .build();
        when(configService.getSmbConfContent(SESSION_ID)).thenReturn("");
        when(configService.parseShares("")).thenReturn(Collections.emptyList());
        when(configService.buildShareSection(dto)).thenReturn("[team]\n");

        shareService.createShare(SESSION_ID, dto);

        verify(commandExecutor).execute(SESSION_ID, LinuxCommands.mkdir("/srv/team"));
        verifyNoMoreInteractions(commandExecutor);
    }

    @Test
    void createShareEnsuresDirectoryWithoutChangingPermissions() throws Exception {
        // Arrange
        SambaShareCreateDto dto = SambaShareCreateDto.builder()
                .name("testShare")
                .path("/srv/samba/test")
                .comment("Test comment")
                .forceUser("samba-admin")
                .validUsers("user1, user2")
                .readOnly(false)
                .browseable(true)
                .build();

        when(configService.getSmbConfContent(SESSION_ID)).thenReturn("");
        when(configService.parseShares("")).thenReturn(Collections.emptyList());

        when(configService.buildShareSection(dto)).thenReturn("[testShare]\n  path = /srv/samba/test\n");

        // Act
        shareService.createShare(SESSION_ID, dto);

        // Assert
        verify(fileSystemService).requireAllowedPath("/srv/samba/test");
        verify(commandExecutor).execute(SESSION_ID, LinuxCommands.mkdir("/srv/samba/test"));
        verifyNoMoreInteractions(commandExecutor);

        verify(configService).updateSmbConf(SESSION_ID, "\n[testShare]\n  path = /srv/samba/test\n");
    }

    @Test
    void createShare_ShouldThrowException_WhenShareAlreadyExists() throws Exception {
        // Arrange
        SambaShareCreateDto dto = SambaShareCreateDto.builder()
                .name("existingShare")
                .path("/srv/samba/existing")
                .build();

        SambaShare existingShare = SambaShare.builder().name("existingShare").build();

        when(configService.getSmbConfContent(SESSION_ID)).thenReturn("");
        when(configService.parseShares("")).thenReturn(Collections.singletonList(existingShare));

        // Act & Assert
        assertThatThrownBy(() -> shareService.createShare(SESSION_ID, dto))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Share already exists");

        verify(commandExecutor, never()).execute(anyString(), anyString());
    }

    @Test
    void getShareSize_ShouldReturnFormattedSize() throws Exception {
        SambaShare share =
                SambaShare.builder().name("dev").path("/srv/samba/dev").build();
        when(configService.getSmbConfContent(SESSION_ID)).thenReturn("");
        when(configService.parseShares("")).thenReturn(Collections.singletonList(share));
        when(commandExecutor.execute(eq(SESSION_ID), anyString())).thenReturn("24K\t/srv/samba/dev\n");

        String size = shareService.getShareSize(SESSION_ID, "dev");
        assertThat(size).isEqualTo("24 KB");
    }

    @Test
    void getShareSize_ShouldReturnZeroBytes_WhenDuReturnsZero() throws Exception {
        SambaShare share =
                SambaShare.builder().name("empty").path("/srv/samba/empty").build();
        when(configService.getSmbConfContent(SESSION_ID)).thenReturn("");
        when(configService.parseShares("")).thenReturn(Collections.singletonList(share));
        when(commandExecutor.execute(eq(SESSION_ID), anyString())).thenReturn("0\n");

        String size = shareService.getShareSize(SESSION_ID, "empty");
        assertThat(size).isEqualTo("0 B");
    }

    @Test
    void getShareSize_ShouldReturnNA_WhenPathIsBlank() {
        SambaShare share = SambaShare.builder().name("nopath").path("").build();
        when(configService.getSmbConfContent(SESSION_ID)).thenReturn("");
        when(configService.parseShares("")).thenReturn(Collections.singletonList(share));

        String size = shareService.getShareSize(SESSION_ID, "nopath");
        assertThat(size).isEqualTo("N/A");
    }
}
