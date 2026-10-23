#!/usr/bin/env python3
"""Kiểm thử chart pam-integration (INF-27; TL-04 mục 9.3 SEC-29, SEC-58, mục 9.7 SEC-42).

Ba nhóm:
  1. Bản render helm template: namespace restricted, ảnh theo digest, không có binding thường trực tới vai trò PAM,
     broker chỉ bind đúng ba vai trò, chính sách nhập đủ điều kiện hợp đồng, chart từ chối nới hợp đồng.
  2. Logic quét RBAC (files/scripts/quet_rbac.py) và báo cáo CSDL (files/scripts/day_chi_so_csdl.py) trên dữ liệu mẫu,
     dùng đúng cấu hình JSON mà chart render ra.
  3. SQL thật trên PostgreSQL tạm (chỉ khi máy có sẵn bản cài PostgreSQL ≥ 16, không kéo ảnh hay dựng container):
     vai-tro-dba.sql chạy lại được, quet-vai-tro.sql tìm đúng các cửa sau của tests/sql/du-lieu-mau.sql, câu tạo và câu
     thu hồi người dùng động của OpenBao chạy được bằng bao_admin không phải siêu người dùng, dba_ops không đọc được
     bảng nghiệp vụ còn phá kính đọc được, đếm kết nối quản trị.

Dùng: python3 -m unittest discover -s deploy/charts/pam-integration/tests
Biến: HELM=<tệp helm>; PG_BIN=<thư mục bin của PostgreSQL> (mặc định tìm /usr/lib/postgresql/*/bin).
"""
import datetime as dt
import glob
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest

import yaml

CHART = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
HELM = os.environ.get("HELM") or shutil.which("helm") or "/root/bin/helm"
sys.dont_write_bytecode = True


def nap(ten, tep):
    spec = importlib.util.spec_from_file_location(ten, os.path.join(CHART, "files", "scripts", tep))
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


qr = nap("quet_rbac", "quet_rbac.py")
qc = nap("day_chi_so_csdl", "day_chi_so_csdl.py")


def render(*args):
    return subprocess.run([HELM, "template", "t", CHART, *args], capture_output=True, text=True)


def docs(*args):
    r = render(*args)
    if r.returncode != 0:
        raise AssertionError(r.stderr)
    return [d for d in yaml.safe_load_all(r.stdout) if d]


def tim(ds, kind, name=None, ns=None):
    return [d for d in ds if d["kind"] == kind and (name is None or d["metadata"]["name"] == name)
            and (ns is None or d["metadata"].get("namespace") == ns)]


D = docs()
CFG = json.loads(tim(D, "ConfigMap", "pam-quet-rbac")[0]["data"]["cau-hinh.json"])
NOW = dt.datetime(2026, 10, 23, 8, 0, tzinfo=dt.timezone.utc)


def ts(delta_phut):
    return (NOW + dt.timedelta(minutes=delta_phut)).strftime("%Y-%m-%dT%H:%M:%SZ")


def binding(kind, ten, vai_tro, chu_the, ns=None, nhan=None, chu_thich=None, tao=-1):
    md = {"name": ten, "creationTimestamp": ts(tao)}
    if ns:
        md["namespace"] = ns
    if nhan:
        md["labels"] = nhan
    if chu_thich:
        md["annotations"] = chu_thich
    return {"kind": kind, "metadata": md, "roleRef": {"kind": "ClusterRole", "name": vai_tro},
            "subjects": chu_the}


def pam(kind, ten, vai_tro, ns=None, duyet="duyet1", cap=0, het=240, nguoi="oidc:nam", yeu_cau="nam", tao=None):
    return binding(kind, ten, vai_tro, [{"kind": "User", "name": nguoi}], ns=ns, nhan={"vt.io/cap-phat": "pam"},
                   chu_thich={"vt.io/pam-yeu-cau": "YC-1", "vt.io/nguoi-yeu-cau": yeu_cau, "vt.io/nguoi-duyet": duyet,
                              "vt.io/cap-luc": ts(cap), "vt.io/het-han": ts(het)},
                   tao=cap if tao is None else tao)


