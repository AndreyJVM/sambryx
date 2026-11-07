package mari.samba.infra;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class LinuxCommandsTest {
    @Test
    void deleteAccountPreservesHomeAndMail() {
        assertEquals("sudo userdel -- 'alice'", LinuxCommands.deleteSystemUser("alice"));
    }

    @Test
    void testEscapeRejectsNewlines() {
        assertThrows(SecurityException.class, () -> LinuxCommands.escape("some\ncommand"));
        assertThrows(SecurityException.class, () -> LinuxCommands.escape("some\rcommand"));
    }

    @Test
    void testEscapeHandlesSingleQuotes() {
        String escaped = LinuxCommands.escape("O'Connor");
        assertEquals("'O'\\''Connor'", escaped);
    }

    @Test
    void testRequireValidUsername() {

        assertDoesNotThrow(() -> LinuxCommands.checkUserExists("validuser_123"));
        assertDoesNotThrow(() -> LinuxCommands.checkUserExists("admin-root"));
        assertDoesNotThrow(() -> LinuxCommands.checkUserExists("john.doe"));

        assertThrows(SecurityException.class, () -> LinuxCommands.checkUserExists("invalid user"));
        assertThrows(SecurityException.class, () -> LinuxCommands.checkUserExists("user;rm-rf"));
        assertThrows(SecurityException.class, () -> LinuxCommands.checkUserExists("1user")); // Cannot start with digit
    }

    @Test
    void testRequireValidPath() {
        assertDoesNotThrow(() -> LinuxCommands.cat("/etc/samba/smb.conf"));
        assertDoesNotThrow(() -> LinuxCommands.cat("/mnt/Data_Drive"));

        // Traversal is blocked
        assertThrows(SecurityException.class, () -> LinuxCommands.cat("/etc/samba/../../shadow"));
        assertThrows(SecurityException.class, () -> LinuxCommands.cat("../config"));

        // Shell injection metacharacters blocked
        assertThrows(SecurityException.class, () -> LinuxCommands.cat("/mnt/data; rm -rf /"));
        assertThrows(SecurityException.class, () -> LinuxCommands.cat("/mnt/data|ls"));
        assertThrows(SecurityException.class, () -> LinuxCommands.cat("/mnt/data&ls"));
        assertThrows(SecurityException.class, () -> LinuxCommands.cat("/mnt/$USER"));
        assertThrows(SecurityException.class, () -> LinuxCommands.cat("/mnt/`ls`"));
    }

    @Test
    void testRequireValidSystemctlAction() {
        assertDoesNotThrow(() -> LinuxCommands.systemctl("restart", "smbd"));
        assertDoesNotThrow(() -> LinuxCommands.systemctl("stop", "nmbd"));
        assertDoesNotThrow(() -> LinuxCommands.systemctl("is-active", "smbd"));

        assertThrows(SecurityException.class, () -> LinuxCommands.systemctl("delete", "smbd"));
        assertThrows(SecurityException.class, () -> LinuxCommands.systemctl("restart; reboot", "smbd"));
    }

    @Test
    void testRequireValidPid() {
        assertDoesNotThrow(() -> LinuxCommands.kill("1234"));
        assertThrows(SecurityException.class, () -> LinuxCommands.kill("1234; rm -rf /"));
        assertThrows(SecurityException.class, () -> LinuxCommands.kill("abc"));
    }

    @Test
    void testListBackupsDetailed_validatesAndEscapesDirectory() {
        String expected =
                "test -d '/etc/samba' && LC_ALL=C find -H '/etc/samba' -maxdepth 1 -name 'smb.conf.backup_*' -exec ls -ldh --time-style=\"+%Y-%m-%d %H:%M:%S\" -- {} +";
        assertEquals(expected, LinuxCommands.listBackupsDetailed("/etc/samba"));

        assertThrows(SecurityException.class, () -> LinuxCommands.listBackupsDetailed("/etc;samba"));
    }

    @Test
    void testGroupCommands_ValidateAndFormat() {
        assertEquals("getent group", LinuxCommands.getentGroup());
        assertEquals("sudo groupadd 'smb_developers'", LinuxCommands.addGroup("smb_developers"));
        assertEquals("sudo groupdel 'smb_developers'", LinuxCommands.deleteGroup("smb_developers"));
        assertEquals(
                "sudo gpasswd -a 'john.doe' 'smb_developers'",
                LinuxCommands.addUserToGroup("john.doe", "smb_developers"));
        assertEquals(
                "sudo gpasswd -d 'john.doe' 'smb_developers'",
                LinuxCommands.removeUserFromGroup("john.doe", "smb_developers"));

        // Malicious group names or usernames are rejected
        assertThrows(SecurityException.class, () -> LinuxCommands.addGroup("dev; rm -rf /"));
        assertThrows(SecurityException.class, () -> LinuxCommands.deleteGroup("dev|ls"));
        assertThrows(SecurityException.class, () -> LinuxCommands.addUserToGroup("bad;user", "smb_developers"));
        assertThrows(SecurityException.class, () -> LinuxCommands.removeUserFromGroup("john", "bad;grp"));
    }

    @Test
    void testChownRecursive_ValidatesProfiles() {
        assertEquals(
                "sudo chown -R 'sambauser' -- '/mnt/share'", LinuxCommands.chownRecursive("sambauser", "/mnt/share"));
        assertEquals(
                "sudo chown -R 'sambauser:sambagroup' -- '/mnt/share'",
                LinuxCommands.chownRecursive("sambauser:sambagroup", "/mnt/share"));
        assertEquals(
                "sudo chown -R ':sambagroup' -- '/mnt/share'",
                LinuxCommands.chownRecursive(":sambagroup", "/mnt/share"));

        assertThrows(SecurityException.class, () -> LinuxCommands.chownRecursive("bad user; rm -rf /", "/mnt/share"));
        assertThrows(SecurityException.class, () -> LinuxCommands.chownRecursive("sambauser", "/mnt/../etc"));
    }

    @Test
    void testTestparmDump() {
        assertEquals("testparm -sv 2>/dev/null", LinuxCommands.testparmDump());
    }

    @Test
    void testDuCommand() {
        String cmd = LinuxCommands.du("/srv/samba/share");
        assertEquals(
                "if [ -e '/srv/samba/share' ]; then du -sh -- '/srv/samba/share' 2>/dev/null || sudo -n du -sh -- '/srv/samba/share' 2>/dev/null || echo '0 B'; else echo '0 B'; fi",
                cmd);
    }
}
