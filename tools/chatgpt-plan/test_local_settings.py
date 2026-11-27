import http.client
from pathlib import Path
import re
import tempfile
import threading
import unittest
from unittest.mock import Mock
from urllib.parse import urlencode

from local_settings import settings_server


class LocalSettingsTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.plan = Mock()
        self.plan.ROOT = Path(directory.name)
        self.plan.PLAN_SCOPE = "chatgpt.tokens.use.direct"
        self.plan.load.return_value = {
            "email": "<script>bad</script>", "access_token": "secret-access",
            "refresh_token": "secret-refresh", "scopes": [self.plan.PLAN_SCOPE],
            "selected_model": "gpt-test"
        }
        self.plan.available_models.return_value = [
            {"slug": "gpt-test", "display_name": "GPT Test"}
        ]
        self.server = settings_server(self.plan, 0)
        self.origin = f"http://127.0.0.1:{self.server.server_port}"
        thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        thread.start()
        def stop():
            self.server.shutdown()
            self.server.server_close()
            thread.join(timeout=2)
        self.addCleanup(stop)

    def request(self, method, path="/", fields=None, origin=None, host=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=5)
        try:
            headers = {}
            if origin is not None:
                headers["Origin"] = origin
            if host is not None:
                headers["Host"] = host
            if fields is not None:
                headers["Content-Type"] = "application/x-www-form-urlencoded"
            conn.request(method, path, body=urlencode(fields) if fields is not None else None,
                         headers=headers)
            response = conn.getresponse()
            return response.status, response.read().decode("utf-8"), response.getheader("Cache-Control")
        finally:
            conn.close()

    def test_settings_escapes_identity_and_never_returns_tokens(self):
        status, body, cache = self.request("GET")
        self.assertEqual(200, status)
        self.assertEqual("no-store", cache)
        self.assertIn("&lt;script&gt;bad&lt;/script&gt;", body)
        self.assertNotIn("secret-access", body)
        self.assertNotIn("secret-refresh", body)
        self.assertNotIn('<select', body)
        self.assertIn('模型与思考档位菜单', body)

    def test_login_requires_same_origin_and_csrf(self):
        _, body, _ = self.request("GET")
        csrf = re.search(r'name="csrf" value="([^"]+)"', body).group(1)
        self.assertEqual(403, self.request("POST", "/login", {"csrf": csrf}, "https://example.org")[0])
        self.assertEqual(403, self.request("POST", "/login", {"csrf": "wrong"}, self.origin)[0])
        self.plan.sign_in.assert_not_called()

    def test_login_logout_and_dns_rebinding(self):
        self.assertEqual(403, self.request("GET", host="example.org")[0])
        _, body, _ = self.request("GET")
        csrf = re.search(r'name="csrf" value="([^"]+)"', body).group(1)
        self.assertEqual(200, self.request("POST", "/login", {"csrf": csrf}, self.origin)[0])
        self.plan.sign_in.assert_called_once_with()
        self.assertEqual(200, self.request("POST", "/logout", {"csrf": csrf}, self.origin)[0])
        self.plan.logout.assert_called_once_with()


if __name__ == "__main__":
    unittest.main()
