#!/usr/bin/env python3
"""Kiểm tĩnh chart security-cell (INF-19) bằng unittest trên bản render helm template.

Khẳng định các tính chất then chốt theo TL-04 mục 9.3, 9.5: Pod Security, RBAC (view-app không đọc Secret, vai trò
PAM không có binding thường trực), quy tắc Falco riêng, Falcosidekick, Tetragon chặn ptrace ở secure, kube-bench
hằng tuần, kured theo khung giờ và cửa sổ đóng băng, thăm dò cổng ra từng namespace, cảnh báo Prometheus.

Dùng: python3 -m unittest discover -s deploy/charts/security-cell/tests  (cần helm trong PATH hoặc HELM=<tệp>)
"""
import os, re, shutil, subprocess, unittest
import yaml

CHART = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
CELL_BASE = os.path.abspath(os.path.join(CHART, "..", "cell-base"))
HELM = os.environ.get("HELM") or shutil.which("helm") or "/root/bin/helm"


def render(chart=CHART, *args):
    out = subprocess.run([HELM, "template", "t", chart, *args], capture_output=True, text=True)
    return out


def docs(*args, chart=CHART):
    r = render(chart, *args)
    if r.returncode != 0:
        raise AssertionError(r.stderr)
    return [d for d in yaml.safe_load_all(r.stdout) if d]


def tim(ds, kind, name=None, ns=None):
    return [d for d in ds if d["kind"] == kind and (name is None or d["metadata"]["name"] == name)
            and (ns is None or d["metadata"].get("namespace") == ns)]