class Render(unittest.TestCase):
    def test_namespace_restricted_va_mac_dinh_tu_choi(self):
        ns = tim(D, "Namespace", "pam-kiem-soat")[0]["metadata"]["labels"]
        self.assertEqual(ns["pod-security.kubernetes.io/enforce"], "restricted")
        self.assertTrue(tim(D, "NetworkPolicy", "default-deny-all", "pam-kiem-soat"))

    def test_pod_restricted_anh_digest(self):
        for cj in tim(D, "CronJob"):
            spec = cj["spec"]["jobTemplate"]["spec"]["template"]["spec"]
            self.assertTrue(spec["securityContext"]["runAsNonRoot"])
            for c in spec.get("initContainers", []) + spec["containers"]:
                sc = c["securityContext"]
                self.assertTrue(sc["readOnlyRootFilesystem"] and not sc["allowPrivilegeEscalation"], c["name"])
                self.assertEqual(sc["capabilities"]["drop"], ["ALL"])
                self.assertRegex(c["image"], r"^harbor\.vt\.local/.+@sha256:[a-f0-9]{64}$")
                self.assertIn("limits", c["resources"])

    def test_khong_binding_thuong_truc_toi_vai_tro_pam(self):
        for b in tim(D, "RoleBinding") + tim(D, "ClusterRoleBinding"):
            self.assertNotIn(b["roleRef"]["name"], ("debug-app", "operate-data", "platform-admin", "cluster-admin"))

    def test_broker_chi_bind_ba_vai_tro(self):
        r = tim(D, "ClusterRole", "vt-pam-broker")[0]["rules"]
        bind = [x for x in r if "bind" in x["verbs"]]
        self.assertEqual(len(bind), 1)
        self.assertEqual(sorted(bind[0]["resourceNames"]), ["debug-app", "operate-data", "platform-admin"])
        for x in r:
            self.assertNotIn("*", x["verbs"])
            self.assertNotIn("escalate", x["verbs"])
            self.assertNotIn("impersonate", x["verbs"])
        self.assertFalse(qr.quy_tac_dac_quyen([x for x in r if "bind" not in x["verbs"]]))

    def test_quet_rbac_chi_doc_va_xoa(self):
        r = tim(D, "ClusterRole", "vt-pam-quet-rbac")[0]["rules"]
        verbs = {v for x in r for v in x["verbs"]}
        self.assertEqual(verbs, {"get", "list", "delete"})
        self.assertFalse(qr.quy_tac_dac_quyen(r))

    def test_chinh_sach_nhap_du_dieu_kien(self):
        p = tim(D, "ValidatingAdmissionPolicy", "vt-pam-binding-co-thoi-han")[0]["spec"]
        self.assertEqual(p["failurePolicy"], "Fail")
        bt = " ".join(v["expression"] for v in p["validations"])
        for can in ("oidc:service-account-pam-broker", "duration('4h')", "startsWith('pam-')", "vt.io/cap-phat",
                    "variables.canDuyet", "variables.nguoiYeuCau in variables.duyet", "oldObject", "size(object.subjects) == 1"):
            self.assertIn(can, bt)
        self.assertIn("object.roleRef.name == 'platform-admin' ? 2", p["variables"][2]["expression"])
        b = tim(D, "ValidatingAdmissionPolicyBinding")[0]["spec"]
        self.assertEqual(b["validationActions"], ["Deny"])

    def test_quet_csdl_moi_cum_va_ket_noi_tls(self):
        cj = tim(D, "CronJob")
        cums = {c["metadata"]["labels"].get("vt.io/pg-cluster") for c in cj} - {None}
        self.assertEqual(cums, {"pg-core", "pg-flowable", "pg-platform", "pg-iam", "pg-ledger", "pg-secure"})
        c = tim(D, "CronJob", "pam-quet-csdl-pg-ledger")[0]["spec"]["jobTemplate"]["spec"]["template"]["spec"]
        env = {e["name"]: e.get("value") for e in c["initContainers"][0]["env"]}
        self.assertEqual(env["PGHOST"], "pg-ledger-rw.secure-data.svc")
        self.assertEqual(env["HAN"], "2 hours 10 minutes")
        self.assertEqual(env["PHA_KINH"], "dba_pha_kinh_doc")
        self.assertFalse(c["automountServiceAccountToken"])
        self.assertTrue(tim(D, "NetworkPolicy", "pam-quet-csdl-ingress", "secure-data"))
        self.assertFalse(tim(D, "NetworkPolicy", "pam-proxy-to-pg"))

    def test_mo_duong_proxy_pam_khi_khai_cidr(self):
        d = docs("--set-json", 'pam.proxyCIDRs=["192.0.2.0/28"]')
        self.assertEqual(len(tim(d, "NetworkPolicy", "pam-proxy-to-pg")), 2)

    def test_tu_choi_noi_hop_dong(self):
        for k, v in (("hopDong.thoiHanToiDa", "8h"), ("hopDong.soNguoiDuyet.platform-admin", "1"),
                     ("vaiTroCsdl.thoiHanPhaKinh", "4h"), ("vaiTroCsdl.dbaOps", "dba")):
            r = render("--set", f"{k}={v}")
            self.assertNotEqual(r.returncode, 0, k)
        r = render("--set-json", 'hopDong.vaiTroPam=["debug-app","operate-data","platform-admin","cluster-admin"]')
        self.assertNotEqual(r.returncode, 0)

    def test_quy_tac_canh_bao(self):
        nhom = tim(D, "PrometheusRule", "vt-pam")[0]["spec"]["groups"][0]["rules"]
        ten = {r["alert"] for r in nhom}
        self.assertTrue({"PamCuaSauRbac", "PamBindingQuaHan", "PamCuaSauCsdl", "PamKetNoiQuanTriVuotNganSach"} <= ten)

    def test_cau_hinh_tu_them_binding_cua_chart(self):
        muc = CFG["quetRbac"]["choPhep"][-1]
        self.assertEqual(muc["ten"], "^vt-pam-(broker|quet-rbac)$")
        self.assertIn("oidc:service-account-pam-broker", muc["chuThe"])


