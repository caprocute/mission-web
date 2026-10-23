#!/usr/bin/env python3
"""Kiểm thử phần việc của Warpgate trong chart pam-integration (INF-27; SEC-29, SEC-42, SEC-58).

1. Bản render: ServiceAccount warpgate-<vai trò> không có binding, Role của CronJob warpgate-lam-moi chỉ tạo token cho
   đúng các tài khoản đó, hai CronJob restricted, bản ghi phiên gắn PVC chỉ đọc cùng node, khoá S3 gắn dạng tệp.
2. Chính sách nhập: biểu thức CEL của hai ValidatingAdmissionPolicy chạy bằng môi trường CEL của kube-apiserver 1.33
   (tests/vap-cel, đặt VAP_CEL=<tệp đã dựng>; thiếu thì bỏ qua).
3. Quét RBAC: binding PAM tới ServiceAccount của Warpgate hợp lệ; binding thường trực tới chúng là cửa sau.
4. warpgate_lam_moi.py với máy chủ giả (API server, OpenBao, Warpgate) và, khi có PostgreSQL ≥ 16, OpenBao giả chạy
   đúng câu tạo người dùng động dba_ops của cau-hinh-pam.sh trên PostgreSQL thật.
5. sao_chep_ghi_phien.py: chữ ký SigV4 theo ví dụ mẫu của tài liệu AWS, S3 giả kiểm Content-MD5 và chữ ký.
"""
import datetime as dt
import hashlib
import http.server
import importlib.util
import base64
import contextlib
import io
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.parse

import yaml

CHART = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
HELM = os.environ.get("HELM") or shutil.which("helm") or "/root/bin/helm"
VAP_CEL = os.environ.get("VAP_CEL")
sys.dont_write_bytecode = True


def nap(ten, tep):
    spec = importlib.util.spec_from_file_location(ten, os.path.join(CHART, "files", tep))
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


lm = nap("warpgate_lam_moi", "warpgate/warpgate_lam_moi.py")
sc = nap("sao_chep_ghi_phien", "warpgate/sao_chep_ghi_phien.py")
qr = nap("quet_rbac", "scripts/quet_rbac.py")


def docs(*args):
    r = subprocess.run([HELM, "template", "t", CHART, *args], capture_output=True, text=True)
    if r.returncode != 0:
        raise AssertionError(r.stderr)
    return [d for d in yaml.safe_load_all(r.stdout) if d]


def tim(ds, kind, name=None, ns=None):
    return [d for d in ds if d["kind"] == kind and (name is None or d["metadata"]["name"] == name)
            and (ns is None or d["metadata"].get("namespace") == ns)]


D = docs()
VAI_TRO = ["debug-app", "operate-data", "platform-admin"]
SA = [f"warpgate-{v}" for v in VAI_TRO]
BROKER = "oidc:service-account-pam-broker"


