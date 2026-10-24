"""Validate rendered deployment models without starting containers."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class ProductionComposeTest(unittest.TestCase):
    def render(self, bundled=False, missing_image=False, project_name="sambryx-production"):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory) / "fixture"
            fixture.write_text("fixture-only")
            environment = {
                **os.environ,
                "SAMBRYX_IMAGE": "sambryx-fixture:1.0.0",
                "COMPOSE_PROJECT_NAME": project_name,
                "TLS_CERT_FILE": str(fixture),
                "TLS_KEY_FILE": str(fixture),
                "SSH_AUTHORIZED_KEYS_FILE": str(fixture),
                "SSH_KNOWN_HOSTS_FILE": str(fixture),
                "PROXY_UID": "1000", "PROXY_GID": "1000",
                "HTTPS_BIND_IP": "127.0.0.1", "HTTPS_PORT": "8443",
                "SAMBA_BIND_IP": "127.0.0.1", "SAMBA_PORT": "445",
            }
            if missing_image:
                environment.pop("SAMBRYX_IMAGE")
            command = ["docker", "compose", "--env-file", "/dev/null",
                       "-f", str(ROOT / "docker-compose.production.yml")]
            if bundled:
                command += ["-f", str(ROOT / "docker-compose.samba.yml")]
            result = subprocess.run(command + ["config", "--format", "json"],
                                    env=environment, capture_output=True, text=True, cwd=ROOT)
            if missing_image:
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("SAMBRYX_IMAGE", result.stderr)
                return None
            self.assertEqual(result.returncode, 0, result.stderr)
            return json.loads(result.stdout)

    def test_external_server_mode_exposes_only_https(self):
        model = self.render()
        self.assertEqual(set(model["services"]), {"web", "nginx"})
        web, proxy = model["services"]["web"], model["services"]["nginx"]
        self.assertFalse(web.get("ports"))
        trust = next(mount for mount in web["volumes"] if mount["target"] == "/etc/sambryx/known_hosts")
        self.assertTrue(trust["read_only"])
        self.assertFalse(trust["bind"]["create_host_path"])
        self.assertEqual([port["target"] for port in proxy["ports"]], [8443])
        self.assertEqual(proxy["ports"][0]["host_ip"], "127.0.0.1")
        self.assertTrue(model["networks"]["proxy"]["internal"])
        for service in (web, proxy):
            self.assertTrue(service["read_only"])
            self.assertNotEqual(service["user"].split(":")[0], "0")
            self.assertIn("ALL", service["cap_drop"])
            self.assertFalse(service.get("privileged", False))

    def test_tls_material_is_read_only_and_not_environment_content(self):
        proxy = self.render()["services"]["nginx"]
        self.assertEqual({secret["source"] for secret in proxy["secrets"]},
                         {"tls_certificate", "tls_private_key"})
        self.assertTrue(all(mount["read_only"] for mount in proxy["volumes"]))
        self.assertNotIn("environment", proxy)

    def test_bundled_server_persists_identity_and_does_not_publish_ssh(self):
        model = self.render(bundled=True)
        node = model["services"]["samba-node"]
        self.assertEqual([port["target"] for port in node["ports"]], [445])
        self.assertFalse(node.get("privileged", False))
        mounts = {mount["target"]: mount for mount in node["volumes"]}
        for target in ("/etc", "/var/lib/samba", "/srv/samba", "/home"):
            self.assertEqual(mounts[target]["type"], "volume")
            self.assertTrue(mounts[target]["source"])
        self.assertTrue(model["networks"]["management"]["internal"])
        self.assertTrue({"proxy", "ssh-egress", "management"}.issubset(
            model["services"]["web"]["networks"]))
        self.assertNotIn("AUTO_ADMIN_PASS", node.get("environment", {}))
        self.assertEqual([secret["source"] for secret in node["secrets"]], ["ssh_authorized_keys"])

    def test_existing_project_keeps_its_persistent_volume_names(self):
        model = self.render(bundled=True, project_name="shareguard-production")
        self.assertEqual(model["name"], "shareguard-production")
        for volume in ("node-identity", "samba-state", "samba-data", "samba-home", "samba-logs"):
            self.assertEqual(model["volumes"][volume]["name"], "shareguard-production_" + volume)

    def test_application_image_is_mandatory(self):
        self.render(missing_image=True)


if __name__ == "__main__":
    unittest.main()
