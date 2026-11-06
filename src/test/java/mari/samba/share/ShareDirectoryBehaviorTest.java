package mari.samba.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.stream.Stream;
import mari.samba.core.SambaCommandException;
import mari.samba.filesystem.FileSystemService;
import mari.samba.infra.CommandExecutor;
import mari.samba.infra.LinuxCommands;
import mari.samba.smbconfig.SambaConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ShareDirectoryBehaviorTest {
    private static final String SESSION = "directory-fixture";
    private static final String PATH = "/srv/samba/team";

    @Mock
    private SambaConfigService config;

    @Mock
    private CommandExecutor commands;

    @Mock
    private FileSystemService filesystem;

    private SambaShareService service;

    @BeforeEach
    void setUp() {
        service = new SambaShareService(config, commands, filesystem);
    }

    @ParameterizedTest
    @CsvSource(
            value = {
                "true,false,NULL,0755",
                "true,true,NULL,0755",
                "false,false,NULL,0775",
                "false,true,NULL,0777",
                "false,true,' 0770 ',0770",
                "true,false,'',0755"
            },
            nullValues = "NULL")
    void preservesPermissionSelectionAndPersistsConfiguration(
            boolean readOnly, boolean guest, String mask, String expected) {
        var request = SambaShareCreateDto.builder()
                .name("team")
                .path(PATH)
                .readOnly(readOnly)
                .guestOk(guest)
                .directoryMask(mask)
                .createMask("0600")
                .build();
        prepareConfigurationUpdate();

        service.updateShare(SESSION, "team", request);

        verify(commands).execute(SESSION, LinuxCommands.chmod(expected, PATH));
        verify(config).updateSmbConf(SESSION, "remaining\n[team]");
    }

    @ParameterizedTest
    @MethodSource("ownershipCases")
    void preservesOwnerPrecedenceAndRecursiveOwnership(
            String forcedUser, String forcedGroup, String validUsers, String expectedOwner) {
        var request = SambaShareCreateDto.builder()
                .name("team")
                .path(PATH)
                .readOnly(false)
                .forceUser(forcedUser)
                .forceGroup(forcedGroup)
                .validUsers(validUsers)
                .build();
        prepareConfigurationUpdate();

        service.updateShare(SESSION, "team", request);

        var executed = ArgumentCaptor.forClass(String.class);
        verify(commands, times(expectedOwner == null ? 2 : 3)).execute(eq(SESSION), executed.capture());
        var expectedCommands =
                new java.util.ArrayList<>(List.of(LinuxCommands.mkdir(PATH), LinuxCommands.chmod("0775", PATH)));
        if (expectedOwner != null) expectedCommands.add(LinuxCommands.chownRecursive(expectedOwner, PATH));
        assertThat(executed.getAllValues()).containsExactlyElementsOf(expectedCommands);
        verify(config).updateSmbConf(SESSION, "remaining\n[team]");
    }

    static Stream<Arguments> ownershipCases() {
        return Stream.of(
                Arguments.of(" alice ", " smb_team ", "bob", "alice:smb_team"),
                Arguments.of("alice", null, "@smb_team", "alice"),
                Arguments.of(null, "smb_team", "bob, alice", "bob:smb_team"),
                Arguments.of(null, "smb_team", "@other", ":smb_team"),
                Arguments.of(null, null, "@smb_team, alice", ":smb_team"),
                Arguments.of(null, null, " bob , alice", "bob"),
                Arguments.of(" ", " ", " ", null),
                Arguments.of(null, "smb_team", null, ":smb_team"),
                Arguments.of(null, null, null, null));
    }

    @Test
    void preparesDirectoryBeforeChangingConfigurationAndKeepsTheExistingShareName() {
        var request = SambaShareCreateDto.builder()
                .name("different")
                .path(" " + PATH + " ")
                .directoryMask("0770")
                .forceUser("alice")
                .readOnly(false)
                .build();
        prepareConfigurationUpdate();

        service.updateShare(SESSION, "team", request);

        var order = inOrder(filesystem, commands, config);
        order.verify(filesystem).requireAllowedPath(PATH);
        order.verify(commands).execute(SESSION, LinuxCommands.mkdir(PATH));
        order.verify(commands).execute(SESSION, LinuxCommands.chmod("0770", PATH));
        order.verify(commands).execute(SESSION, LinuxCommands.chownRecursive("alice", PATH));
        order.verify(config).getSmbConfContent(SESSION);
        order.verify(config).removeShareSection("current", "team");
        order.verify(config).buildShareSection(request.withName("team"));
        order.verify(config).updateSmbConf(SESSION, "remaining\n[team]");
    }

    enum Failure {
        PATH,
        MKDIR,
        CHMOD,
        CHOWN
    }

    @ParameterizedTest
    @EnumSource(Failure.class)
    void directoryFailureStopsLaterCommandsAndConfigurationUpdate(Failure step) {
        var failure = new SambaCommandException("Synthetic directory failure");
        var request = SambaShareCreateDto.builder()
                .name("team")
                .path(PATH)
                .forceUser("alice")
                .readOnly(false)
                .build();
        if (step == Failure.PATH) doThrow(failure).when(filesystem).requireAllowedPath(PATH);
        else {
            String rejectedCommand =
                    switch (step) {
                        case MKDIR -> LinuxCommands.mkdir(PATH);
                        case CHMOD -> LinuxCommands.chmod("0775", PATH);
                        case CHOWN -> LinuxCommands.chownRecursive("alice", PATH);
                        default -> throw new AssertionError("Unexpected stage");
                    };
            when(commands.execute(eq(SESSION), anyString())).thenAnswer(call -> {
                if (rejectedCommand.equals(call.getArgument(1))) throw failure;
                return "";
            });
        }

        assertThatThrownBy(() -> service.updateShare(SESSION, "team", request)).isSameAs(failure);

        var executed = ArgumentCaptor.forClass(String.class);
        int count =
                switch (step) {
                    case PATH -> 0;
                    case MKDIR -> 1;
                    case CHMOD -> 2;
                    case CHOWN -> 3;
                };
        if (count == 0) verifyNoInteractions(commands);
        else {
            verify(commands, times(count)).execute(eq(SESSION), executed.capture());
            assertThat(executed.getAllValues())
                    .containsExactlyElementsOf(List.of(
                                    LinuxCommands.mkdir(PATH),
                                    LinuxCommands.chmod("0775", PATH),
                                    LinuxCommands.chownRecursive("alice", PATH))
                            .subList(0, count));
        }
        verifyNoInteractions(config);
    }

    @Test
    void invalidMaskStillFailsAfterMkdirWithoutPersistingConfiguration() {
        var request = SambaShareCreateDto.builder()
                .name("team")
                .path(PATH)
                .directoryMask("invalid")
                .build();

        assertThatThrownBy(() -> service.updateShare(SESSION, "team", request)).isInstanceOf(SecurityException.class);

        verify(commands).execute(SESSION, LinuxCommands.mkdir(PATH));
        verifyNoMoreInteractions(commands);
        verifyNoInteractions(config);
    }

    private void prepareConfigurationUpdate() {
        when(config.getSmbConfContent(SESSION)).thenReturn("current");
        when(config.removeShareSection("current", "team")).thenReturn("remaining");
        when(config.buildShareSection(any())).thenReturn("[team]");
    }
}