def cong_trong():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class Render(unittest.TestCase):
    def test_tai_khoan_may_khong_co_binding(self):
        for ten in SA:
            sa = tim(D, "ServiceAccount", ten, "pam")
            self.assertEqual(len(sa), 1, ten)
            self.assertFalse(sa[0]["automountServiceAccountToken"])
        self.assertFalse([s for s in tim(D, "Secret") if s.get("type") == "kubernetes.io/service-account-token"])
        for b in tim(D, "RoleBinding") + tim(D, "ClusterRoleBinding"):
            for s in b.get("subjects", []):
                self.assertNotIn(s["name"], SA, b["metadata"]["name"])

    def test_role_lam_moi_chi_tao_token(self):
        r = tim(D, "Role", "warpgate-lam-moi", "pam")[0]
        self.assertEqual(r["rules"], [{"apiGroups": [""], "resources": ["serviceaccounts/token"], "verbs": ["create"],
                                       "resourceNames": SA}])
        rb = tim(D, "RoleBinding", "warpgate-lam-moi", "pam")[0]
        self.assertEqual(rb["subjects"], [{"kind": "ServiceAccount", "name": "warpgate-lam-moi", "namespace": "pam"}])

    def test_muc_tieu_va_cau_hinh_lam_moi(self):
        nhap = tim(D, "ConfigMap", "warpgate-nhap", "pam")[0]["data"]
        mt = {m["target"]["name"]: m for m in json.loads(nhap["muc-tieu.json"])}
        for v in VAI_TRO:
            m = mt[f"kubectl-g4-{v}"]
            self.assertEqual(m["roles"], [f"pam-kubectl-{v}"])
            self.assertEqual(m["target"]["options"]["auth"],
                             {"kind": "Token", "token": f"secret://openbao-cell/kv/warpgate/kubectl-g4-{v}#token"})
        p = mt["psql-pg-core"]["target"]["options"]
        self.assertEqual((p["host"], p["username"], p["auth"]["password"]),
                         ("pg-core-rw.data-pg.svc", "chua-cap", "secret://openbao-cell/kv/warpgate/psql-pg-core#password"))
        self.assertEqual(json.loads(nhap["vai-tro.json"]),
                         ["pam-kubectl-debug-app", "pam-kubectl-operate-data", "pam-kubectl-platform-admin", "pam-psql", "pam-ssh"])
        # Nhóm Keycloak không ánh xạ thẳng vào vai trò kubectl: chỉ broker cấp, có hạn.
        mau = yaml.safe_load(tim(D, "ConfigMap", "warpgate-cau-hinh", "pam")[0]["data"]["warpgate.yaml.mau"])
        self.assertNotIn("pam-kubectl", json.dumps(mau))
        cfg = json.loads(tim(D, "ConfigMap", "warpgate-viec", "pam")[0]["data"]["lam-moi.json"])
        self.assertEqual([k["serviceAccount"] for k in cfg["kubectl"]], SA)
        self.assertEqual(cfg["psql"], [{"cum": "pg-core", "creds": "database/creds/pg-core-dba-ops", "kv": "warpgate/psql-pg-core",
                                        "mucTieu": "psql-pg-core"}])
        self.assertEqual((cfg["tokenTtlGiay"], cfg["nguongGiay"], cfg["openbao"]["role"]), (3600, 5400, "warpgate-lam-moi"))
        # Chính sách OpenBao của CronJob khớp đúng các khoá mục tiêu tham chiếu (đặc quyền tối thiểu).
        cs = subprocess.run(["bash", os.path.join(CHART, "files", "openbao", "cau-hinh-pam.sh"), "--in-chinh-sach-lam-moi"],
                            env={**os.environ, "CELL": "g4"}, capture_output=True, text=True, check=True).stdout
        duong = dict(re.findall(r'path "([^"]+)" \{ capabilities = (\[[^\]]*\]) \}', cs))
        mong = {"database/creds/pg-core-dba-ops": '["read"]', "kv/data/warpgate/psql-pg-core": '["create", "update", "read"]'}
        mong.update({f"kv/data/warpgate/kubectl-g4-{v}": '["create", "update"]' for v in VAI_TRO})
        self.assertEqual(duong, mong)
        ref = {f'kv/data/{m["target"]["options"]["auth"].get("token", m["target"]["options"]["auth"].get("password", ""))[len("secret://openbao-cell/kv/"):].split("#")[0]}'
               for m in mt.values() if m["target"]["options"]["kind"] in ("Kubernetes", "Postgres")}
        self.assertEqual(ref, {k for k in mong if k.startswith("kv/")})

    def test_cronjob_restricted(self):
        for ten in ("warpgate-lam-moi", "warpgate-ghi-phien"):
            cj = tim(D, "CronJob", ten, "pam")[0]
            self.assertEqual(cj["spec"]["concurrencyPolicy"], "Forbid")
            p = cj["spec"]["jobTemplate"]["spec"]["template"]["spec"]
            self.assertTrue(p["securityContext"]["runAsNonRoot"])
            for c in p["containers"]:
                self.assertTrue(c["securityContext"]["readOnlyRootFilesystem"])
                self.assertEqual(c["securityContext"]["capabilities"]["drop"], ["ALL"])
                self.assertIn("limits", c["resources"])
                self.assertFalse([e for e in c.get("env", []) if "valueFrom" in e], "bí mật chỉ gắn dạng tệp")
        p = tim(D, "CronJob", "warpgate-ghi-phien", "pam")[0]["spec"]["jobTemplate"]["spec"]["template"]["spec"]
        self.assertFalse(p["automountServiceAccountToken"])
        aff = p["affinity"]["podAffinity"]["requiredDuringSchedulingIgnoredDuringExecution"][0]
        self.assertEqual((aff["labelSelector"]["matchLabels"], aff["topologyKey"]),
                         ({"app.kubernetes.io/name": "warpgate"}, "kubernetes.io/hostname"))
        vol = {v["name"]: v for v in p["volumes"]}
        self.assertEqual(vol["du-lieu"]["persistentVolumeClaim"], {"claimName": "du-lieu-warpgate-0", "readOnly": True})
        self.assertEqual(vol["s3-khoa"]["secret"]["secretName"], "rgw-pam-ghi-phien")
        c = p["containers"][0]
        self.assertIn({"name": "du-lieu", "mountPath": "/data", "readOnly": True}, c["volumeMounts"])
        env = {e["name"]: e["value"] for e in c["env"]}
        self.assertEqual((env["BUCKET"], env["TIEN_TO"], env["THU_MUC"]), ("pam-cell-g4", "ghi-phien/g4", "/data/recordings"))
        p = tim(D, "CronJob", "warpgate-lam-moi", "pam")[0]["spec"]["jobTemplate"]["spec"]["template"]["spec"]
        tok = next(v for v in p["volumes"] if v["name"] == "openbao-token")["projected"]["sources"][0]["serviceAccountToken"]
        self.assertEqual(tok["audience"], "openbao")

    def test_mang(self):
        np = tim(D, "NetworkPolicy", "warpgate-ghi-phien", "pam")[0]["spec"]
        self.assertEqual(np["ingress"], [])
        self.assertNotIn("443", json.dumps(np["egress"]), "không khai egressTo thì không ra được RGW")
        d = docs("--set-json", 'warpgate.saoChepGhiPhien.egressTo=[{"ipBlock":{"cidr":"192.0.2.32/28"}}]')
        ra = tim(d, "NetworkPolicy", "warpgate-ghi-phien", "pam")[0]["spec"]["egress"][-1]
        self.assertEqual(ra, {"to": [{"ipBlock": {"cidr": "192.0.2.32/28"}}], "ports": [{"protocol": "TCP", "port": 443}]})
        wg = tim(D, "NetworkPolicy", "warpgate", "pam")[0]["spec"]["ingress"]
        self.assertEqual(wg, [{"from": [{"podSelector": {"matchLabels": {"app.kubernetes.io/name": "warpgate-lam-moi"}}}],
                               "ports": [{"protocol": "TCP", "port": 8888}]}])
        self.assertTrue(tim(D, "NetworkPolicy", "warpgate-viec-to-pushgateway-ingress", "observability"))

    def test_tat_sao_chep(self):
        d = docs("--set", "warpgate.saoChepGhiPhien.enabled=false")
        self.assertFalse(tim(d, "CronJob", "warpgate-ghi-phien"))
        self.assertTrue(tim(d, "CronJob", "warpgate-lam-moi"))
        d = docs("--set", "warpgate.enabled=false")
        self.assertFalse(tim(d, "ServiceAccount", "warpgate-debug-app"))
        self.assertFalse(tim(d, "ValidatingAdmissionPolicy", "vt-pam-tai-khoan-warpgate"))
        self.assertEqual(json.loads(tim(d, "ConfigMap", "pam-quet-rbac")[0]["data"]["cau-hinh.json"])["hopDong"]["taiKhoanMay"]["namespace"], "")


