import importlib.util
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import tempfile
import threading
import unittest
import urllib.error
import zipfile


spec = importlib.util.spec_from_file_location(
    "verify_artifacts", Path(__file__).resolve().parents[1] / "checks/verify-artifacts.py"
)
artifacts = importlib.util.module_from_spec(spec)
spec.loader.exec_module(artifacts)


class ArtifactVerificationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.jar = self.directory / "application.jar"

    def write_jar(self, include_index=True, include_assets=True):
        with zipfile.ZipFile(self.jar, "w") as archive:
            if include_index:
                archive.writestr(
                    "BOOT-INF/classes/static/ui/index.html",
                    '<script src="/ui/assets/app.js"></script>'
                    '<link rel="stylesheet" href="/ui/assets/app.css">',
                )
            if include_assets:
                archive.writestr("BOOT-INF/classes/static/ui/assets/app.js", "fixture-js")
                archive.writestr("BOOT-INF/classes/static/ui/assets/app.css", "fixture-css")

    def test_complete_frontend_is_read_from_jar(self):
        self.write_jar()
        files = artifacts.frontend_files(self.jar)
        self.assertEqual(files["/ui/assets/app.js"], b"fixture-js")
        self.assertEqual(files["/ui/assets/app.css"], b"fixture-css")

    def test_backend_only_jar_is_rejected(self):
        self.write_jar(include_index=False)
        with self.assertRaises(KeyError):
            artifacts.frontend_files(self.jar)

    def test_missing_bundle_is_rejected(self):
        self.write_jar(include_assets=False)
        with self.assertRaises(KeyError):
            artifacts.frontend_files(self.jar)

    def test_placeholder_index_without_frontend_is_rejected(self):
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr("BOOT-INF/classes/static/ui/index.html", "<html>placeholder</html>")
        with self.assertRaisesRegex(ValueError, "no JavaScript bundle"):
            artifacts.frontend_files(self.jar)


class RuntimeVerificationTest(unittest.TestCase):
    def setUp(self):
        self.files = {"/ui/": b"fixture-index", "/ui/assets/app.js": b"fixture-js"}
        routes = {
            path: (200, content) for path, content in self.files.items()
        }
        routes["/api/auth/environment"] = (
            200, json.dumps({"success": True, "data": {"mode": "remote"}}).encode()
        )
        routes["/api/auth/me"] = (401, b'{}')
        self.routes = routes

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                status, content = routes.get(self.path, (404, b"missing"))
                self.send_response(status)
                self.send_header("Content-Length", str(len(content)))
                self.end_headers()
                self.wfile.write(content)

            def log_message(self, *arguments):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.addCleanup(self.server.server_close)
        worker = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.01})
        worker.start()
        self.addCleanup(worker.join)
        self.addCleanup(self.server.shutdown)
        self.url = f"http://127.0.0.1:{self.server.server_port}"

    def test_valid_remote_runtime_passes(self):
        artifacts.verify_http(self.url, self.files)

    def test_served_bundle_different_from_jar_is_rejected(self):
        self.routes["/ui/assets/app.js"] = (200, b"stale-bundle")
        with self.assertRaisesRegex(ValueError, "differs from JAR"):
            artifacts.verify_http(self.url, self.files)

    def test_local_execution_in_remote_image_is_rejected(self):
        self.routes["/api/auth/environment"] = (
            200, json.dumps({"success": True, "data": {"mode": "local"}}).encode()
        )
        with self.assertRaisesRegex(ValueError, "remote execution"):
            artifacts.verify_http(self.url, self.files)

    def test_anonymous_session_access_is_rejected(self):
        self.routes["/api/auth/me"] = (200, b'{}')
        with self.assertRaisesRegex(ValueError, "Anonymous access"):
            artifacts.verify_http(self.url, self.files)

    def test_forbidden_is_not_accepted_as_expected_unauthorized(self):
        self.routes["/api/auth/me"] = (403, b'{}')
        with self.assertRaises(urllib.error.HTTPError) as failure:
            artifacts.verify_http(self.url, self.files)
        self.assertEqual(failure.exception.code, 403)
        failure.exception.close()


if __name__ == "__main__":
    unittest.main()
