# Bộ điều khiển suy giảm của cổng API (DV-01-02; TL-04 "Hành vi khi bão hoà", OPS-14).
# Nhận webhook Alertmanager (cảnh báo mang nhãn degrade_level 1..3) và lệnh bật, tắt bằng tay của người trực,
# rồi ghi vào mọi ApisixPluginConfig có nhãn vt.io/degrade-managed=true: nhãn vt.io/degrade-level và bộ plugin
# của lớp ưu tiên ở mức đó, dựng sẵn bởi Helm trong ConfigMap apisix-degrade-levels (tệp p<lớp>-<mức>.json).
# Mức áp dụng = mức đặt tay nếu có, ngược lại max(mức nền trong Git, mức của các cảnh báo đang cháy).
# Chỉ dùng thư viện chuẩn Python; trạng thái cảnh báo giữ trong bộ nhớ, Alertmanager gửi lại theo repeat_interval.
import hmac
import json
import os
import ssl
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SA_DIR = "/var/run/secrets/kubernetes.io/serviceaccount"
GROUP = "apis/apisix.apache.org/v2"
SELECTOR = "vt.io/degrade-managed=true"
LEVEL_LABEL = "vt.io/degrade-level"
CLASS_LABEL = "vt.io/priority-class"


def log(event, **fields):
    # Nhật ký vận hành dạng JSON một dòng; không ghi token hay thân request.
    fields.update(event=event, ts=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()))
    print(json.dumps(fields, ensure_ascii=False), flush=True)


class Kube:
    def __init__(self, api, token_file, ca_file):
        self.api = api.rstrip("/")
        self.token_file = token_file
        self.ctx = None
        if api.startswith("https://"):
            self.ctx = ssl.create_default_context(cafile=ca_file if ca_file and os.path.exists(ca_file) else None)

    def call(self, method, path, body=None, content_type="application/json"):
        headers = {"Accept": "application/json"}
        if self.token_file and os.path.exists(self.token_file):
            with open(self.token_file, encoding="utf-8") as f:
                headers["Authorization"] = "Bearer " + f.read().strip()
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = content_type
        req = urllib.request.Request(self.api + path, data=data, method=method, headers=headers)
        with urllib.request.urlopen(req, timeout=10, context=self.ctx) as r:
            return json.loads(r.read() or b"{}")

    def plugin_configs(self):
        q = urllib.parse.urlencode({"labelSelector": SELECTOR})
        return self.call("GET", f"/{GROUP}/apisixpluginconfigs?{q}").get("items", [])

    def patch(self, ns, name, body):
        path = f"/{GROUP}/namespaces/{ns}/apisixpluginconfigs/{name}"
        return self.call("PATCH", path, body, "application/merge-patch+json")


class Controller:
    def __init__(self, kube, levels_dir, base_level):
        self.kube = kube
        self.levels_dir = levels_dir
        self.base = base_level
        self.firing = {}  # fingerprint → mức
        self.manual = None
        self.applied = None
        self.lock = threading.Lock()
        self.transitions = 0
        self.errors = 0

    def target(self):
        if self.manual is not None:
            return self.manual
        return max([self.base, *self.firing.values()])

    def variant(self, cls, level):
        with open(os.path.join(self.levels_dir, f"p{cls}-{level}.json"), encoding="utf-8") as f:
            return json.load(f)

    def on_alerts(self, payload):
        with self.lock:
            for a in payload.get("alerts", []):
                raw = (a.get("labels") or {}).get("degrade_level")
                key = a.get("fingerprint") or json.dumps(a.get("labels") or {}, sort_keys=True)
                if raw is None:
                    continue
                level = parse_level(raw)
                if a.get("status") == "firing" and level is not None:
                    self.firing[key] = level
                else:
                    self.firing.pop(key, None)
            return self.reconcile("alertmanager", payload.get("groupKey", ""))

    def on_manual(self, level, reason):
        with self.lock:
            self.manual = level
            return self.reconcile("manual", reason)

    def reconcile(self, source, reason=""):
        level = self.target()
        items = self.kube.plugin_configs()
        for it in items:
            md = it["metadata"]
            cls = (md.get("labels") or {}).get(CLASS_LABEL, "2")
            body = {"metadata": {"labels": {LEVEL_LABEL: str(level)}}, "spec": {"plugins": self.variant(cls, level)}}
            self.kube.patch(md["namespace"], md["name"], body)
        if level != self.applied:
            log("degrade_level_changed", previous=self.applied, level=level, source=source, reason=reason,
                plugin_configs=len(items))
            if self.applied is not None:
                self.transitions += 1
            self.applied = level
        return level

    def state(self):
        return {"level": self.applied, "target": self.target(), "base": self.base, "manual": self.manual,
                "firing": sorted(set(self.firing.values()))}


