#!/usr/bin/env python3
"""Kiểm thử Warpgate của chart pam-integration (INF-27; SEC-42, SEC-43).

1. Bản render: namespace restricted, mặc định từ chối, ảnh theo digest, bí mật OIDC chỉ qua secretKeyRef, PVC cho
   CSDL và bản ghi phiên, NetworkPolicy vào chỉ từ nguonCIDRs, CSDL mở cho Warpgate.
2. Cầu nối: sự kiện kiểm toán Warpgate → syslog RFC 5424 SD-ID vtpam@32473 (thoát ký tự, cắt thời điểm 6 chữ số),
   nhật ký vận hành bị bỏ; init cau-hinh chèn client_secret an toàn.
3. Chạy thật khi có WARPGATE_BIN (tệp chạy Warpgate 0.30.0 rút từ ảnh) và OTELCOL (otelcol-contrib):
   "warpgate check" trên cấu hình render (không có khoá lạ); "warpgate run" rồi nhập vai trò, kho bí mật, mục tiêu
   của ConfigMap warpgate-nhap qua API quản trị; sự kiện kiểm toán thật đi qua cầu nối, mTLS, receiver syslog/pam và
   transform/ecs-pam của otel-siem (chart otel-collector) ra ECS. Thiếu thì phần 3 tự bỏ qua.
"""
import importlib.util
import json
import os
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.request

import yaml

CHART = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
OTEL_CHART = os.path.abspath(os.path.join(CHART, "..", "otel-collector"))
HELM = os.environ.get("HELM") or shutil.which("helm") or "/root/bin/helm"
WARPGATE = os.environ.get("WARPGATE_BIN")
OTELCOL = os.environ.get("OTELCOL")
sys.dont_write_bytecode = True

spec = importlib.util.spec_from_file_location("warpgate_pam", os.path.join(CHART, "files", "warpgate", "warpgate_pam.py"))
wp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(wp)


def docs(chart, *args):
    r = subprocess.run([HELM, "template", "t", chart, *args], capture_output=True, text=True)
    if r.returncode != 0:
        raise AssertionError(r.stderr)
    return [d for d in yaml.safe_load_all(r.stdout) if d]


def tim(ds, kind, name=None, ns=None):
    return [d for d in ds if d["kind"] == kind and (name is None or d["metadata"]["name"] == name)
            and (ns is None or d["metadata"].get("namespace") == ns)]


D = docs(CHART)


def cm(ten):
    return tim(D, "ConfigMap", ten, "pam")[0]["data"]