class CoSo(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.d = docs()
        with open(os.path.join(CHART, "values.yaml")) as f:
            cls.v = yaml.safe_load(f)
        cls.giam_sat = cls.v["zones"]["app"] + cls.v["zones"]["data"] + cls.v["zones"]["secure"] + cls.v["zones"]["edge"]


class PodSecurity(CoSo):
    def test_namespace_dac_quyen_co_ghi_danh(self):
        ns = tim(self.d, "Namespace", "platform-privileged")[0]["metadata"]["labels"]
        self.assertEqual(ns["pod-security.kubernetes.io/enforce"], "privileged")
        self.assertEqual(ns["pod-security.kubernetes.io/audit"], "restricted")
        self.assertEqual(ns["pod-security.kubernetes.io/warn"], "restricted")

    def test_namespace_ung_dung_du_lieu_restricted(self):
        """Mọi namespace vùng ứng dụng, dữ liệu, bảo mật của cell-base gắn enforce restricted (SEC-26)."""
        nss = {d["metadata"]["name"]: d["metadata"]["labels"] for d in tim(docs(chart=CELL_BASE), "Namespace")}
        for n in self.giam_sat:
            self.assertEqual(nss[n]["pod-security.kubernetes.io/enforce"], "restricted", n)

    def test_pod_ngoai_vung_dac_quyen_chay_restricted(self):
        """Pod của chart đặt ngoài platform-privileged đều không root, chỉ đọc, bỏ mọi capability."""
        for d in self.d:
            if d["kind"] not in ("Deployment", "CronJob", "DaemonSet"):
                continue
            if d["metadata"]["namespace"] == "platform-privileged":
                continue
            spec = d["spec"]["jobTemplate"]["spec"]["template"]["spec"] if d["kind"] == "CronJob" else d["spec"]["template"]["spec"]
            self.assertTrue(spec["securityContext"]["runAsNonRoot"], d["metadata"])
            self.assertEqual(spec["securityContext"]["seccompProfile"]["type"], "RuntimeDefault")
            for c in spec["containers"]:
                sc = c["securityContext"]
                self.assertTrue(sc["readOnlyRootFilesystem"] and not sc["allowPrivilegeEscalation"], c["name"])
                self.assertEqual(sc["capabilities"]["drop"], ["ALL"])
                self.assertRegex(c["image"], r"^harbor\.vt\.local/.+@sha256:[a-f0-9]{64}$")


class Rbac(CoSo):
    def quy_tac(self, ten):
        return tim(self.d, "ClusterRole", ten)[0]["rules"]

    def test_du_sau_vai_tro(self):
        for r in ("view-app", "operate-app", "debug-app", "view-data", "operate-data", "platform-admin"):
            self.assertTrue(tim(self.d, "ClusterRole", r), r)

    def test_view_app_khong_doc_secret(self):
        for ten in ("view-app", "operate-app", "debug-app", "view-data", "operate-data"):
            for r in self.quy_tac(ten):
                if "" in r["apiGroups"] or "*" in r["apiGroups"]:
                    self.assertNotIn("secrets", r["resources"], ten)
                    self.assertNotIn("*", r["resources"], ten)
                self.assertNotIn("*", r["verbs"], ten)

    def test_view_app_chi_doc(self):
        for r in self.quy_tac("view-app"):
            self.assertEqual(sorted(r["verbs"]), ["get", "list", "watch"])
            for res in r["resources"]:
                self.assertNotIn(res, ("pods/exec", "pods/portforward", "pods/attach"))

    def test_operate_app_them_xoa_pod_va_scale(self):
        ex = [r for r in self.quy_tac("operate-app") if "delete" in r["verbs"] or "patch" in r["verbs"]]
        res = {x for r in ex for x in r["resources"]}
        self.assertIn("pods", res)
        self.assertIn("deployments/scale", res)
        self.assertNotIn("pods/exec", {x for r in self.quy_tac("operate-app") for x in r["resources"]})

    def test_debug_app_them_exec_portforward(self):
        res = {x for r in self.quy_tac("debug-app") for x in r["resources"]}
        self.assertTrue({"pods/exec", "pods/portforward"} <= res)

    def test_operate_data_chi_cnpg_strimzi(self):
        nhom = {g for r in self.quy_tac("operate-data") if set(r["verbs"]) & {"create", "patch", "delete"} for g in r["apiGroups"]}
        self.assertTrue(nhom <= {"postgresql.cnpg.io", "kafka.strimzi.io", "core.strimzi.io", ""}, nhom)

    def test_platform_admin_toan_quyen_khong_binding_thuong_truc(self):
        self.assertEqual(self.quy_tac("platform-admin")[0], {"apiGroups": ["*"], "resources": ["*"], "verbs": ["*"]})
        rb = tim(self.d, "RoleBinding") + tim(self.d, "ClusterRoleBinding")
        dich = {b["roleRef"]["name"] for b in rb}
        for ten in ("debug-app", "operate-data", "platform-admin", "cluster-admin"):
            self.assertNotIn(ten, dich)

    def test_binding_thuong_truc_dung_vung_va_tien_to_oidc(self):
        for b in tim(self.d, "RoleBinding"):
            r = b["roleRef"]["name"]
            if r == "kured":
                continue
            vung = self.v["zones"]["data"] if r == "view-data" else self.v["zones"]["app"]
            self.assertIn(b["metadata"]["namespace"], vung, b["metadata"])
            for s in b["subjects"]:
                self.assertEqual(s["kind"], "Group")
                self.assertTrue(s["name"].startswith("oidc:"), s)

    def test_tu_choi_binding_thuong_truc_vai_tro_pam(self):
        r = render(CHART, "--set-json", 'rbac.bindings={"debug-app":["x"]}')
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("PAM", r.stderr)


class Falco(CoSo):
    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        cm = tim(cls.d, "ConfigMap", "falco")[0]["data"]
        cls.cfg = yaml.safe_load(cm["falco.yaml"])
        cls.rules = yaml.safe_load(cm["vt-rules.yaml"])

    def test_ebpf_hien_dai_va_dau_ra(self):
        self.assertEqual(self.cfg["engine"]["kind"], "modern_ebpf")
        self.assertTrue(self.cfg["json_output"])
        self.assertTrue(self.cfg["http_output"]["enabled"])
        self.assertIn("falcosidekick.platform-privileged.svc:2801", self.cfg["http_output"]["url"])

    def test_bon_quy_tac_rieng(self):
        r = {x["rule"]: x for x in self.rules if "rule" in x}
        mong = {"shell": "CRITICAL", "/tmp": "ERROR", "cổng ra": "ERROR", "/var/run/secrets": "ERROR"}
        for khoa, muc in mong.items():
            ten = [n for n in r if khoa in n]
            self.assertEqual(len(ten), 1, khoa)
            self.assertEqual(r[ten[0]]["priority"], muc, khoa)
            for k in ("desc", "condition", "output", "tags"):
                self.assertIn(k, r[ten[0]])
        self.assertIn("k8s.ns.name != egress", r[[n for n in r if "cổng ra" in n][0]]["condition"])
        self.assertIn("proc.vpid != 1", r[[n for n in r if "secrets" in n][0]]["condition"])

    def test_cau_truc_quy_tac_hop_le(self):
        """Thay cho falco --validate khi không có ảnh Falco: mọi macro, danh sách vt_ được khai trước khi dùng."""
        da_khai = set()
        for x in self.rules:
            self.assertEqual(len({"rule", "macro", "list", "required_engine_version"} & set(x)), 1, x)
            if "list" in x:
                self.assertIsInstance(x["items"], list)
                da_khai.add(x["list"])
            if "macro" in x or "rule" in x:
                for ten in re.findall(r"\bvt_[a-z_]+\b", x["condition"]):
                    self.assertIn(ten, da_khai, f"{ten} dùng trước khi khai ({x.get('rule') or x.get('macro')})")
            if "macro" in x:
                da_khai.add(x["macro"])
            if "rule" in x:
                self.assertIn(x["priority"], ("EMERGENCY", "ALERT", "CRITICAL", "ERROR", "WARNING", "NOTICE", "INFORMATIONAL", "DEBUG"))

    def test_danh_sach_namespace_theo_vung(self):
        l = {x["list"]: x["items"] for x in self.rules if "list" in x}
        self.assertEqual(l["vt_namespace_giam_sat"], self.giam_sat)
        self.assertNotIn("egress", l["vt_namespace_giam_sat"])

    def test_daemonset_it_dac_quyen_moi_node(self):
        ds = tim(self.d, "DaemonSet", "falco")[0]["spec"]["template"]["spec"]
        c = ds["containers"][0]
        self.assertFalse(c["securityContext"]["privileged"])
        self.assertEqual(set(c["securityContext"]["capabilities"]["add"]), {"BPF", "PERFMON", "SYS_RESOURCE", "SYS_PTRACE"})
        self.assertIn({"operator": "Exists"}, ds["tolerations"])
        self.assertFalse(ds["automountServiceAccountToken"])


class Falcosidekick(CoSo):
    def test_sang_topic_an_ninh_va_soc(self):
        cfg = yaml.safe_load(tim(self.d, "ConfigMap", "falcosidekick")[0]["data"]["config.yaml"])
        self.assertTrue(cfg["otlp"]["logs"]["endpoint"].startswith("https://otel-siem.observability.svc"))
        self.assertIn("OTEL_EXPORTER_OTLP_LOGS_CLIENT_CERTIFICATE", cfg["otlp"]["logs"]["extraenvvars"])
        self.assertEqual(cfg["webhook"]["minimumpriority"], "critical")
        self.assertNotIn("address", cfg["webhook"], "địa chỉ webhook SOC không được nằm trong ConfigMap")
        dep = tim(self.d, "Deployment", "falcosidekick")[0]["spec"]["template"]["spec"]["containers"][0]
        env = {e["name"]: e for e in dep["env"]}
        self.assertIn("secretKeyRef", env["WEBHOOK_ADDRESS"]["valueFrom"])
        self.assertEqual(env["HTTPS_PROXY"]["value"], "http://egress-gateway.egress.svc:443")


class Tetragon(CoSo):
    def test_chan_ptrace_o_secure(self):
        tp = tim(self.d, "TracingPolicyNamespaced", "chan-ptrace")
        self.assertEqual([t["metadata"]["namespace"] for t in tp], ["secure"])
        k = tp[0]["spec"]["kprobes"][0]
        self.assertEqual(k["call"], "sys_ptrace")
        self.assertTrue(k["syscall"])
        self.assertEqual(k["selectors"][0]["matchActions"][0], {"action": "Override", "argError": -1})


class KubeBench(CoSo):
    def test_hang_tuan_moi_nhom_node(self):
        cj = {c["metadata"]["name"]: c for c in tim(self.d, "CronJob") if c["metadata"]["name"].startswith("kube-bench")}
        self.assertEqual(set(cj), {"kube-bench-master", "kube-bench-node"})
        for c in cj.values():
            self.assertRegex(c["spec"]["schedule"], r"^\S+ \S+ \* \* [0-6]$")
            js = c["spec"]["jobTemplate"]["spec"]
            p = js["template"]["spec"]
            self.assertEqual(js["completions"], js["parallelism"])
            self.assertEqual(p["affinity"]["podAntiAffinity"]["requiredDuringSchedulingIgnoredDuringExecution"][0]["topologyKey"], "kubernetes.io/hostname")
            env = {e["name"]: e.get("value") for e in p["containers"][0]["env"]}
            self.assertTrue(env["PUSHGATEWAY"].startswith("http://pushgateway.observability.svc"))
            self.assertIn("--json", p["initContainers"][0]["command"])


class Kured(CoSo):
    def args(self, *extra):
        ds = tim(docs(*extra), "DaemonSet", "kured")[0]
        return ds["spec"]["template"]["spec"]["containers"][0]["args"]

    def test_khung_gio_va_pdb(self):
        a = self.args()
        for x in ("--start-time=1am", "--end-time=5am", "--time-zone=Asia/Ho_Chi_Minh", "--concurrency=1", "--force-reboot=false"):
            self.assertIn(x, a)

    def test_cua_so_dong_bang(self):
        a = self.args()
        self.assertTrue(any(x.startswith("--prometheus-url=") for x in a))
        self.assertIn("--alert-filter-match-only=true", a)
        rx = [x.split("=", 1)[1] for x in a if x.startswith("--alert-filter-regexp=")][0]
        self.assertRegex("VtCuaSoDongBang", rx)
        rules = {r["alert"] for d in tim(self.d, "PrometheusRule") for g in d["spec"]["groups"] for r in g["rules"]}
        self.assertIn("VtCuaSoDongBang", rules)

    def test_bo_qua_dong_bang_chi_khi_dat_co(self):
        self.assertFalse(any(x.startswith("--prometheus-url=") for x in self.args("--set", "kured.boQuaDongBang=true")))


class ThamDoCongRa(CoSo):
    def test_moi_namespace_mot_cronjob(self):
        cj = tim(self.d, "CronJob", "tham-do-cong-ra")
        self.assertEqual(sorted(c["metadata"]["namespace"] for c in cj), sorted(self.giam_sat))
        for c in cj:
            env = {e["name"]: e["value"] for e in c["spec"]["jobTemplate"]["spec"]["template"]["spec"]["containers"][0]["env"]}
            for muc in env["TARGETS"].split():
                may = muc.split("=", 1)[1].rsplit(":", 1)[0]
                # Chỉ địa chỉ dành cho tài liệu (RFC 5737) hoặc tên miền ví dụ (RFC 2606).
                self.assertRegex(may, r"^(192\.0\.2\.|198\.51\.100\.|203\.0\.113\.|example\.(com|net|org)$)")

    def test_chi_them_duong_toi_pushgateway(self):
        for np in tim(self.d, "NetworkPolicy", "egress-probe-to-pushgateway"):
            e = np["spec"]["egress"]
            self.assertEqual(len(e), 1)
            self.assertEqual(e[0]["ports"], [{"protocol": "TCP", "port": 9091}])


class CanhBao(CoSo):
    def test_luat_toi_thieu(self):
        r = {x["alert"]: x for d in tim(self.d, "PrometheusRule") for g in d["spec"]["groups"] for x in g["rules"]}
        self.assertIn('kube_bench_checks{result="fail"}', r["KubeBenchFail"]["expr"])
        self.assertTrue(r["KubeBenchFail"]["expr"].strip().endswith("> 0"))
        self.assertIn("vt_egress_probe_reachable", r["EgressProbeLeak"]["expr"])
        self.assertEqual(r["EgressProbeLeak"]["labels"]["doi"], "soc")


class BiMat(CoSo):
    def test_khong_co_bi_mat_hay_dia_chi_that(self):
        txt = render().stdout
        self.assertNotRegex(txt, r"(?i)(password|passwd|secret_key|BEGIN [A-Z ]*PRIVATE KEY)\s*[:=]\s*\S")
        for url in re.findall(r"https?://([a-z0-9.-]+)", txt):
            self.assertRegex(url, r"(\.svc|\.vt\.local|^example\.com)$", url)


if __name__ == "__main__":
    unittest.main()