def parse_level(raw):
    try:
        v = int(raw)
    except (TypeError, ValueError):
        return None
    return v if 0 <= v <= 3 else None


def make_handler(ctl, token):
    class Handler(BaseHTTPRequestHandler):
        def authorized(self):
            got = self.headers.get("Authorization", "")
            return bool(token) and hmac.compare_digest(got.encode(), ("Bearer " + token).encode())

        def reply(self, code, obj, ctype="application/json"):
            body = obj if isinstance(obj, bytes) else json.dumps(obj, ensure_ascii=False).encode()
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path == "/healthz":
                return self.reply(200, {"status": "UP"})
            if self.path == "/level":
                return self.reply(200, ctl.state())
            if self.path == "/metrics":
                m = (f"# TYPE vt_degrade_level gauge\nvt_degrade_level {ctl.applied if ctl.applied is not None else 0}\n"
                     f"# TYPE vt_degrade_transitions_total counter\nvt_degrade_transitions_total {ctl.transitions}\n"
                     f"# TYPE vt_degrade_errors_total counter\nvt_degrade_errors_total {ctl.errors}\n")
                return self.reply(200, m.encode(), "text/plain; version=0.0.4")
            return self.reply(404, {"title": "Không có tài nguyên"})

        def do_POST(self):
            if self.path not in ("/alertmanager", "/level"):
                return self.reply(404, {"title": "Không có tài nguyên"})
            if not self.authorized():
                return self.reply(401, {"title": "Thiếu hoặc sai token"})
            try:
                n = int(self.headers.get("Content-Length") or 0)
                payload = json.loads(self.rfile.read(min(n, 1 << 20)) or b"{}")
            except ValueError:
                return self.reply(400, {"title": "Thân không phải JSON"})
            try:
                if self.path == "/alertmanager":
                    level = ctl.on_alerts(payload)
                else:
                    raw = payload.get("level")
                    level = None if raw is None else parse_level(raw)
                    if raw is not None and level is None:
                        return self.reply(422, {"title": "Mức suy giảm chỉ nhận 0, 1, 2, 3 hoặc null"})
                    level = ctl.on_manual(level, str(payload.get("reason", ""))[:200])
            except (urllib.error.URLError, OSError, KeyError) as e:
                ctl.errors += 1
                log("degrade_apply_failed", error=type(e).__name__)
                return self.reply(502, {"title": "Không ghi được cấu hình cổng"})
            return self.reply(200, {"level": level})

        def log_message(self, *args):
            pass

    return Handler


def periodic(ctl, interval):
    # Ghi lại mức hiện hành định kỳ: namespace mới, hoặc cấu hình bị đồng bộ GitOps ghi đè.
    while True:
        time.sleep(interval)
        try:
            with ctl.lock:
                ctl.reconcile("periodic")
        except (urllib.error.URLError, OSError, KeyError) as e:
            ctl.errors += 1
            log("degrade_apply_failed", error=type(e).__name__)


def main():
    env = os.environ.get
    kube = Kube(env("K8S_API", "https://kubernetes.default.svc"), env("K8S_TOKEN_FILE", SA_DIR + "/token"),
                env("K8S_CA_FILE", SA_DIR + "/ca.crt"))
    base = parse_level(env("BASE_LEVEL", "0")) or 0
    ctl = Controller(kube, env("LEVELS_DIR", "/etc/vt-degrade"), base)
    token = ""
    tf = env("WEBHOOK_TOKEN_FILE", "/var/run/vt-degrade/token")
    if os.path.exists(tf):
        with open(tf, encoding="utf-8") as f:
            token = f.read().strip()
    if not token:
        log("degrade_webhook_token_missing")
    interval = int(env("RECONCILE_SECONDS", "60"))
    if interval > 0:
        threading.Thread(target=periodic, args=(ctl, interval), daemon=True).start()
    port = int(env("PORT", "8080"))
    log("degrade_controller_started", port=port, base=base)
    ThreadingHTTPServer(("", port), make_handler(ctl, token)).serve_forever()


if __name__ == "__main__":
    sys.exit(main())