class Render(unittest.TestCase):
    def test_namespace_va_mang(self):
        ns = tim(D, "Namespace", "pam")[0]["metadata"]["labels"]
        self.assertEqual(ns["pod-security.kubernetes.io/enforce"], "restricted")
        self.assertTrue(tim(D, "NetworkPolicy", "default-deny-all", "pam"))
        np = tim(D, "NetworkPolicy", "warpgate", "pam")[0]["spec"]
        # Không khai nguonCIDRs thì người không vào được; chỉ CronJob warpgate-lam-moi gọi API quản trị (cổng HTTPS).
        self.assertEqual(np["ingress"], [{"from": [{"podSelector": {"matchLabels": {"app.kubernetes.io/name": "warpgate-lam-moi"}}}],
                                          "ports": [{"protocol": "TCP", "port": 8888}]}])
        ra = json.dumps(np["egress"])
        self.assertIn('"port": 6514', ra)
        self.assertIn("otel-siem", ra)
        d = docs(CHART, "--set-json", 'warpgate.nguonCIDRs=["198.51.100.0/28"]')
        vao = tim(d, "NetworkPolicy", "warpgate", "pam")[0]["spec"]["ingress"][0]
        self.assertEqual(vao["from"], [{"ipBlock": {"cidr": "198.51.100.0/28"}}])
        self.assertEqual(sorted(p["port"] for p in vao["ports"]), [2222, 8443, 8888, 55432])
        # CSDL chỉ mở cho Warpgate (pam-proxy-to-pg ở data-pg và secure-data).
        pg = tim(D, "NetworkPolicy", "pam-proxy-to-pg")
        self.assertEqual(len(pg), 2)
        self.assertIn("warpgate", json.dumps(pg[0]["spec"]["ingress"]))
        # otel-siem nhận syslog từ pod Warpgate.
        siem = tim(docs(OTEL_CHART), "NetworkPolicy", "otel-siem", "observability")[0]["spec"]["ingress"]
        self.assertTrue(any('"pam"' in json.dumps(r) and "warpgate" in json.dumps(r) and '"port": 6514' in json.dumps(r) for r in siem))

    def test_statefulset_restricted(self):
        sts = tim(D, "StatefulSet", "warpgate", "pam")[0]
        p = sts["spec"]["template"]["spec"]
        self.assertTrue(p["securityContext"]["runAsNonRoot"])
        self.assertFalse(p["automountServiceAccountToken"])
        for c in p["initContainers"] + p["containers"]:
            sc = c["securityContext"]
            self.assertTrue(sc["readOnlyRootFilesystem"] and not sc["allowPrivilegeEscalation"], c["name"])
            self.assertEqual(sc["capabilities"]["drop"], ["ALL"])
            self.assertRegex(c["image"], r"^harbor\.vt\.local/.+@sha256:[a-f0-9]{64}$")
            self.assertIn("limits", c["resources"])
        wg = next(c for c in p["containers"] if c["name"] == "warpgate")
        self.assertIn("harbor.vt.local/ghcr/warp-tech/warpgate@sha256:8d0899c4", wg["image"])
        self.assertEqual(sts["metadata"]["labels"]["app.kubernetes.io/version"], "0.30.0")
        self.assertEqual(sts["spec"]["volumeClaimTemplates"][0]["metadata"]["name"], "du-lieu")
        self.assertIn({"name": "du-lieu", "mountPath": "/data"}, wg["volumeMounts"])
        # Bí mật OIDC chỉ qua secretKeyRef, không có trong ConfigMap.
        env = p["initContainers"][0]["env"][0]
        self.assertEqual(env["valueFrom"]["secretKeyRef"], {"name": "warpgate-oidc", "key": "client-secret"})
        mau = cm("warpgate-cau-hinh")["warpgate.yaml.mau"]
        self.assertEqual(mau.count('"__OIDC_CLIENT_SECRET__"'), 1)
        c = yaml.safe_load(mau)
        self.assertEqual(c["sso_providers"][0]["provider"]["issuer_url"], "https://sso-ops.vt.local/realms/platform-ops")
        self.assertEqual(c["log"]["send_to"], "/run/warpgate-log/log.sock")
        self.assertTrue(c["database_url"].startswith("sqlite:/data/"))

    def test_du_lieu_nhap_khong_co_bi_mat(self):
        d = cm("warpgate-nhap")
        mt = json.loads(d["muc-tieu.json"])
        self.assertEqual({m["target"]["options"]["kind"] for m in mt}, {"Kubernetes", "Ssh", "Postgres"})
        for m in mt:
            self.assertTrue(m["target"]["ticket_requests_disabled"])
            for k, v in _la(m["target"]["options"]):
                if k in ("token", "password", "private_key"):
                    self.assertTrue(v.startswith("secret://openbao-cell/kv/warpgate/"), (k, v))
            self.assertTrue(json.dumps(m).count(".vt.local") or "svc" in json.dumps(m))
        self.assertEqual(json.loads(d["vai-tro.json"]), ["pam-kubectl-debug-app", "pam-kubectl-operate-data",
                                                          "pam-kubectl-platform-admin", "pam-psql", "pam-ssh"])

    def test_tat_warpgate(self):
        d = docs(CHART, "--set", "warpgate.enabled=false")
        self.assertFalse(tim(d, "StatefulSet"))
        self.assertFalse(tim(d, "NetworkPolicy", "pam-proxy-to-pg"))

    def test_tu_choi_anh_khong_digest(self):
        r = subprocess.run([HELM, "template", "t", CHART, "--set", "warpgate.image.digest=0.30.0"], capture_output=True, text=True)
        self.assertNotEqual(r.returncode, 0)