class QuetRbac(unittest.TestCase):
    CR = [{"metadata": {"name": "nhan-ban-admin"}, "rules": [{"apiGroups": ["*"], "resources": ["*"], "verbs": ["*"]}]},
          {"metadata": {"name": "doc-secret"}, "rules": [{"apiGroups": [""], "resources": ["secrets"], "verbs": ["get"]}]},
          {"metadata": {"name": "view-app"}, "rules": [{"apiGroups": [""], "resources": ["pods"], "verbs": ["get", "list"]}]},
          {"metadata": {"name": "debug-app", "labels": {"vt.io/cap-phat": "pam-4h"}}, "rules": []}]

    def chay(self, crbs=(), rbs=(), cfg=CFG):
        return qr.danh_gia(list(crbs), list(rbs), self.CR, cfg, NOW)

    def loai(self, kq):
        return {(p["loai"], p["ten"]) for p in kq[0]}

    def test_crb_tao_tay_toi_cluster_admin_la_cua_sau(self):
        kq = self.chay([binding("ClusterRoleBinding", "tao-tay", "cluster-admin", [{"kind": "User", "name": "oidc:nam"}])])
        self.assertEqual(self.loai(kq), {("cua-sau", "tao-tay")})

    def test_nhan_ban_cluster_admin_va_doc_secret_cung_la_dac_quyen(self):
        kq = self.chay([binding("ClusterRoleBinding", "x", "nhan-ban-admin", [{"kind": "Group", "name": "oidc:a"}])],
                       [binding("RoleBinding", "y", "doc-secret", [{"kind": "User", "name": "oidc:b"}], ns="core")])
        self.assertEqual(self.loai(kq), {("cua-sau", "x"), ("cua-sau", "y")})

    def test_binding_he_thong_va_cua_chart_duoc_phep(self):
        kq = self.chay([
            binding("ClusterRoleBinding", "cluster-admin", "cluster-admin", [{"kind": "Group", "name": "system:masters"}]),
            binding("ClusterRoleBinding", "system:controller:x", "nhan-ban-admin",
                    [{"kind": "ServiceAccount", "name": "x", "namespace": "kube-system"}]),
            binding("ClusterRoleBinding", "vt-pam-broker", "nhan-ban-admin", [{"kind": "User", "name": "oidc:service-account-pam-broker"}]),
            binding("ClusterRoleBinding", "view", "view-app", [{"kind": "Group", "name": "oidc:dev"}]),
        ])
        self.assertEqual(kq[0], [])

    def test_them_chu_the_vao_binding_he_thong_bi_phat_hien(self):
        kq = self.chay([binding("ClusterRoleBinding", "cluster-admin", "cluster-admin",
                                [{"kind": "Group", "name": "system:masters"}, {"kind": "User", "name": "oidc:ke-gian"}])])
        self.assertEqual(self.loai(kq), {("cua-sau", "cluster-admin")})

    def test_binding_pam_hop_le_dang_mo(self):
        kq = self.chay([pam("ClusterRoleBinding", "pam-yc-2", "platform-admin", duyet="a,b", het=200)],
                       [pam("RoleBinding", "pam-yc-1", "debug-app", ns="core")])
        self.assertEqual(kq[0], [])
        self.assertEqual({b["ten"] for b in kq[1]}, {"pam-yc-1", "pam-yc-2"})
        self.assertEqual(kq[2], [])

    def test_qua_han_thi_thu_hoi_sau_an_han(self):
        kq = self.chay(rbs=[pam("RoleBinding", "pam-cu", "debug-app", ns="core", cap=-250, het=-10),
                            pam("RoleBinding", "pam-vua-het", "debug-app", ns="core", cap=-242, het=-2)])
        self.assertEqual(self.loai(kq), {("qua-han", "pam-cu"), ("qua-han", "pam-vua-het")})
        self.assertEqual([b["ten"] for b in kq[2]], ["pam-cu"])

    def test_khong_thu_hoi_khi_tat(self):
        cfg = json.loads(json.dumps(CFG))
        cfg["quetRbac"]["thuHoiQuaHan"] = False
        kq = self.chay(rbs=[pam("RoleBinding", "pam-cu", "debug-app", ns="core", cap=-250, het=-10)], cfg=cfg)
        self.assertEqual(kq[2], [])

    def test_sai_hop_dong(self):
        ca = {
            "mot-nguoi-duyet": pam("ClusterRoleBinding", "pam-a", "platform-admin", duyet="a"),
            "trung-nguoi-duyet": pam("ClusterRoleBinding", "pam-b", "platform-admin", duyet="a,a"),
            "tu-duyet": pam("ClusterRoleBinding", "pam-c", "platform-admin", duyet="nam,b"),
            "qua-4h": pam("RoleBinding", "pam-d", "debug-app", ns="core", het=300),
            "sai-vung": pam("RoleBinding", "pam-e", "debug-app", ns="data-pg"),
            "toan-cum-debug": pam("ClusterRoleBinding", "pam-f", "debug-app"),
            "nhom": dict(pam("RoleBinding", "pam-g", "debug-app", ns="core"), subjects=[{"kind": "Group", "name": "oidc:sre"}]),
            "ten": pam("RoleBinding", "tam-h", "debug-app", ns="core"),
            "cluster-admin": pam("ClusterRoleBinding", "pam-i", "cluster-admin", duyet="a,b"),
            "lech-tao": pam("RoleBinding", "pam-j", "debug-app", ns="core", tao=-60),
        }
        crbs = [b for b in ca.values() if b["kind"] == "ClusterRoleBinding"]
        rbs = [b for b in ca.values() if b["kind"] == "RoleBinding"]
        kq = self.chay(crbs, rbs)
        sai = {p["ten"] for p in kq[0] if p["loai"] == "sai-hop-dong"}
        self.assertEqual(sai, {b["metadata"]["name"] for b in ca.values()})

    def test_thieu_chu_thich_va_sai_dinh_dang(self):
        b = pam("RoleBinding", "pam-k", "debug-app", ns="core")
        b["metadata"]["annotations"]["vt.io/het-han"] = "ngày mai"
        del b["metadata"]["annotations"]["vt.io/pam-yeu-cau"]
        kq = self.chay(rbs=[b])
        ct = kq[0][0]["chi_tiet"]
        self.assertIn("thiếu chú thích vt.io/pam-yeu-cau", ct)
        self.assertIn("không phải RFC 3339", ct)

    def test_chi_so_va_ecs(self):
        kq = self.chay([binding("ClusterRoleBinding", "tao-tay", "cluster-admin", [{"kind": "User", "name": "oidc:nam"}])],
                       [pam("RoleBinding", "pam-yc-1", "debug-app", ns="core")])
        cs = qr.chi_so(kq[0], kq[1], 0, NOW)
        self.assertIn('vt_pam_cua_sau_rbac{loai="cua-sau",kind="ClusterRoleBinding",namespace="",binding="tao-tay",role="cluster-admin"} 1', cs)
        self.assertIn('vt_pam_binding_dang_mo{role="debug-app"} 1', cs)
        e = json.loads(qr.ecs("pam.cua-sau-rbac", "failure", "x", NOW, ten="tao-tay"))
        self.assertEqual(e["event"]["dataset"], "security")
        self.assertEqual(e["event"]["kind"], "alert")
        self.assertEqual(e["@timestamp"], "2026-10-23T08:00:00.000Z")


