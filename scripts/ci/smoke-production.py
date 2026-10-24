#!/usr/bin/env python3
"""Exercise HTTPS, real SSH/SMB and persistence in a disposable Compose project."""

import argparse
import http.cookiejar
import json
import os
from pathlib import Path
import secrets
import ssl
import subprocess
import tempfile
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]


def check(condition, message):
    if not condition:
        raise RuntimeError(message)


class Fixture:
    def __init__(self, image, directory):
        self.directory = Path(directory)
        self.environment = {
            **os.environ, "SAMBRYX_IMAGE": image,
            "TLS_CERT_FILE": str(self.directory / "certificate.pem"),
            "TLS_KEY_FILE": str(self.directory / "tls.key"),
            "SSH_AUTHORIZED_KEYS_FILE": str(self.directory / "management.pub"),
            "SSH_KNOWN_HOSTS_FILE": str(self.directory / "known_hosts"),
            "PROXY_UID": str(os.getuid()), "PROXY_GID": str(os.getgid()),
            "HTTPS_BIND_IP": "127.0.0.1", "HTTPS_PORT": "0",
            "SAMBA_BIND_IP": "127.0.0.1", "SAMBA_PORT": "0",
        }
        self.compose = ["docker", "compose", "--env-file", "/dev/null",
                        "--project-name", "sg-smoke-" + uuid.uuid4().hex[:12],
                        "-f", str(ROOT / "docker-compose.production.yml"),
                        "-f", str(ROOT / "docker-compose.samba.yml")]
        self.password = secrets.token_hex(24)
        self.opener = None
        self.cookies = None
        self.url = None

    def command(self, arguments, **options):
        return subprocess.run(arguments, cwd=ROOT, env=self.environment, check=True, **options)

    def compose_command(self, *arguments, **options):
        return self.command(self.compose + list(arguments), **options)

    def node(self, *arguments, **options):
        return self.compose_command("exec", "-T", "samba-node", *arguments, **options)

    def prepare(self):
        trust = Path(self.environment["SSH_KNOWN_HOSTS_FILE"])
        trust.write_text("")
        trust.chmod(0o644)
        self.command(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                      "-keyout", self.environment["TLS_KEY_FILE"],
                      "-out", self.environment["TLS_CERT_FILE"], "-days", "1",
                      "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost,IP:127.0.0.1"],
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        Path(self.environment["TLS_KEY_FILE"]).chmod(0o600)
        self.command(["ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", str(self.directory / "management")])

    def start(self, recreate=False):
        args = ["up", "--detach", "--wait", "--wait-timeout", "180", "--no-build"]
        if recreate:
            args.append("--force-recreate")
        self.compose_command(*args)
        binding = self.compose_command("port", "nginx", "8443", capture_output=True, text=True).stdout.strip()
        self.url = "https://" + binding
        self.cookies = http.cookiejar.CookieJar()
        context = ssl.create_default_context(cafile=self.environment["TLS_CERT_FILE"])
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPSHandler(context=context), urllib.request.HTTPCookieProcessor(self.cookies))

    def api(self, path, body=None):
        headers = {}
        content = None
        if body is not None:
            token = next(cookie.value for cookie in self.cookies if cookie.name == "XSRF-TOKEN")
            headers = {"Content-Type": "application/json", "X-XSRF-TOKEN": token}
            content = json.dumps(body).encode()
        request = urllib.request.Request(self.url + path, content, headers)
        with self.opener.open(request, timeout=60) as response:
            value = json.load(response)
        check(value.get("success"), "API operation failed: " + path)
        return value.get("data")

    def login(self):
        check(self.api("/api/auth/environment")["mode"] == "remote", "Expected SSH mode")
        try:
            self.opener.open(self.url + "/api/auth/me", timeout=10)
        except urllib.error.HTTPError as error:
            try:
                check(error.code == 401, "Unexpected anonymous response")
            finally:
                error.close()
        else:
            raise RuntimeError("Anonymous session was accepted")
        self.api("/api/auth/login", {"host": "samba-node", "username": "root", "authType": "key",
                                    "privateKey": (self.directory / "management").read_text()})
        check(self.api("/api/auth/me")["user"] == "root", "SSH login failed")
        session = next(cookie for cookie in self.cookies if cookie.name == "JSESSIONID")
        check(session.secure and session.has_nonstandard_attr("HttpOnly"), "Session cookie is not protected")
        check(self.api("/api/monitoring/dashboard")["isRunning"], "Samba is not running")
        print("PASS: verified TLS, anonymous 401, CSRF, real SSH key login and secure session cookie", flush=True)

    def verify_host_identity(self):
        self.api("/api/auth/environment")
        credentials = {"host": "samba-node", "username": "root", "authType": "key",
                       "privateKey": (self.directory / "management").read_text()}
        expected = self.node("ssh-keygen", "-lf", "/etc/ssh/ssh_host_ed25519_key.pub", "-E", "sha256", capture_output=True, text=True).stdout.split()[1]
        for reason in ("UNKNOWN", "CHANGED"):
            if reason == "CHANGED":
                # A different valid public key simulates a server whose identity changed.
                Path(self.environment["SSH_KNOWN_HOSTS_FILE"]).write_text(
                    "samba-node " + (self.directory / "management.pub").read_text())
            try:
                self.api("/api/auth/login", credentials)
            except urllib.error.HTTPError as error:
                with error:
                    check(error.code == 409, "Host identity failure must be a conflict")
                    failure = json.load(error)["data"]
                    check(failure["code"] == "SSH_HOST_KEY_" + reason, "Wrong host identity error")
                    check(failure["fingerprint"] == expected, "Observed fingerprint differs from the server console")
            else:
                raise RuntimeError("Untrusted SSH host was accepted")
            try:
                self.opener.open(self.url + "/api/auth/me", timeout=10)
            except urllib.error.HTTPError as error:
                with error:
                    check(error.code == 401, "Host identity failure authenticated a user")
            else:
                raise RuntimeError("Host identity failure created an authenticated session")
        public = self.node("cat", "/etc/ssh/ssh_host_ed25519_key.pub", capture_output=True, text=True).stdout
        # Docker exec is the fixture's trusted administrative channel, not a network key scan.
        Path(self.environment["SSH_KNOWN_HOSTS_FILE"]).write_text("samba-node " + public)
        print("PASS: unknown and changed SSH keys rejected without authentication; administrator key provisioned", flush=True)

    def smb(self, write=False):
        # Credentials are delivered on stdin, written into a private temporary file and never logged.
        program = '''import json, pathlib, subprocess, sys, tempfile
value=json.load(sys.stdin)
with tempfile.TemporaryDirectory() as directory:
    root=pathlib.Path(directory)
    auth=root/'auth'
    auth.write_text('username = sgfixture\\npassword = '+value['password']+'\\n')
    auth.chmod(0o600)
    source=root/'source.txt'
    source.write_text('persistent SMB fixture')
    action=('put '+str(source)+' sentinel.txt') if value['write'] else ('get sentinel.txt '+str(root/'download.txt'))
    result=subprocess.run(['smbclient','//127.0.0.1/lifecycle','-A',str(auth),'-c',action],capture_output=True)
    if result.returncode: raise SystemExit('SMB fixture operation failed')
    if not value['write'] and (root/'download.txt').read_text()!='persistent SMB fixture':
        raise SystemExit('SMB fixture content differs')
'''
        self.node("python3", "-c", program, input=json.dumps({"password": self.password, "write": write}), text=True)

    def verify_persistence(self):
        self.api("/api/users", {"username": "sgfixture", "password": self.password, "fullName": "Disposable fixture"})
        self.api("/api/shares", {"name": "lifecycle", "path": "/srv/samba/lifecycle", "readOnly": False,
                                 "guestOk": False, "browseable": True, "validUsers": "sgfixture",
                                 "forceUser": "sgfixture", "directoryMask": "0770", "createMask": "0660"})
        identity = self.node("id", "-u", "sgfixture", capture_output=True, text=True).stdout
        fingerprint = self.node("ssh-keygen", "-lf", "/etc/ssh/ssh_host_ed25519_key.pub",
                                capture_output=True, text=True).stdout
        self.smb(write=True)
        self.api("/api/monitoring/control?action=restart", {})
        check(self.api("/api/monitoring/dashboard")["isRunning"], "Service restart failed")
        self.start(recreate=True)
        self.login()
        check(self.node("id", "-u", "sgfixture", capture_output=True, text=True).stdout == identity,
              "User identity changed after recreation")
        check(self.node("ssh-keygen", "-lf", "/etc/ssh/ssh_host_ed25519_key.pub",
                        capture_output=True, text=True).stdout == fingerprint, "SSH host identity changed")
        self.smb()
        check(any(share["name"] == "lifecycle" for share in self.api("/api/shares")), "Share configuration was lost")
        print("PASS: real SMB write/read, service restart, UID, host key, password, shares and data survive recreation", flush=True)

    def cleanup(self):
        subprocess.run(self.compose + ["down", "--volumes", "--remove-orphans"], cwd=ROOT,
                       env=self.environment, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image", help="Locally built application image; the Samba and nginx images must also be available")
    arguments = parser.parse_args()
    check(os.getuid() != 0, "Run the smoke test as a non-root Docker user")
    with tempfile.TemporaryDirectory(prefix="sambryx-production-smoke-") as directory:
        fixture = Fixture(arguments.image, directory)
        try:
            fixture.prepare()
            fixture.start()
            fixture.verify_host_identity()
            fixture.login()
            fixture.verify_persistence()
        except Exception:
            # Application logs may contain credentials; collect infrastructure diagnostics only.
            subprocess.run(fixture.compose + ["logs", "--tail", "40", "nginx", "samba-node"],
                           cwd=ROOT, env=fixture.environment, check=False)
            raise
        finally:
            fixture.cleanup()


if __name__ == "__main__":
    main()
