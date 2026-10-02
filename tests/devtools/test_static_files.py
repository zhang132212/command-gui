"""Exercise the real DevStudio handler using an isolated localhost fixture."""
import http.client
import importlib.util
from pathlib import Path
import tempfile
import threading
import unittest


REPO = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("devstudio_under_test", REPO / "devtools/server.py")
DEVSTUDIO = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DEVSTUDIO)


class StaticFileTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.scratch = tempfile.TemporaryDirectory(prefix="command-gui-static-test-")
        cls.base = Path(cls.scratch.name).resolve()
        cls.served = cls.base / "served"
        cls.served.mkdir()
        (cls.served / "index.html").write_text("fixture index", encoding="utf-8")
        (cls.served / "app.js").write_text("fixture script", encoding="utf-8")
        (cls.served / "textures").mkdir()
        (cls.served / "textures" / "test.png").write_bytes(b"fixture image")
        cls.outside = cls.base / "outside.txt"
        cls.outside.write_text("must never be served", encoding="utf-8")
        cls.old_root = DEVSTUDIO.DEVTOOLS
        DEVSTUDIO.DEVTOOLS = cls.served
        cls.server = DEVSTUDIO.ThreadingHTTPServer(("127.0.0.1", 0), DEVSTUDIO.Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=5)
        DEVSTUDIO.DEVTOOLS = cls.old_root
        cls.scratch.cleanup()

    def request(self, path):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=5)
        try:
            connection.request("GET", path)
            response = connection.getresponse()
            return response.status, response.read(), response.getheader("Content-Type")
        finally:
            connection.close()

    def test_regular_files_still_work(self):
        for path, expected in (("/", b"fixture index"), ("/app.js", b"fixture script"),
                               ("/static/app.js", b"fixture script"), ("/textures/test.png", b"fixture image")):
            with self.subTest(path=path):
                status, body, _ = self.request(path)
                self.assertEqual(status, 200)
                self.assertEqual(body, expected)

    def test_parent_traversal_is_forbidden(self):
        for path in ("/static/../outside.txt", "/static/%2e%2e/outside.txt", "/textures/../../outside.txt"):
            with self.subTest(path=path):
                status, body, _ = self.request(path)
                self.assertEqual(status, 403)
                self.assertNotIn(b"must never be served", body)

    def test_absolute_paths_are_forbidden(self):
        status, body, _ = self.request("/static/" + self.outside.as_posix())
        self.assertEqual(status, 403)
        self.assertNotIn(b"must never be served", body)

    def test_windows_separator_traversal_is_forbidden(self):
        if not self.served.drive:
            self.skipTest("Windows path separator semantics")
        self.assertEqual(self.request("/static/..%5coutside.txt")[0], 403)

    def test_symlink_target_is_checked(self):
        link = self.served / "outside-link.txt"
        try:
            link.symlink_to(self.outside)
        except OSError as error:
            self.skipTest("Symlink creation unavailable: " + str(error))
        status, body, _ = self.request("/static/outside-link.txt")
        self.assertEqual(status, 403)
        self.assertNotIn(b"must never be served", body)

    def test_missing_file_and_directory_return_not_found(self):
        self.assertEqual(self.request("/static/missing.txt")[0], 404)
        self.assertEqual(self.request("/static/textures")[0], 404)

    def test_invalid_path_is_rejected_without_killing_handler(self):
        self.assertIn(self.request("/static/%00.txt")[0], (403, 404))
        self.assertEqual(self.request("/app.js")[0], 200)


if __name__ == "__main__":
    unittest.main(verbosity=2)
