# Kiểm thử bộ điều khiển suy giảm (files/degrade-controller.py, DV-01-02) với Kubernetes API giả lập:
# webhook Alertmanager nâng và hạ mức, mức đặt tay thắng cảnh báo, token sai bị 401, bộ plugin đúng lớp được ghi
# vào ApisixPluginConfig cùng nhãn vt.io/degrade-level. Bộ plugin lấy từ ConfigMap do chính chart dựng.
# Chạy: python3 tests/kiem-thu-dieu-khien-suy-giam.py (cần helm, PyYAML).
import importlib.util, json, os, subprocess, sys, tempfile, threading, unittest, urllib.error, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import yaml

sys.dont_write_bytecode = True
CHART = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
spec = importlib.util.spec_from_file_location("degrade_controller", os.path.join(CHART, "files/degrade-controller.py"))
dc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dc)


class FakeKube(BaseHTTPRequestHandler):
    store = {}  # (ns, name) → object
    patches = []

    def do_GET(self):
        assert "labelSelector=vt.io%2Fdegrade-managed%3Dtrue" in self.path, self.path
        body = json.dumps({"items": list(self.store.values())}).encode()
        self.send_response(200); self.send_header("Content-Length", str(len(body))); self.end_headers()
        self.wfile.write(body)

    def do_PATCH(self):
        assert self.headers["Content-Type"] == "application/merge-patch+json"
        parts = self.path.split("/")
        ns, name = parts[parts.index("namespaces") + 1], parts[-1]
        patch = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        obj = self.store[(ns, name)]
        obj["metadata"]["labels"].update(patch["metadata"]["labels"])
        obj["spec"] = patch["spec"]
        self.patches.append((ns, name))
        body = json.dumps(obj).encode()
        self.send_response(200); self.send_header("Content-Length", str(len(body))); self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


def render_levels(tmp):
    out = subprocess.run(["helm", "template", "g", CHART, "-f", os.path.join(CHART, "tests/values-ci.yaml")],
                         check=True, capture_output=True, text=True).stdout
    for doc in yaml.safe_load_all(out):
        if doc and doc["kind"] == "ConfigMap" and doc["metadata"]["name"] == "apisix-degrade-levels":
            for k, v in doc["data"].items():
                open(os.path.join(tmp, k), "w").write(v)
            return sorted(doc["data"])
    raise AssertionError("không có ConfigMap apisix-degrade-levels")


class DegradeControllerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp()
        cls.keys = render_levels(cls.tmp)
        cls.kube = ThreadingHTTPServer(("127.0.0.1", 0), FakeKube)
        threading.Thread(target=cls.kube.serve_forever, daemon=True).start()
        ctl = dc.Controller(dc.Kube(f"http://127.0.0.1:{cls.kube.server_port}", None, None), cls.tmp, 0)
        cls.ctl = ctl
        cls.web = ThreadingHTTPServer(("127.0.0.1", 0), dc.make_handler(ctl, "bi-mat-thu"))
        threading.Thread(target=cls.web.serve_forever, daemon=True).start()
        cls.url = f"http://127.0.0.1:{cls.web.server_port}"

    def setUp(self):
        FakeKube.store.clear()
        for ns in ("core", "secure"):
            for cls_, name in (("1", "protected-api-p1"), ("2", "protected-api"), ("3", "protected-api-p3")):
                FakeKube.store[(ns, name)] = {"metadata": {"namespace": ns, "name": name, "labels": {
                    "vt.io/degrade-managed": "true", "vt.io/priority-class": cls_, "vt.io/degrade-level": "0"}},
                    "spec": {"plugins": []}}
        FakeKube.patches.clear()
        self.ctl.firing.clear(); self.ctl.manual = None

    def post(self, path, body, token="bi-mat-thu"):
        req = urllib.request.Request(self.url + path, data=json.dumps(body).encode(), method="POST",
                                     headers={"Content-Type": "application/json", "Authorization": "Bearer " + token})
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read())

    def level_of(self, ns, name):
        o = FakeKube.store[(ns, name)]
        return o["metadata"]["labels"]["vt.io/degrade-level"], {p["name"]: p for p in o["spec"]["plugins"]}

    def test_variants_cover_every_class_and_level(self):
        self.assertEqual(self.keys, sorted(f"p{c}-{l}.json" for c in "123" for l in range(4)))

    def test_alert_raises_and_resolve_lowers(self):
        s, b = self.post("/alertmanager", {"groupKey": "g", "alerts": [
            {"status": "firing", "fingerprint": "a", "labels": {"alertname": "DbCpu", "degrade_level": "3"}}]})
        self.assertEqual((s, b["level"]), (200, 3))
        lv, plugins = self.level_of("core", "protected-api-p3")
        self.assertEqual(lv, "3")
        self.assertEqual(plugins["fault-injection"]["config"]["abort"]["headers"]["Retry-After"], "60")
        lv, plugins = self.level_of("secure", "protected-api-p1")
        self.assertEqual(lv, "3")
        # Lớp 1 vẫn qua ở mức 3; chỉ đường dẫn AI bị tắt (abort có điều kiện vars).
        self.assertIn("vars", plugins["fault-injection"]["config"]["abort"])
        self.assertEqual(len(FakeKube.patches), 6)
        s, b = self.post("/alertmanager", {"alerts": [
            {"status": "resolved", "fingerprint": "a", "labels": {"degrade_level": "3"}}]})
        self.assertEqual(b["level"], 0)
        lv, plugins = self.level_of("core", "protected-api-p3")
        self.assertEqual((lv, "fault-injection" in plugins), ("0", False))

    def test_highest_firing_level_wins_and_level2_cuts_tenant_rate(self):
        self.post("/alertmanager", {"alerts": [
            {"status": "firing", "fingerprint": "a", "labels": {"degrade_level": "1"}},
            {"status": "firing", "fingerprint": "b", "labels": {"degrade_level": "2"}}]})
        lv, plugins = self.level_of("core", "protected-api")
        self.assertEqual(lv, "2")
        self.assertEqual(plugins["limit-req"]["config"]["rate"], 280)
        self.assertEqual(plugins["proxy-rewrite"]["config"]["headers"]["set"]["X-Vt-Degrade-Level"], "2")

    def test_manual_overrides_alerts_until_cleared(self):
        self.post("/alertmanager", {"alerts": [{"status": "firing", "fingerprint": "a", "labels": {"degrade_level": "1"}}]})
        s, b = self.post("/level", {"level": 3, "reason": "OPS-14 diễn tập"})
        self.assertEqual(b["level"], 3)
        s, b = self.post("/level", {"level": None})
        self.assertEqual(b["level"], 1, "bỏ mức tay thì về mức của cảnh báo đang cháy")

    def test_rejects_bad_token_and_bad_level(self):
        self.assertEqual(self.post("/level", {"level": 3}, token="sai")[0], 401)
        self.assertEqual(self.post("/level", {"level": 7})[0], 422)
        self.assertEqual(FakeKube.patches, [])


if __name__ == "__main__":
    unittest.main(verbosity=2)
