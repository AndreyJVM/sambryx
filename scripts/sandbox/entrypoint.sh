#!/bin/bash
set -euo pipefail

password_file=/run/secrets/sandbox_admin_password
if [ ! -s "$password_file" ]; then
    echo "A sandbox administrator password secret is required." >&2
    exit 1
fi
password=$(cat -- "$password_file")
if [ "${#password}" -lt 24 ] || [[ "$password" == *:* || "$password" == *$'\n'* ]]; then
    echo "The sandbox administrator password must contain at least 24 characters without colons or newlines." >&2
    exit 1
fi
printf 'admin:%s\n' "$password" | chpasswd
unset password

# Refresh only the policy owned by this sandbox image; preserve mounted configuration and data.
/setup-samba-server.sh --permissions-only admin
install -d -m 0755 /run/sshd
ssh-keygen -A >/dev/null
testparm -s >/dev/null

# Ensure test directories and mock documents exist (e.g. if mounted over empty named volume)
if [ ! -f /srv/samba/public/welcome_readme.txt ] && [ -x /setup-test-data.sh ]; then
    /setup-test-data.sh || true
fi

# Start Samba Daemons
service smbd start
service nmbd start

# Execute SSH Daemon in foreground to keep container running
exec /usr/sbin/sshd -D