# ------------------------------------------------------------------ chính sách nhập (CEL thật của kube-apiserver)
NOW = dt.datetime(2026, 10, 23, 8, 0, tzinfo=dt.timezone.utc)


def ts(phut):
    return (NOW + dt.timedelta(minutes=phut)).strftime("%Y-%m-%dT%H:%M:%SZ")


def bd(kind, vai_tro, chu_the, ten="pam-yc-1", ns=None, duyet="a,b", het=240, yeu_cau="nam", nhan=True):
    md = {"name": ten, "labels": {"vt.io/cap-phat": "pam"} if nhan else {},
          "annotations": {"vt.io/pam-yeu-cau": "YC-1", "vt.io/nguoi-yeu-cau": yeu_cau, "vt.io/nguoi-duyet": duyet,
                          "vt.io/cap-luc": ts(0), "vt.io/het-han": ts(het)}}
    if ns:
        md["namespace"] = ns
    return {"kind": kind, "metadata": md, "roleRef": {"apiGroup": "rbac.authorization.k8s.io", "kind": "ClusterRole", "name": vai_tro},
            "subjects": chu_the}


def sa(ten, ns="pam"):
    return [{"kind": "ServiceAccount", "name": ten, "namespace": ns}]


@unittest.skipUnless(VAP_CEL and os.access(VAP_CEL, os.X_OK), "không có VAP_CEL (dựng tests/vap-cel); chính sách nhập chỉ kiểm tĩnh")
class ChinhSachNhap(unittest.TestCase):
    CS = {d["metadata"]["name"]: d["spec"] for d in tim(D, "ValidatingAdmissionPolicy")}

    def danh_gia(self, obj, nguoi=BROKER):
        ca = {"object": obj, "oldObject": None,
              "request": {"userInfo": {"username": nguoi, "groups": []}, "kind": {"kind": obj["kind"]}, "operation": "CREATE"}}
        loi = []
        for ten, cs in self.CS.items():
            r = subprocess.run([VAP_CEL], input=json.dumps({"policy": cs, "cases": [ca]}), capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            kq = json.loads(r.stdout.splitlines()[0])
            self.assertFalse(kq.get("errors"), (ten, kq))
            if kq["match"]:
                loi += kq["failed"]
        return loi

    def test_hai_chinh_sach_da_render(self):
        self.assertEqual(set(self.CS), {"vt-pam-binding-co-thoi-han", "vt-pam-tai-khoan-warpgate"})

    def test_nguoi_va_tai_khoan_warpgate_hop_le(self):
        self.assertEqual(self.danh_gia(bd("ClusterRoleBinding", "platform-admin", [{"kind": "User", "name": "oidc:nam"}])), [])
        self.assertEqual(self.danh_gia(bd("ClusterRoleBinding", "platform-admin", sa("warpgate-platform-admin"))), [])
        self.assertEqual(self.danh_gia(bd("RoleBinding", "debug-app", sa("warpgate-debug-app"), ns="core", duyet="a")), [])
        self.assertEqual(self.danh_gia(bd("RoleBinding", "operate-data", sa("warpgate-operate-data"), ns="data-pg", duyet="a")), [])

    def test_tai_khoan_lech_vai_tro_hay_namespace(self):
        loi = self.danh_gia(bd("ClusterRoleBinding", "platform-admin", sa("warpgate-debug-app")))
        self.assertIn("đúng một người dùng OIDC", " ".join(loi))
        self.assertIn("chỉ được gán đúng vai trò PAM cùng tên", " ".join(loi))
        loi = self.danh_gia(bd("ClusterRoleBinding", "platform-admin", sa("warpgate-platform-admin", ns="core")))
        self.assertTrue(any("đúng một người dùng OIDC" in m for m in loi), loi)
        loi = self.danh_gia(bd("ClusterRoleBinding", "platform-admin", sa("warpgate-platform-admin") + [{"kind": "User", "name": "oidc:x"}]))
        self.assertTrue(loi)
        loi = self.danh_gia(bd("ClusterRoleBinding", "platform-admin", [{"kind": "Group", "name": "oidc:sre"}]))
        self.assertTrue(any("không cho nhóm" in m for m in loi), loi)

    def test_van_giu_hop_dong_4h_va_hai_nguoi_duyet(self):
        loi = self.danh_gia(bd("ClusterRoleBinding", "platform-admin", sa("warpgate-platform-admin"), het=241))
        self.assertTrue(any("không quá 4h" in m for m in loi), loi)
        loi = self.danh_gia(bd("ClusterRoleBinding", "platform-admin", sa("warpgate-platform-admin"), duyet="a"))
        self.assertTrue(any("người duyệt khác nhau" in m for m in loi), loi)
        loi = self.danh_gia(bd("ClusterRoleBinding", "platform-admin", sa("warpgate-platform-admin"), duyet="a,a"))
        self.assertTrue(any("người duyệt khác nhau" in m for m in loi), loi)
        loi = self.danh_gia(bd("ClusterRoleBinding", "platform-admin", sa("warpgate-platform-admin"), duyet="nam,b"))
        self.assertTrue(any("tự duyệt" in m for m in loi), loi)
        loi = self.danh_gia(bd("RoleBinding", "debug-app", sa("warpgate-debug-app"), ns="secure", duyet="a"))
        self.assertTrue(any("chỉ được gán trong" in m for m in loi), loi)

    def test_chi_broker(self):
        loi = self.danh_gia(bd("ClusterRoleBinding", "platform-admin", sa("warpgate-platform-admin")), nguoi="kubernetes-admin")
        self.assertTrue(any("Chỉ hệ thống PAM" in m for m in loi), loi)
        self.assertTrue(any("chỉ nhận binding do broker PAM tạo" in m for m in loi), loi)

    def test_binding_thuong_truc_toi_tai_khoan_warpgate_bi_chan(self):
        # Vai trò không được bảo vệ (view): chính sách thứ nhất không xét, chính sách thứ hai vẫn chặn.
        loi = self.danh_gia(bd("ClusterRoleBinding", "view", sa("warpgate-debug-app"), ten="doc-cum", nhan=False),
                            nguoi="kubernetes-admin")
        self.assertEqual(len(loi), 3, loi)
        loi = self.danh_gia(bd("RoleBinding", "view", sa("warpgate-debug-app"), ten="pam-x", ns="core"))
        self.assertTrue(any("chỉ được gán đúng vai trò PAM cùng tên" in m for m in loi), loi)
        # ServiceAccount khác trong pam (warpgate, warpgate-lam-moi) không thuộc diện này.
        self.assertEqual(self.danh_gia(bd("ClusterRoleBinding", "view", sa("warpgate-lam-moi"), ten="x", nhan=False),
                                       nguoi="kubernetes-admin"), [])


class QuetRbac(unittest.TestCase):
    CFG = json.loads(tim(D, "ConfigMap", "pam-quet-rbac")[0]["data"]["cau-hinh.json"])
    CR = [{"metadata": {"name": "view-app"}, "rules": [{"apiGroups": [""], "resources": ["pods"], "verbs": ["get"]}]},
          {"metadata": {"name": "tao-token"}, "rules": [{"apiGroups": [""], "resources": ["serviceaccounts/token"], "verbs": ["create"]}]}]

    def chay(self, crbs=(), rbs=()):
        b = [dict(x, metadata=dict(x["metadata"], creationTimestamp=ts(0))) for x in crbs]
        r = [dict(x, metadata=dict(x["metadata"], creationTimestamp=ts(0))) for x in rbs]
        return qr.danh_gia(b, r, self.CR, self.CFG, NOW + dt.timedelta(minutes=5))

    def test_binding_pam_toi_tai_khoan_warpgate_hop_le(self):
        kq = self.chay([bd("ClusterRoleBinding", "platform-admin", sa("warpgate-platform-admin"))],
                       [bd("RoleBinding", "debug-app", sa("warpgate-debug-app"), ten="pam-yc-2", ns="core", duyet="a")])
        self.assertEqual(kq[0], [])
        self.assertEqual(len(kq[1]), 2)

    def test_binding_thuong_truc_la_cua_sau(self):
        kq = self.chay([bd("ClusterRoleBinding", "view-app", sa("warpgate-operate-data"), ten="doc", nhan=False)])
        self.assertEqual([(p["loai"], p["ten"]) for p in kq[0]], [("cua-sau", "doc")])
        self.assertIn("Warpgate", kq[0][0]["chi_tiet"])

    def test_sai_tai_khoan(self):
        kq = self.chay([bd("ClusterRoleBinding", "platform-admin", sa("warpgate-debug-app"))])
        self.assertEqual({p["loai"] for p in kq[0]}, {"sai-hop-dong"})
        kq = self.chay([bd("ClusterRoleBinding", "platform-admin", sa("warpgate-platform-admin", ns="core"))])
        self.assertEqual({p["loai"] for p in kq[0]}, {"sai-hop-dong"})

    def test_tao_token_la_quyen_nhay_cam(self):
        kq = self.chay([bd("ClusterRoleBinding", "tao-token", [{"kind": "User", "name": "oidc:nam"}], ten="x", nhan=False)])
        self.assertEqual([p["loai"] for p in kq[0]], ["cua-sau"])


# ------------------------------------------------------------------ warpgate_lam_moi.py với máy chủ giả
class MayChuGia:
    """Một máy chủ HTTP đóng ba vai: API server (TokenRequest), OpenBao (login, KV v2, database creds), Warpgate (targets)."""

    def __init__(self, tao_nguoi_dung=None):
        self.kv, self.goi, self.so = {}, [], 0
        self.targets = [{"id": "t-1", "name": "psql-pg-core", "description": "", "ticket_requests_disabled": True,
                         "ticket_require_approval": False, "require_approval": False, "group_id": None,
                         "options": {"kind": "Postgres", "host": "h", "port": 5432, "username": "chua-cap",
                                     "tls": {"mode": "Required", "verify": True},
                                     "auth": {"kind": "Password", "password": "secret://openbao-cell/kv/warpgate/psql-pg-core#password"}}}]
        self.tao = tao_nguoi_dung or (lambda ten, mk, het: None)
        self.loi_token = set()
        g = self

        class H(http.server.BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def tra(self, st, body=None):
                b = b"" if body is None else json.dumps(body).encode()
                self.send_response(st)
                self.send_header("Content-Length", str(len(b)))
                self.end_headers()
                self.wfile.write(b)

            def than(self):
                n = int(self.headers.get("Content-Length") or 0)
                return json.loads(self.rfile.read(n)) if n else None

            def xu_ly(self, method):
                p, b = self.path, self.than()
                g.goi.append((method, p))
                if p == "/v1/auth/k8s-cell-g4/login":
                    ok = b == {"role": "warpgate-lam-moi", "jwt": "jwt-openbao"}
                    return self.tra(200, {"auth": {"client_token": "tok-bao"}}) if ok else self.tra(403, {"errors": ["sai"]})
                if p.startswith("/v1/") and self.headers.get("X-Vault-Token") != "tok-bao":
                    return self.tra(403, {"errors": ["permission denied"]})
                m = re.fullmatch(r"/v1/kv/data/(.+)", p)
                if m:
                    if method == "GET":
                        return self.tra(200, {"data": {"data": g.kv[m[1]]}}) if m[1] in g.kv else self.tra(404, {"errors": []})
                    g.kv[m[1]] = b["data"]
                    return self.tra(200, {"data": {"version": 1}})
                if p == "/v1/database/creds/pg-core-dba-ops":
                    g.so += 1
                    ten, mk = f"v-warpgate-pg-core-dba-ops-{g.so}", f"mk-{os.urandom(6).hex()}"
                    het = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(hours=4)).isoformat()
                    g.tao(ten, mk, het)
                    return self.tra(200, {"lease_id": f"database/creds/pg-core-dba-ops/l{g.so}", "lease_duration": 14400,
                                          "data": {"username": ten, "password": mk}})
                m = re.fullmatch(r"/api/v1/namespaces/pam/serviceaccounts/([^/]+)/token", p)
                if m:
                    if self.headers.get("Authorization") != "Bearer tok-k8s" or m[1] in g.loi_token:
                        return self.tra(403, {"kind": "Status", "message": "forbidden"})
                    het = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(seconds=b["spec"]["expirationSeconds"]))
                    return self.tra(201, {"status": {"token": f"jwt-{m[1]}", "expirationTimestamp": het.strftime("%Y-%m-%dT%H:%M:%SZ")}})
                if p.startswith("/@warpgate/admin/api") and self.headers.get("X-Warpgate-Token") != "tok-wg":
                    return self.tra(401)
                if p == "/@warpgate/admin/api/targets":
                    return self.tra(200, g.targets)
                m = re.fullmatch(r"/@warpgate/admin/api/targets/(.+)", p)
                if m and method == "PUT":
                    t = next(t for t in g.targets if t["id"] == m[1])
                    t.update(b)
                    return self.tra(200, t)
                return self.tra(404, {"errors": [p]})

            def do_GET(self):
                self.xu_ly("GET")

            def do_POST(self):
                self.xu_ly("POST")

            def do_PUT(self):
                self.xu_ly("PUT")

        self.srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), H)
        self.url = f"http://127.0.0.1:{self.srv.server_address[1]}"
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()

    def dong(self):
        self.srv.shutdown()
        self.srv.server_close()