class BaoCaoCsdl(unittest.TestCase):
    DONG = ["app|thua-ke-dich-vu|bao_admin|svc_task", "t1|thua-ke-dich-vu|bao_admin|svc_okr",
            "t1|doc-truc-tiep|alice|task", "t2|doc-truc-tiep|alice|okr", "t1|pha-kinh-dang-mo|v-x|2026-10-23 09:00:00+00", ""]

    def test_gop_theo_cum(self):
        p = {(x["loai"], x["vai_tro"]): x for x in qc.doc_ket_qua(self.DONG)}
        self.assertEqual(p[("doc-truc-tiep", "alice")]["csdl"], ["t1", "t2"])
        self.assertEqual(p[("thua-ke-dich-vu", "bao_admin")]["chi_tiet"], ["svc_okr", "svc_task"])

    def test_ngoai_le_con_han_va_het_han(self):
        ph = qc.doc_ket_qua(self.DONG)
        nl = [{"vaiTro": "bao_admin", "denNgay": "2026-12-31"}, {"vaiTro": "alice", "denNgay": "2026-10-22"}]
        giu, bo = qc.loc_ngoai_le(ph, nl, dt.date(2026, 10, 23))
        self.assertEqual({p["vai_tro"] for p in bo}, {"bao_admin"})
        self.assertIn("alice", {p["vai_tro"] for p in giu})
        giu, bo = qc.loc_ngoai_le(ph, nl, dt.date(2027, 1, 1))
        self.assertEqual(bo, [])

    def test_chi_so(self):
        ph = qc.doc_ket_qua(self.DONG)
        giu, bo = qc.loc_ngoai_le(ph, [{"vaiTro": "bao_admin", "denNgay": "2026-12-31"}], dt.date(2026, 10, 23))
        cs = qc.chi_so("pg-core", giu, bo, qc.doc_ket_noi("12|11|1\n"), 3, NOW)
        self.assertIn('vt_pam_cua_sau_csdl{cluster="pg-core",loai="doc-truc-tiep",vai_tro="alice"} 1', cs)
        self.assertNotIn('vai_tro="bao_admin"} 1\nvt_pam_ngoai', cs)
        self.assertIn('vt_pam_ngoai_le_csdl{cluster="pg-core",vai_tro="bao_admin"} 1', cs)
        self.assertIn('vt_pam_pha_kinh_dang_mo{cluster="pg-core"} 1', cs)
        self.assertIn('vt_pam_ket_noi_quan_tri{cluster="pg-core",vai_tro="tong"} 12', cs)
        self.assertNotIn("pha-kinh-dang-mo", "\n".join(l for l in cs.splitlines() if l.startswith("vt_pam_cua_sau_csdl")))
        self.assertEqual(qc.doc_ket_noi(""), (0, 0, 0))