def _la(o, k=None):
    if isinstance(o, dict):
        for kk, v in o.items():
            yield from _la(v, kk)
    elif isinstance(o, list):
        for v in o:
            yield from _la(v, k)
    else:
        yield k, o


class CauNoi(unittest.TestCase):
    def test_phien_bat_dau(self):
        d = wp.sang_syslog({"target": "audit", "_type": "TargetSessionStarted1", "session_id": "s-1", "target_name": "psql-pg-core",
                            "user_id": "u-1", "username": "nguoi.van.hanh", "message": "Session started",
                            "timestamp": "2026-10-23T01:02:03.123456789Z"}, host="warpgate-0")
        self.assertTrue(d.startswith("<86>1 2026-10-23T01:02:03.123456Z warpgate-0 warpgate - pam.phien-bat-dau [vtpam@32473 "), d)
        self.assertIn('event_action="pam.phien-bat-dau"', d)
        self.assertIn('session_id="s-1"', d)
        self.assertIn('target="psql-pg-core"', d)
        self.assertIn('recording_uri="https://warpgate.vt.local/@warpgate/admin#/sessions/s-1"', d)
        self.assertTrue(d.endswith("] Session started"))

    def test_thoat_ky_tu_va_xuong_dong(self):
        d = wp.sang_syslog({"target": "audit", "_type": "KubernetesExecStarted1", "username": "a", "namespace": "core",
                            "pod": "p", "command": '["sh","-c","echo ]\\"x\\n"]', "message": "Exec\nstarted"})
        self.assertIn('command="[\\"sh\\",\\"-c\\",\\"echo \\]\\\\\\"x\\\\n\\"\\]"', d)
        self.assertNotIn("\n", d)
        self.assertTrue(d.startswith("<84>1 "))

    def test_bo_nhat_ky_van_hanh_va_loai_la(self):
        self.assertIsNone(wp.sang_syslog({"target": "warpgate::listener_supervisor", "message": "Binding listener"}))
        d = wp.sang_syslog({"target": "audit", "_type": "LoaiMoi9", "message": "m"})
        self.assertIn('event_action="pam.khac"', d)
        self.assertIn('warpgate_type="LoaiMoi9"', d)

    def test_cap_thu_hoi_vai_tro(self):
        for loai, hd in (("AccessRoleGranted1", "pam.cap-quyen"), ("AccessRoleRevoked1", "pam.thu-hoi")):
            d = wp.sang_syslog({"target": "audit", "_type": loai, "grantee_username": "b", "role_name": "pam-psql", "message": "m"})
            self.assertIn(f'event_action="{hd}"', d)
            self.assertIn('user="b"', d)
            self.assertIn('role="pam-psql"', d)

    def test_cau_hinh_chen_bi_mat(self):
        tmp = tempfile.mkdtemp()
        try:
            mau, dich = os.path.join(tmp, "m"), os.path.join(tmp, "d")
            with open(mau, "w") as f:
                f.write('a: 1\nclient_secret: "__OIDC_CLIENT_SECRET__"\n')
            r = subprocess.run([sys.executable, "-I", wp.__file__, "cau-hinh", mau, dich], env={"OIDC_CLIENT_SECRET": 'x"y: #z\\'},
                               capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            with open(dich) as f:
                self.assertEqual(yaml.safe_load(f)["client_secret"], 'x"y: #z\\')
            self.assertEqual(os.stat(dich).st_mode & 0o777, 0o600)
            r = subprocess.run([sys.executable, "-I", wp.__file__, "cau-hinh", mau, dich], env={}, capture_output=True, text=True)
            self.assertNotEqual(r.returncode, 0)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)


def cong_trong():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@unittest.skipUnless(WARPGATE and OTELCOL and os.path.exists(WARPGATE or "") and os.path.exists(OTELCOL or ""),
                     "không có WARPGATE_BIN (Warpgate 0.30.0) và OTELCOL (otelcol-contrib)")