def cau_hinh_lam_moi(url):
    cfg = json.loads(tim(D, "ConfigMap", "warpgate-viec", "pam")[0]["data"]["lam-moi.json"])
    cfg["openbao"]["address"] = url
    cfg["warpgate"] = url
    return cfg


def moi_truong(tmp, url):
    for ten, nd in (("k8s", "tok-k8s"), ("bao", "jwt-openbao"), ("wg", "tok-wg")):
        with open(os.path.join(tmp, ten), "w") as f:
            f.write(nd + "\n")
    return {"K8S_API": url, "K8S_TOKEN": f"{tmp}/k8s", "K8S_CA": "", "BAO_JWT": f"{tmp}/bao", "BAO_CA": "",
            "WARPGATE_TOKEN_FILE": f"{tmp}/wg", "WARPGATE_CA": ""}


class LamMoiGia(unittest.TestCase):
    def setUp(self):
        self.enterContext(contextlib.redirect_stdout(io.StringIO()))  # nhật ký ECS của script
        self.tmp = tempfile.mkdtemp(prefix="wg-lm-")
        self.g = MayChuGia()
        self.addCleanup(self.g.dong)
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.env = moi_truong(self.tmp, self.g.url)

    def chay(self, cfg=None):
        x = lm.LamMoi(cfg or cau_hinh_lam_moi(self.g.url), self.env)
        x.chay("openbao-cell")
        return x

    def test_lan_dau_cap_token_va_nguoi_dung(self):
        x = self.chay()
        self.assertEqual(x.loi, 0)
        for v in VAI_TRO:
            self.assertEqual(self.g.kv[f"warpgate/kubectl-g4-{v}"]["token"], f"jwt-warpgate-{v}")
        nd = self.g.kv["warpgate/psql-pg-core"]
        self.assertEqual(nd["username"], "v-warpgate-pg-core-dba-ops-1")
        t = self.g.targets[0]
        self.assertEqual(t["options"]["username"], "v-warpgate-pg-core-dba-ops-1")
        self.assertEqual(t["options"]["auth"], {"kind": "Password", "password": "secret://openbao-cell/kv/warpgate/psql-pg-core#password"})
        self.assertEqual((t["options"]["host"], t["options"]["tls"], t["ticket_requests_disabled"]), ("h", {"mode": "Required", "verify": True}, True))
        cs = x.chi_so()
        self.assertIn('vt_pam_warpgate_bi_mat_het_han_timestamp_seconds{kv="warpgate/psql-pg-core"}', cs)
        self.assertIn("vt_pam_warpgate_lam_moi_last_success_timestamp_seconds", cs)
        self.assertIn("vt_pam_warpgate_lam_moi_loi 0", cs)

    def test_con_han_thi_giu_het_han_thi_doi(self):
        self.chay()
        self.chay()
        self.assertEqual(self.g.so, 1, "lease còn ~4 giờ > ngưỡng 90 phút: không xin người dùng mới")
        self.g.kv["warpgate/psql-pg-core"]["het_han"] = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(minutes=80)).strftime("%Y-%m-%dT%H:%M:%SZ")
        self.chay()
        self.assertEqual(self.g.so, 2)
        self.assertEqual(self.g.targets[0]["options"]["username"], "v-warpgate-pg-core-dba-ops-2")
        self.assertFalse([p for m, p in self.g.goi if "revoke" in p], "không thu hồi sớm người dùng cũ")

    def test_mot_viec_loi_van_lam_viec_khac(self):
        self.g.loi_token.add("warpgate-operate-data")
        x = self.chay()
        self.assertEqual(x.loi, 1)
        self.assertIn("warpgate/kubectl-g4-platform-admin", self.g.kv)
        self.assertIn("warpgate/psql-pg-core", self.g.kv)
        self.assertNotIn("last_success", x.chi_so())

    def test_sai_jwt_openbao(self):
        with open(f"{self.tmp}/bao", "w") as f:
            f.write("khac")
        x = self.chay()
        self.assertEqual(x.loi, 1)
        self.assertEqual(self.g.kv, {})

    def test_main_day_chi_so_bang_post(self):
        nhan = []

        class P(http.server.BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_POST(self):
                nhan.append((self.command, self.path, self.rfile.read(int(self.headers["Content-Length"])).decode()))
                self.send_response(200)
                self.send_header("Content-Length", "0")
                self.end_headers()

            do_PUT = do_POST

        srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), P)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        self.addCleanup(srv.server_close)
        self.addCleanup(srv.shutdown)
        with open(f"{self.tmp}/cfg.json", "w") as f:
            json.dump(cau_hinh_lam_moi(self.g.url), f)
        env = dict(os.environ, **self.env, CAU_HINH=f"{self.tmp}/cfg.json", PUSHGATEWAY=f"http://127.0.0.1:{srv.server_address[1]}")
        r = subprocess.run([sys.executable, "-I", lm.__file__], env=env, capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertEqual([(m, p) for m, p, _ in nhan], [("POST", "/metrics/job/warpgate-lam-moi")])
        su_kien = [json.loads(l) for l in r.stdout.splitlines()]
        self.assertTrue(all(e["event"]["module"] == "pam" for e in su_kien))
        self.assertNotIn("mk-", r.stdout, "mật khẩu không vào nhật ký")
        self.assertNotIn("jwt-warpgate", r.stdout, "token không vào nhật ký")


def tim_pg_bin():
    if os.environ.get("PG_BIN"):
        return os.environ["PG_BIN"]
    import glob
    ung = sorted(glob.glob("/usr/lib/postgresql/*/bin/initdb"), key=lambda p: int(p.split("/")[4]))
    if ung and int(ung[-1].split("/")[4]) >= 16:
        return os.path.dirname(ung[-1])
    return None


@unittest.skipUnless(tim_pg_bin() and os.path.exists(os.path.join(tim_pg_bin() or "", "postgres")),
                     "không có PostgreSQL ≥ 16 cài sẵn (đặt PG_BIN)")
class LamMoiPgThat(unittest.TestCase):
    """OpenBao giả chạy đúng câu tạo người dùng động của cau-hinh-pam.sh (bằng bao_admin) trên PostgreSQL 16 thật;
    người dùng mà CronJob ghi vào KV đăng nhập được bằng mật khẩu (scram), là thành viên dba_ops, có VALID UNTIL."""

    @classmethod
    def setUpClass(cls):
        cls.bin = tim_pg_bin()
        cls.tmp = tempfile.mkdtemp(prefix="wg-pg-")
        cls.chay_bang = []
        if os.geteuid() == 0:
            cls.chay_bang = ["runuser", "-u", "postgres", "--"]
            shutil.chown(cls.tmp, "postgres")
        cls.data = os.path.join(cls.tmp, "data")
        cls.port = str(cong_trong())
        cls.lenh([f"{cls.bin}/initdb", "-D", cls.data, "--auth-local=trust", "--auth-host=scram-sha-256", "-U", "postgres",
                  "--no-instructions", "-E", "UTF8"])
        cls.lenh([f"{cls.bin}/pg_ctl", "-D", cls.data, "-w", "-l", os.path.join(cls.tmp, "log"),
                  "-o", f"-c listen_addresses=127.0.0.1 -k {cls.tmp} -p {cls.port}", "start"])
        cls.psql("-c", "CREATE ROLE bao_admin LOGIN CREATEROLE")
        cls.psql("-f", os.path.join(CHART, "files", "sql", "vai-tro-dba.sql"))
        cls.cau = subprocess.run(["bash", os.path.join(CHART, "files", "openbao", "cau-hinh-pam.sh"), "--in-sql", "dba_ops", "2"],
                                 capture_output=True, text=True, check=True).stdout.splitlines()[0]

    @classmethod
    def lenh(cls, args):
        return subprocess.run(cls.chay_bang + args, capture_output=True, text=True, check=True, cwd=cls.tmp)

    @classmethod
    def psql(cls, *args, nguoi="postgres", mat_khau=None, host=None):
        a = [f"{cls.bin}/psql", "-X", "-q", "-At", "-v", "ON_ERROR_STOP=1", "-h", host or cls.tmp, "-p", cls.port, "-U", nguoi, "-d", "postgres"]
        env = dict(os.environ, PGPASSWORD=mat_khau or "", PGCONNECT_TIMEOUT="5")
        return subprocess.run(a + list(args), capture_output=True, text=True, check=True, env=env).stdout.strip()

    @classmethod
    def tearDownClass(cls):
        subprocess.run(cls.chay_bang + [f"{cls.bin}/pg_ctl", "-D", cls.data, "-m", "immediate", "stop"], capture_output=True)
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def test_nguoi_dung_dong_that(self):
        self.enterContext(contextlib.redirect_stdout(io.StringIO()))
        def tao(ten, mk, het):
            sql = self.cau.replace("{{name}}", ten).replace("{{password}}", mk).replace("{{expiration}}", het)
            self.psql("-c", sql, nguoi="bao_admin")

        g = MayChuGia(tao)
        tmp = tempfile.mkdtemp(prefix="wg-lm-", dir=self.tmp)
        try:
            x = lm.LamMoi(cau_hinh_lam_moi(g.url), moi_truong(tmp, g.url))
            x.chay("openbao-cell")
            self.assertEqual(x.loi, 0)
        finally:
            g.dong()
        nd = g.kv["warpgate/psql-pg-core"]
        self.assertEqual(g.targets[0]["options"]["username"], nd["username"])
        ra = self.psql("-c", "SELECT current_user, pg_has_role('dba_ops', 'MEMBER'), pg_has_role('pg_monitor', 'USAGE')",
                       nguoi=nd["username"], mat_khau=nd["password"], host="127.0.0.1")
        self.assertEqual(ra, f"{nd['username']}|t|t")
        han = self.psql("-c", f"SELECT rolconnlimit, extract(epoch FROM rolvaliduntil - now())::int / 60 FROM pg_roles WHERE rolname = '{nd['username']}'")
        so_ket_noi, phut = han.split("|")
        self.assertEqual(so_ket_noi, "2")
        self.assertTrue(235 <= int(phut) <= 240, han)
        with self.assertRaises(subprocess.CalledProcessError):
            self.psql("-c", "SELECT 1", nguoi=nd["username"], mat_khau="sai", host="127.0.0.1")


# ------------------------------------------------------------------ sao_chep_ghi_phien.py
class SigV4(unittest.TestCase):
    """Ví dụ mẫu trong tài liệu AWS "Signature Calculations for the Authorization Header" (dịch vụ s3)."""
    AK, SK = "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
    NOW = dt.datetime(2013, 5, 24, tzinfo=dt.timezone.utc)
    RONG = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    def test_get_object(self):
        h = sc.ky_sigv4("GET", "examplebucket.s3.amazonaws.com", "/test.txt", {}, {"Range": "bytes=0-9"}, self.AK, self.SK,
                        "us-east-1", self.NOW, payload_hash=self.RONG)
        self.assertEqual(h["authorization"],
                         "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, "
                         "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, "
                         "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41")

    def test_list_objects(self):
        h = sc.ky_sigv4("GET", "examplebucket.s3.amazonaws.com", "/", {"max-keys": "2", "prefix": "J"}, {}, self.AK, self.SK,
                        "us-east-1", self.NOW, payload_hash=self.RONG)
        self.assertTrue(h["authorization"].endswith("Signature=34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7"))


class S3Gia:
    """S3 kiểu path-style: kiểm chữ ký SigV4 (tính lại bằng ky_sigv4), Content-MD5 bắt buộc khi PUT, versioning."""

    def __init__(self, ak="ak-thu", sk="sk-thu", region="cell-g4-zg"):
        self.obj, self.phien_ban, self.loi_khoa = {}, {}, set()
        g = self

        class H(http.server.BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def tra(self, st, body=b""):
                self.send_response(st)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def kiem_ky(self):
                u = urllib.parse.urlsplit(self.path)
                q = dict(urllib.parse.parse_qsl(u.query, keep_blank_values=True))
                ky = {k: v for k, v in self.headers.items() if k.lower() in self.headers["authorization"].split("SignedHeaders=")[1].split(",")[0].split(";")
                      and k.lower() not in ("host", "x-amz-date", "x-amz-content-sha256")}
                now = dt.datetime.strptime(self.headers["x-amz-date"], "%Y%m%dT%H%M%SZ").replace(tzinfo=dt.timezone.utc)
                h = sc.ky_sigv4(self.command, self.headers["host"], u.path, q, ky, ak, sk, region, now,
                                payload_hash=self.headers["x-amz-content-sha256"])
                return h["authorization"] == self.headers["authorization"], u, q

            def do_GET(self):
                ok, u, q = self.kiem_ky()
                if not ok:
                    return self.tra(403, b"<Error><Code>SignatureDoesNotMatch</Code></Error>")
                tt = "".join(f"<Contents><Key>{k}</Key><Size>{len(v)}</Size></Contents>" for k, v in sorted(g.obj.items())
                             if k.startswith(q.get("prefix", "")))
                self.tra(200, f'<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><IsTruncated>false</IsTruncated>{tt}</ListBucketResult>'.encode())

            def do_PUT(self):
                ok, u, _ = self.kiem_ky()
                body = self.rfile.read(int(self.headers["Content-Length"]))
                if not ok:
                    return self.tra(403)
                if "content-md5" not in {k.lower() for k in self.headers}:
                    return self.tra(400, b"<Error><Code>InvalidRequest</Code><Message>Content-MD5 required</Message></Error>")
                if base64.b64decode(self.headers["content-md5"]) != hashlib.md5(body).digest():
                    return self.tra(400, b"<Error><Code>BadDigest</Code></Error>")
                khoa = urllib.parse.unquote(u.path.split("/", 2)[2])
                if khoa in g.loi_khoa:
                    return self.tra(500)
                g.obj[khoa] = body
                g.phien_ban[khoa] = g.phien_ban.get(khoa, 0) + 1
                self.tra(200)

        self.srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), H)
        self.url = f"http://127.0.0.1:{self.srv.server_address[1]}"
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()

    def dong(self):
        self.srv.shutdown()
        self.srv.server_close()