def tim_pg_bin():
    if os.environ.get("PG_BIN"):
        return os.environ["PG_BIN"]
    ung = sorted(glob.glob("/usr/lib/postgresql/*/bin/initdb"), key=lambda p: int(p.split("/")[4]))
    if ung and int(ung[-1].split("/")[4]) >= 16:
        return os.path.dirname(ung[-1])
    return None


@unittest.skipUnless(tim_pg_bin() and os.path.exists(os.path.join(tim_pg_bin() or "", "postgres")),
                     "không có PostgreSQL ≥ 16 cài sẵn (đặt PG_BIN); phần SQL chỉ kiểm tĩnh")
class SqlThat(unittest.TestCase):
    """PostgreSQL tạm trong thư mục tạm, chỉ socket Unix (listen_addresses rỗng), xoá khi xong."""

    @classmethod
    def setUpClass(cls):
        cls.bin = tim_pg_bin()
        cls.tmp = tempfile.mkdtemp(prefix="pam-pg-")
        cls.chay_bang = []
        if os.geteuid() == 0:
            # initdb từ chối chạy bằng root: chạy bằng tài khoản postgres của gói cài đặt.
            cls.chay_bang = ["runuser", "-u", "postgres", "--"]
            shutil.chown(cls.tmp, "postgres")
        cls.data = os.path.join(cls.tmp, "data")
        cls.sock = cls.tmp
        cls.port = "55432"
        cls.lenh([f"{cls.bin}/initdb", "-D", cls.data, "-A", "trust", "-U", "postgres", "--no-instructions", "-E", "UTF8"])
        cls.lenh([f"{cls.bin}/pg_ctl", "-D", cls.data, "-w", "-l", os.path.join(cls.tmp, "log"),
                  "-o", f"-c listen_addresses='' -k {cls.sock} -p {cls.port}", "start"])
        cls.psql("-c", "CREATE DATABASE thu")
        cls.psql("-d", "thu", "-f", os.path.join(CHART, "tests", "sql", "du-lieu-mau.sql"))
        for _ in range(2):  # chạy lại được
            cls.psql("-d", "thu", "-f", os.path.join(CHART, "files", "sql", "vai-tro-dba.sql"))
        # Người dùng động như OpenBao tạo, chạy bằng bao_admin (không phải siêu người dùng), câu lấy từ script OpenBao.
        cls.cau = {}
        for nhom, so in (("dba_ops", "2"), ("dba_pha_kinh_doc", "1"), ("svc_task", "1")):
            ra = subprocess.run(["bash", os.path.join(CHART, "files", "openbao", "cau-hinh-pam.sh"), "--in-sql", nhom, so],
                                capture_output=True, text=True, check=True).stdout.splitlines()
            cls.cau[nhom] = ra
        for ten, nhom, gio in (("v-pam-pg-core-dba-ops-x1", "dba_ops", 4), ("v-pam-pg-core-pha-kinh-doc-x2", "dba_pha_kinh_doc", 2),
                               ("v-tok-pg-core-mission-x3", "svc_task", 24)):
            het = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(hours=gio, minutes=-1)).isoformat()
            sql = cls.cau[nhom][0].replace("{{name}}", ten).replace("{{password}}", "khong-dung").replace("{{expiration}}", het)
            cls.psql("-U", "bao_admin", "-d", "thu", "-c", sql)
        # Phá kính cấp tay không có hạn: phải bị phát hiện.
        cls.psql("-d", "thu", "-c", "CREATE ROLE nam LOGIN IN ROLE dba_pha_kinh_doc")

    @classmethod
    def lenh(cls, args, **kw):
        return subprocess.run(cls.chay_bang + args, capture_output=True, text=True, check=True, cwd=cls.tmp, **kw)

    @classmethod
    def psql(cls, *args, nguoi=None):
        a = [f"{cls.bin}/psql", "-X", "-q", "-v", "ON_ERROR_STOP=1", "-h", cls.sock, "-p", cls.port]
        if "-U" not in args:
            a += ["-U", "postgres"]
        return subprocess.run(a + list(args), capture_output=True, text=True, check=True).stdout

    @classmethod
    def tearDownClass(cls):
        subprocess.run(cls.chay_bang + [f"{cls.bin}/pg_ctl", "-D", cls.data, "-m", "immediate", "stop"], capture_output=True)
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def quet(self):
        ra = self.psql("-d", "thu", "-v", "schemas=^(task|okr)$", "-v", "dich_vu=^(svc|ro|mig|cdc)_[a-z_]+$", "-v", "dong=^v-",
                       "-v", "pha_kinh=dba_pha_kinh_doc", "-v", "dba=dba_ops", "-v", "han=2 hours 10 minutes",
                       "-v", "sieu=postgres", "-f", os.path.join(CHART, "files", "sql", "quet-vai-tro.sql"))
        return {tuple(d.split("|")[:2]) for d in ra.splitlines() if d}

    def test_quet_tim_dung_cua_sau(self):
        self.assertEqual(self.quet(), {
            ("cong-khai", "PUBLIC"),
            ("doc-truc-tiep", "alice"), ("doc-truc-tiep", "gina"), ("doc-truc-tiep", "nhom_x"),
            ("thua-ke-dich-vu", "bob"), ("thua-ke-dich-vu", "bao_admin"), ("thua-ke-dich-vu", "ivan"),
            ("thua-ke-khac", "carol"),
            ("doc-toan-bo", "dave"),
            ("sieu-nguoi-dung", "erin"), ("sieu-nguoi-dung", "frank"),
            ("pha-kinh-qua-han", "nam"),
            ("pha-kinh-dang-mo", "v-pam-pg-core-pha-kinh-doc-x2"),
        })

    def test_dba_ops_khong_doc_duoc_pha_kinh_doc_duoc(self):
        with self.assertRaises(subprocess.CalledProcessError) as e:
            self.psql("-U", "v-pam-pg-core-dba-ops-x1", "-d", "thu", "-c", "SELECT count(*) FROM task.giao_viec")
        self.assertIn("permission denied", e.exception.stderr)
        # dba_ops vẫn đọc được thống kê và cấu hình (pg_monitor).
        self.assertIn("t", self.psql("-U", "v-pam-pg-core-dba-ops-x1", "-d", "thu", "-At", "-c",
                                     "SELECT pg_has_role('pg_monitor', 'USAGE')"))
        self.assertEqual(self.psql("-U", "v-pam-pg-core-pha-kinh-doc-x2", "-d", "thu", "-At", "-c",
                                   "SELECT count(*) FROM task.giao_viec").strip(), "1")

    def test_dem_ket_noi_quan_tri_va_thu_hoi(self):
        a = [f"{self.bin}/psql", "-X", "-h", self.sock, "-p", self.port, "-U", "v-pam-pg-core-dba-ops-x1", "-d", "thu",
             "-c", "SELECT pg_sleep(30)"]
        p = subprocess.Popen(a, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            for _ in range(50):
                ra = self.psql("-d", "thu", "-v", "dba=dba_ops", "-v", "pha_kinh=dba_pha_kinh_doc",
                               "-f", os.path.join(CHART, "files", "sql", "ket-noi-quan-tri.sql")).strip()
                if ra.startswith("1|"):
                    break
                time.sleep(0.1)
            self.assertEqual(ra, "1|1|0")
            # Câu thu hồi của OpenBao chạy bằng bao_admin: ngắt phiên đang mở rồi xoá vai trò.
            thu_hoi = self.cau["dba_ops"][1].replace("{{name}}", "v-pam-pg-core-dba-ops-x1")
            self.psql("-U", "bao_admin", "-d", "thu", "-c", thu_hoi)
            p.wait(timeout=10)
            self.assertEqual(self.psql("-d", "thu", "-At", "-c",
                                       "SELECT count(*) FROM pg_roles WHERE rolname = 'v-pam-pg-core-dba-ops-x1'").strip(), "0")
        finally:
            if p.poll() is None:
                p.kill()

    def test_cronjob_dau_cuoi_bang_pam_quet(self):
        """quet-csdl.sh rồi day_chi_so_csdl.py như hai container của CronJob, đăng nhập bằng vai trò pam_quet."""
        kq = os.path.join(self.tmp, "ket-qua")
        os.makedirs(kq, exist_ok=True)
        env = dict(os.environ, PGHOST=self.sock, PGPORT=self.port, PGUSER="pam_quet", PGSSLMODE="disable",
                   PATH=f"{self.bin}:{os.environ['PATH']}", SQL=os.path.join(CHART, "files", "sql"), KET_QUA=kq,
                   SCHEMAS="^(task|okr)$", DICH_VU="^(svc|ro|mig|cdc)_[a-z_]+$", DONG="^v-", PHA_KINH="dba_pha_kinh_doc",
                   DBA="dba_ops", HAN="2 hours 10 minutes", SIEU="postgres")
        subprocess.run(["sh", os.path.join(CHART, "files", "scripts", "quet-csdl.sh")], env=env, check=True, capture_output=True, text=True)
        env.update(CUM="pg-thu", NGOAI_LE=json.dumps([{"vaiTro": "bao_admin", "denNgay": "2999-12-31"}]), NGAN_SACH="10")
        env.pop("PUSHGATEWAY", None)
        ra = subprocess.run([sys.executable, "-I", os.path.join(CHART, "files", "scripts", "day_chi_so_csdl.py")], env=env,
                            check=True, capture_output=True, text=True).stdout
        self.assertIn('vt_pam_cua_sau_csdl{cluster="pg-thu",loai="doc-truc-tiep",vai_tro="alice"} 1', ra)
        self.assertIn('vt_pam_ngoai_le_csdl{cluster="pg-thu",vai_tro="bao_admin"} 1', ra)
        self.assertNotIn('loai="thua-ke-dich-vu",vai_tro="bao_admin"', ra)
        # CSDL postgres, template không cho nối bị bỏ; thu và postgres được quét.
        self.assertIn('vt_pam_quet_csdl_so_csdl{cluster="pg-thu"} 2', ra)
        su_kien = [json.loads(l) for l in ra.splitlines() if l.startswith("{")]
        self.assertIn("pam.doc-truc-tiep-csdl", {e["event"]["action"] for e in su_kien})

    def test_bao_admin_chi_admin_tren_vai_tro_pam(self):
        ra = self.psql("-d", "thu", "-At", "-c",
                       "SELECT r.rolname, m.admin_option, m.inherit_option, m.set_option FROM pg_auth_members m "
                       "JOIN pg_roles r ON r.oid = m.roleid JOIN pg_roles u ON u.oid = m.member "
                       "WHERE u.rolname = 'bao_admin' AND r.rolname IN ('dba_ops', 'dba_pha_kinh_doc', 'pam_quet') ORDER BY 1")
        self.assertEqual(ra.split(), ["dba_ops|t|f|f", "dba_pha_kinh_doc|t|f|f", "pam_quet|t|f|f"])


if __name__ == "__main__":
    unittest.main()