class ChayThat(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix="wg-")
        cls.teps, cls.p = [], []
        # Dọn cả khi setUpClass lỗi giữa chừng (tearDownClass không chạy trong trường hợp đó).
        cls.addClassCleanup(cls.don_dep)
        t = cls.tmp
        for d in ("data", "tls", "run", "log"):
            os.makedirs(os.path.join(t, d))
        subprocess.run(["openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-nodes",
                        "-keyout", f"{t}/tls/tls.key", "-out", f"{t}/tls/tls.crt", "-subj", "/CN=thu", "-days", "1",
                        "-addext", "subjectAltName=DNS:localhost,DNS:otel-siem.observability.svc"], check=True, capture_output=True)
        shutil.copy(f"{t}/tls/tls.crt", f"{t}/tls/ca.crt")
        cls.cong = {k: cong_trong() for k in ("http", "ssh", "kubernetes", "postgres", "siem")}
        mau = cm("warpgate-cau-hinh")["warpgate.yaml.mau"]
        for k, c in (("http", 8888), ("ssh", 2222), ("kubernetes", 8443), ("postgres", 55432)):
            mau = mau.replace(f"0.0.0.0:{c}", f"127.0.0.1:{cls.cong[k]}")
        mau = mau.replace("sqlite:/data/", f"sqlite:{t}/data/").replace(" /data/", f" {t}/data/") \
                 .replace("/tls/", f"{t}/tls/").replace("/run/warpgate-log/", f"{t}/log/")
        with open(f"{t}/mau.yaml", "w") as f:
            f.write(mau)
        subprocess.run([sys.executable, "-I", wp.__file__, "cau-hinh", f"{t}/mau.yaml", f"{t}/run/warpgate.yaml"],
                       env={"OIDC_CLIENT_SECRET": "khong-dung"}, check=True)
        # otel-siem rút gọn: đúng receiver syslog/pam (mTLS) và bộ xử lý ECS của chart otel-collector.
        c = next(yaml.safe_load(d["data"]["config.yaml"]) for d in tim(docs(OTEL_CHART), "ConfigMap", "otel-siem"))
        r = c["receivers"]["syslog/pam"]
        r["tcp"]["listen_address"] = f"127.0.0.1:{cls.cong['siem']}"
        r["tcp"]["tls"].update(cert_file=f"{t}/tls/tls.crt", key_file=f"{t}/tls/tls.key", client_ca_file=f"{t}/tls/ca.crt")
        xl = ["transform/dau", "transform/ecs-pam", "transform/cuoi"]
        yaml.safe_dump({"receivers": {"syslog/pam": r}, "processors": {k: c["processors"][k] for k in xl},
                        "exporters": {"file": {"path": f"{t}/siem.json"}},
                        "service": {"telemetry": {"metrics": {"level": "none"}},
                                    "pipelines": {"logs": {"receivers": ["syslog/pam"], "processors": xl, "exporters": ["file"]}}}},
                       cls.tep("otel.yaml"))
        cls.teps[-1].close()
        cls.p.append(subprocess.Popen([OTELCOL, f"--config={t}/otel.yaml"], stdout=subprocess.DEVNULL, stderr=cls.tep("otel.log")))
        env = {"SOCKET": f"{t}/log/log.sock", "SIEM_HOST": "127.0.0.1", "SIEM_PORT": str(cls.cong["siem"]),
               "SIEM_SERVER_NAME": "otel-siem.observability.svc", "TLS_CA": f"{t}/tls/ca.crt", "TLS_CRT": f"{t}/tls/tls.crt",
               "TLS_KEY": f"{t}/tls/tls.key", "HOSTNAME_PAM": "warpgate-0"}
        cls.p.append(subprocess.Popen([sys.executable, "-I", wp.__file__, "cau-noi"], env=env, stdout=subprocess.DEVNULL,
                                      stderr=cls.tep("cau-noi.log")))
        han = time.time() + 10
        while not os.path.exists(f"{t}/log/log.sock") and time.time() < han:
            time.sleep(0.1)
        cls.check = subprocess.run([WARPGATE, "--config", f"{t}/run/warpgate.yaml", "check"], capture_output=True, text=True)
        cls.token = "thu-" + os.urandom(8).hex()
        cls.p.append(subprocess.Popen([WARPGATE, "--config", f"{t}/run/warpgate.yaml", "run", "--enable-admin-token"],
                                      env={"WARPGATE_ADMIN_TOKEN": cls.token}, stdout=cls.tep("wg.log"), stderr=subprocess.STDOUT))
        cls.ctx = ssl.create_default_context(cafile=f"{t}/tls/ca.crt")
        cls.ctx.check_hostname = False
        han = time.time() + 30
        while time.time() < han:
            try:
                cls.goi("GET", "/@warpgate/api/info")
                break
            except OSError:
                time.sleep(0.5)

    @classmethod
    def tep(cls, ten):
        f = open(os.path.join(cls.tmp, ten), "w")
        cls.teps.append(f)
        return f

    @classmethod
    def goi(cls, method, path, body=None, admin=False):
        rq = urllib.request.Request(f"https://127.0.0.1:{cls.cong['http']}{path}", method=method,
                                    data=None if body is None else json.dumps(body).encode(),
                                    headers={"Content-Type": "application/json", **({"X-Warpgate-Token": cls.token} if admin else {})})
        try:
            with urllib.request.urlopen(rq, context=cls.ctx, timeout=10) as r:
                return r.status, json.loads(r.read() or b"null")
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode()

    @classmethod
    def don_dep(cls):
        for p in cls.p:
            p.terminate()
        for p in cls.p:
            try:
                p.wait(timeout=10)
            except subprocess.TimeoutExpired:
                p.kill()
        for f in cls.teps:
            f.close()
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def test_1_check_khong_khoa_la(self):
        self.assertEqual(self.check.returncode, 0, self.check.stdout + self.check.stderr)
        self.assertIn("No problems found", self.check.stdout + self.check.stderr)
        self.assertNotIn("Ignoring unknown config key", self.check.stdout + self.check.stderr)

    def test_2_nhap_va_su_kien_toi_siem(self):
        d = cm("warpgate-nhap")
        api = "/@warpgate/admin/api"
        vai_tro = {}
        for ten in json.loads(d["vai-tro.json"]):
            st, r = self.goi("POST", f"{api}/roles", {"name": ten}, admin=True)
            self.assertEqual(st, 201, r)
            vai_tro[ten] = r["id"]
        st, r = self.goi("POST", f"{api}/secret-backends", json.loads(d["kho-bi-mat.json"]), admin=True)
        self.assertEqual(st, 201, r)
        for m in json.loads(d["muc-tieu.json"]):
            st, r = self.goi("POST", f"{api}/targets", m["target"], admin=True)
            self.assertEqual(st, 201, (m["target"]["name"], r))
            for ten in m["roles"]:
                st, r2 = self.goi("POST", f"{api}/targets/{r['id']}/roles/{vai_tro[ten]}", admin=True)
                self.assertIn(st, (200, 201), r2)
        st, nd = self.goi("POST", f"{api}/users", {"username": "nguoi.van.hanh", "description": ""}, admin=True)
        self.assertEqual(st, 201, nd)
        # Broker cấp vai trò Warpgate có hạn (expires_at = vt.io/het-han, ≤ 4 giờ) song song với binding có thời hạn.
        het = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() + 4 * 3600))
        st, _ = self.goi("POST", f"{api}/users/{nd['id']}/roles/{vai_tro['pam-psql']}", {"expires_at": het}, admin=True)
        self.assertIn(st, (200, 201))
        st, _ = self.goi("DELETE", f"{api}/users/{nd['id']}/roles/{vai_tro['pam-psql']}", admin=True)
        self.assertIn(st, (200, 204))
        st, _ = self.goi("POST", "/@warpgate/api/auth/login", {"username": "khong-co", "password": "sai"})
        self.assertEqual(st, 401)
        han, kq = time.time() + 20, []
        while time.time() < han:
            kq = self.doc_siem()
            if {"pam.dang-nhap-that-bai", "pam.cap-quyen", "pam.thu-hoi"} <= {r.get("event.action") for r in kq}:
                break
            time.sleep(0.5)
        hd = {r.get("event.action"): r for r in kq}
        that_bai = hd.get("pam.dang-nhap-that-bai", {})
        self.assertEqual(that_bai.get("user.name"), "khong-co", kq)
        self.assertEqual(that_bai.get("event.outcome"), "failure")
        self.assertEqual(that_bai.get("event.category"), ["authentication"])
        self.assertEqual(that_bai.get("source.ip"), "127.0.0.1")
        self.assertEqual(that_bai.get("host.name"), "warpgate-0")
        self.assertEqual(that_bai.get("pam", {}).get("warpgate_type"), "UserAuthenticationFailed1")
        cap = hd.get("pam.cap-quyen", {})
        self.assertEqual((cap.get("user.name"), cap.get("user.target.name"), cap.get("event.type")),
                         ("nguoi.van.hanh", "pam-psql", ["change"]))
        self.assertEqual(hd.get("pam.thu-hoi", {}).get("event.type"), ["deletion"])
        self.assertTrue(all(r.get("event.module") == "pam" and "Z" in r.get("@timestamp", "") for r in kq))

    def test_3_lam_moi_doi_username_psql(self):
        """warpgate_lam_moi.py (OpenBao và API server giả) đổi username của mục tiêu psql-pg-core trên Warpgate thật qua
        PUT /targets/{id}, giữ nguyên các tuỳ chọn khác và tham chiếu secret:// của mật khẩu."""
        spec = importlib.util.spec_from_file_location("test_warpgate_viec", os.path.join(CHART, "tests", "test_warpgate_viec.py"))
        tv = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(tv)
        g = tv.MayChuGia()
        self.addCleanup(g.dong)
        env = tv.moi_truong(self.tmp, g.url)
        with open(env["WARPGATE_TOKEN_FILE"], "w") as f:
            f.write(self.token)
        env["WARPGATE_CA"] = f"{self.tmp}/tls/ca.crt"
        cfg = tv.cau_hinh_lam_moi(g.url)
        cfg["warpgate"] = f"https://localhost:{self.cong['http']}"
        st, truoc = self.goi("GET", "/@warpgate/admin/api/targets", admin=True)
        truoc = next(t for t in truoc if t["name"] == "psql-pg-core")
        x = tv.lm.LamMoi(cfg, env)
        with tv.contextlib.redirect_stdout(tv.io.StringIO()):
            x.chay("openbao-cell")
        self.assertEqual(x.loi, 0)
        st, sau = self.goi("GET", "/@warpgate/admin/api/targets", admin=True)
        sau = next(t for t in sau if t["name"] == "psql-pg-core")
        self.assertEqual(sau["options"]["username"], g.kv["warpgate/psql-pg-core"]["username"])
        self.assertEqual(sau["options"]["auth"], truoc["options"]["auth"])
        truoc["options"].pop("username")
        sau["options"].pop("username")
        self.assertEqual(sau["options"], truoc["options"])
        self.assertEqual(sau["ticket_requests_disabled"], truoc["ticket_requests_disabled"])

    def doc_siem(self):
        kq = []
        tep = f"{self.tmp}/siem.json"
        if not os.path.exists(tep):
            return kq
        with open(tep) as f:
            dong = f.readlines()
        for l in dong:
            for rl in json.loads(l)["resourceLogs"]:
                for sl in rl["scopeLogs"]:
                    for r in sl["logRecords"]:
                        kq.append(_gia_tri(r["body"]))
        return kq


def _gia_tri(v):
    if "kvlistValue" in v:
        return {x["key"]: _gia_tri(x["value"]) for x in v["kvlistValue"].get("values", [])}
    if "arrayValue" in v:
        return [_gia_tri(x) for x in v["arrayValue"].get("values", [])]
    return next(iter(v.values()), None)


if __name__ == "__main__":
    unittest.main()