class SaoChep(unittest.TestCase):
    def setUp(self):
        self.enterContext(contextlib.redirect_stdout(io.StringIO()))
        self.tmp = tempfile.mkdtemp(prefix="wg-gp-")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.s3 = S3Gia()
        self.addCleanup(self.s3.dong)
        self.goc = os.path.join(self.tmp, "recordings")
        os.makedirs(os.path.join(self.goc, "phien-1"))
        os.makedirs(os.path.join(self.tmp, "khoa"))
        for ten, nd in (("access-key", "ak-thu\n"), ("secret-key", "sk-thu\n")):
            with open(os.path.join(self.tmp, "khoa", ten), "w") as f:
                f.write(nd)
        self.cu = time.time() - 3600
        self.ghi("phien-1/ssh.asciicast", b"du lieu phien " * 100, self.cu)
        self.ghi("phien 2/kubernetes.json", b'{"a": 1}', self.cu)
        self.ghi("phien-1/dang-ghi.asciicast", b"dang ghi", time.time())
        self.env = {"THU_MUC": self.goc, "S3_ENDPOINT": self.s3.url, "S3_REGION": "cell-g4-zg", "BUCKET": "pam-cell-g4",
                    "TIEN_TO": "ghi-phien/g4", "ON_DINH_PHUT": "10", "S3_KHOA": os.path.join(self.tmp, "khoa")}

    def ghi(self, ten, nd, mtime):
        p = os.path.join(self.goc, ten)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "wb") as f:
            f.write(nd)
        os.utime(p, (mtime, mtime))

    def test_chep_tep_on_dinh_bo_qua_tep_da_co(self):
        kq = sc.chay(self.env)
        self.assertEqual((kq["chep"], kq["cho"], kq["loi"]), (2, 1, 0))
        self.assertEqual(set(self.s3.obj), {"ghi-phien/g4/phien-1/ssh.asciicast", "ghi-phien/g4/phien 2/kubernetes.json"})
        kq = sc.chay(self.env)
        self.assertEqual((kq["chep"], kq["cho"]), (0, 1), "đã có cùng kích thước thì không chép lại")
        # Phiên dài ghi tiếp sau khi đã chép: chép lại thành phiên bản mới (bucket versioning, bản cũ vẫn bị khoá).
        self.ghi("phien-1/ssh.asciicast", b"du lieu phien " * 200, self.cu + 60)
        kq = sc.chay(self.env)
        self.assertEqual(kq["chep"], 1)
        self.assertEqual(self.s3.phien_ban["ghi-phien/g4/phien-1/ssh.asciicast"], 2)

    def test_loi_mot_tep_khong_bao_thanh_cong(self):
        self.s3.loi_khoa.add("ghi-phien/g4/phien 2/kubernetes.json")
        kq = sc.chay(self.env)
        self.assertEqual((kq["chep"], kq["loi"]), (1, 1))
        cs = sc.chi_so(kq, "g4")
        self.assertNotIn("last_success", cs)
        self.assertIn('vt_pam_ghi_phien_loi{cell="g4"} 1', cs)
        self.s3.loi_khoa.clear()
        cs = sc.chi_so(sc.chay(self.env), "g4")
        self.assertIn('vt_pam_ghi_phien_last_success_timestamp_seconds{cell="g4"}', cs)

    def test_sai_khoa_thi_loi(self):
        with open(os.path.join(self.tmp, "khoa", "secret-key"), "w") as f:
            f.write("khac")
        with self.assertRaises(RuntimeError):
            sc.chay(self.env)

    def test_main_ma_thoat_va_chi_so(self):
        nhan = []

        class P(http.server.BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_POST(self):
                nhan.append((self.command, self.path, self.rfile.read(int(self.headers["Content-Length"])).decode()))
                self.send_response(200)
                self.send_header("Content-Length", "0")
                self.end_headers()

        srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), P)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        self.addCleanup(srv.server_close)
        self.addCleanup(srv.shutdown)
        env = dict(os.environ, **self.env, CELL="g4", PUSHGATEWAY=f"http://127.0.0.1:{srv.server_address[1]}")
        r = subprocess.run([sys.executable, "-I", sc.__file__], env=env, capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertEqual(nhan[0][:2], ("POST", "/metrics/job/warpgate-ghi-phien"))
        self.assertIn('vt_pam_ghi_phien_da_chep{cell="g4"} 2', nhan[0][2])
        self.assertNotIn("sk-thu", r.stdout)
        env["S3_ENDPOINT"] = f"http://127.0.0.1:{cong_trong()}"
        r = subprocess.run([sys.executable, "-I", sc.__file__], env=env, capture_output=True, text=True)
        self.assertEqual(r.returncode, 1)
        self.assertNotIn("last_success", nhan[-1][2])


if __name__ == "__main__":
    unittest.main()
