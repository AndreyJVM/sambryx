package mari.samba.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.List;
import mari.samba.core.SambaCommandException;
import mari.samba.core.SshSessionExpiredException;
import mari.samba.infra.CommandExecutor;
import mari.samba.infra.LinuxCommands;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SambaUserServiceTest {

    @Mock
    private CommandExecutor commandExecutor;

    @InjectMocks
    private SambaUserService sambaUserService;

    private static final String SESSION_ID = "test-session";

    @Test
    void systemUsernamesExposeOnlySortedDistinctNames() {
        when(commandExecutor.execute(SESSION_ID, LinuxCommands.listSystemUsers()))
                .thenReturn("bob:x:1002:1002:Bob:/home/bob:/bin/bash\r\n"
                        + "alice:x:1001:1001:Alice:/home/alice:/usr/sbin/nologin\r\n"
                        + "bob:x:1002:1002:Bob:/home/bob:/bin/bash\n"
                        + "invalid line\n:secret:1:1:Missing name:/tmp:/bin/sh\n");

        assertEquals(List.of("alice", "bob"), sambaUserService.getSystemUsernames(SESSION_ID));
        verify(commandExecutor).execute(SESSION_ID, "getent passwd");
        verifyNoMoreInteractions(commandExecutor);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void emptySystemUserDatabaseReturnsEmptySuggestions(String output) {
        when(commandExecutor.execute(SESSION_ID, LinuxCommands.listSystemUsers()))
                .thenReturn(output);

        assertEquals(List.of(), sambaUserService.getSystemUsernames(SESSION_ID));
    }

    @Test
    void systemUserLookupDoesNotHideExecutionFailure() {
        RuntimeException failure = new RuntimeException("Lookup failed");
        when(commandExecutor.execute(SESSION_ID, LinuxCommands.listSystemUsers()))
                .thenThrow(failure);

        assertSame(
                failure, assertThrows(RuntimeException.class, () -> sambaUserService.getSystemUsernames(SESSION_ID)));
    }

    @Test
    void getAllUsers_ShouldParsePdbeditOutputCorrectly() throws Exception {

        String mockOutput = "admin:1000:Server Administrator\n" + "bob:1001:\n" + "alice:1002:Alice Wonderland\n";

        when(commandExecutor.execute(SESSION_ID, LinuxCommands.listSambaUsers()))
                .thenReturn(mockOutput);

        List<SambaUser> users = sambaUserService.getAllUsers(SESSION_ID);

        assertNotNull(users);
        assertEquals(3, users.size());

        assertEquals("admin", users.get(0).getUsername());
        assertEquals("Server Administrator", users.get(0).getFullName());

        assertEquals("bob", users.get(1).getUsername());
        assertEquals("-", users.get(1).getFullName());

        assertEquals("alice", users.get(2).getUsername());
    }

    @Test
    void createUser_ShouldExecuteCorrectSequenceOfCommands() throws Exception {
        String username = "newuser";
        String password = "securepass123";
        String fullName = "New User";

        sambaUserService.createUser(SESSION_ID, username, password, fullName);

        verify(commandExecutor).execute(SESSION_ID, LinuxCommands.addSystemUserWithHome(username, fullName));
        verify(commandExecutor)
                .execute(eq(SESSION_ID), eq(LinuxCommands.chpasswd()), eq(username + ":" + password + "\n"));
        verify(commandExecutor)
                .execute(
                        eq(SESSION_ID),
                        eq(LinuxCommands.addSambaUser(username)),
                        eq(password + "\n" + password + "\n"));
        verify(commandExecutor).execute(SESSION_ID, LinuxCommands.enableSambaUser(username));
    }

    @Test
    void createUser_ShouldRejectBlankOrNewlinePassword() {
        assertThrows(
                IllegalArgumentException.class, () -> sambaUserService.createUser(SESSION_ID, "user1", "", "User One"));
        assertThrows(
                IllegalArgumentException.class,
                () -> sambaUserService.createUser(SESSION_ID, "user1", "pass\ninjection", "User One"));
        assertThrows(
                IllegalArgumentException.class,
                () -> sambaUserService.createUser(SESSION_ID, "user1", "pass\rinjection", "User One"));
    }

    @Test
    void sambaDeletionFailureStopsBeforeLinuxDeletion() {
        var failure = new SambaCommandException("Samba deletion failed");
        when(commandExecutor.execute(SESSION_ID, LinuxCommands.deleteSambaUser("alice")))
                .thenThrow(failure);
        assertSame(
                failure,
                assertThrows(SambaCommandException.class, () -> sambaUserService.deleteUser(SESSION_ID, "alice")));
        verify(commandExecutor).execute(SESSION_ID, LinuxCommands.deleteSambaUser("alice"));
        verifyNoMoreInteractions(commandExecutor);
    }

    @Test
    void deletionPreservesFilesAndRunsOnlyAccountCommandsInOrder() {
        sambaUserService.deleteUser(SESSION_ID, "alice");
        var order = inOrder(commandExecutor);
        order.verify(commandExecutor).execute(SESSION_ID, "sudo smbpasswd -x 'alice'");
        order.verify(commandExecutor).execute(SESSION_ID, "sudo userdel -- 'alice'");
        verifyNoMoreInteractions(commandExecutor);
    }

    @Test
    void linuxDeletionFailureReportsPartialCompletionAndKeepsCause() {
        when(commandExecutor.execute(SESSION_ID, LinuxCommands.deleteSambaUser("alice")))
                .thenReturn("");
        var failure = new SambaCommandException("Linux deletion failed");
        when(commandExecutor.execute(SESSION_ID, LinuxCommands.deleteSystemUser("alice")))
                .thenThrow(failure);
        var error = assertThrows(SambaCommandException.class, () -> sambaUserService.deleteUser(SESSION_ID, "alice"));
        assertTrue(error.getMessage().contains("Samba account was deleted"));
        assertTrue(error.getMessage().contains("Linux account deletion failed"));
        assertSame(failure, error.getCause());
        verify(commandExecutor).execute(SESSION_ID, LinuxCommands.deleteSambaUser("alice"));
        verify(commandExecutor).execute(SESSION_ID, LinuxCommands.deleteSystemUser("alice"));
        verifyNoMoreInteractions(commandExecutor);
    }

    @Test
    void expiredSessionAfterSambaDeletionReportsPartialCompletion() {
        when(commandExecutor.execute(SESSION_ID, LinuxCommands.deleteSambaUser("alice")))
                .thenReturn("");
        when(commandExecutor.execute(SESSION_ID, LinuxCommands.deleteSystemUser("alice")))
                .thenThrow(new SshSessionExpiredException("SSH session expired"));
        var error =
                assertThrows(SshSessionExpiredException.class, () -> sambaUserService.deleteUser(SESSION_ID, "alice"));
        assertTrue(error.getMessage().contains("Samba account was deleted"));
        verify(commandExecutor).execute(SESSION_ID, LinuxCommands.deleteSambaUser("alice"));
        verify(commandExecutor).execute(SESSION_ID, LinuxCommands.deleteSystemUser("alice"));
        verifyNoMoreInteractions(commandExecutor);
    }

    @ParameterizedTest
    @ValueSource(strings = {"root", "daemon", "sshd", "postgres", "mysql", " ROOT "})
    void protectedAccountDeletionNeverExecutesCommands(String username) {
        assertThrows(SecurityException.class, () -> sambaUserService.deleteUser(SESSION_ID, username));
        verifyNoInteractions(commandExecutor);
    }

    @Test
    void changePassword_ShouldUpdateBothSystemAndSamba() throws Exception {
        String username = "bob";
        String newPassword = "newpassword!";

        sambaUserService.changePassword(SESSION_ID, username, newPassword);

        verify(commandExecutor).execute(eq(SESSION_ID), eq(LinuxCommands.chpasswd()), eq("bob:newpassword!\n"));
        verify(commandExecutor)
                .execute(
                        eq(SESSION_ID),
                        eq(LinuxCommands.changeSambaPassword(username)),
                        eq("newpassword!\nnewpassword!\n"));
    }

    @Test
    void changePassword_ShouldRejectBlankOrNewlinePassword() {
        assertThrows(IllegalArgumentException.class, () -> sambaUserService.changePassword(SESSION_ID, "bob", "   "));
        assertThrows(
                IllegalArgumentException.class,
                () -> sambaUserService.changePassword(SESSION_ID, "bob", "pass\nattack"));
    }
}
