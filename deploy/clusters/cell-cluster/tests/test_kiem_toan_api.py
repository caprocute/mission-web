#!/usr/bin/env python3
"""Kiểm tĩnh chính sách kiểm toán và cờ API server của khuôn cell-cluster (INF-19; TL-04 mục 9.3, SEC-27, SEC-30,
SEC-31). Chính sách phải trùng từng quy tắc, đúng thứ tự với mẫu của TL-04; KubeadmControlPlane phải mang cờ
anonymous-auth=false, profiling=false, encryption-provider-config KMS v2 (OpenBao Transit), TLS ≥ 1.2.

Dùng: python3 -m unittest discover -s deploy/clusters/cell-cluster/tests  (cần helm trong PATH hoặc HELM=<tệp>)
"""
import os, shutil, subprocess, unittest
import yaml

GOC = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
HELM = os.environ.get("HELM") or shutil.which("helm") or "/root/bin/helm"
V = ["-f", os.path.join(GOC, "cell-g4-hl/values.yaml"), "-f", os.path.join(GOC, "cell-g4-hl/values-mau-ha-tang.yaml")]

# Mẫu chính sách của TL-04 mục 9.3 "Nhật ký kiểm toán API (SEC-30)".
TL04 = [
    {"level": "None", "users": ["system:kube-proxy", "system:apiserver"], "verbs": ["get", "list", "watch"]},
    {"level": "None", "resources": [{"group": "coordination.k8s.io", "resources": ["leases"]}]},
    {"level": "Metadata", "resources": [{"group": "", "resources": ["secrets", "configmaps"]}]},
    {"level": "RequestResponse", "resources": [{"group": "rbac.authorization.k8s.io"},
                                               {"group": "", "resources": ["pods/exec", "pods/attach", "pods/portforward", "serviceaccounts/token"]}]},
    {"level": "RequestResponse", "resources": [{"group": "postgresql.cnpg.io"}, {"group": "kafka.strimzi.io"}],
     "verbs": ["create", "update", "patch", "delete"]},
    {"level": "Metadata", "verbs": ["create", "update", "patch", "delete", "deletecollection"]},
    {"level": "None"},
]


def render(*extra):
    return subprocess.run([HELM, "template", "x", os.path.join(GOC, "cell-cluster"), *V, *extra], capture_output=True, text=True)


def muc(policy, group, resource, verb, user="oidc:u"):
    """Mức kiểm toán theo luật khớp đầu tiên (mô phỏng thuật toán của API server cho các trường dùng ở đây)."""
    for r in policy["rules"]:
        if "users" in r and user not in r["users"]:
            continue
        if "verbs" in r and verb not in r["verbs"]:
            continue
        if "resources" in r and not any(g["group"] == group and ("resources" not in g or resource in g["resources"]) for g in r["resources"]):
            continue
        return r["level"]
    return "None"


class KiemToan(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with open(os.path.join(GOC, "cell-cluster/files/audit-policy.yaml")) as f:
            cls.p = yaml.safe_load(f)
        r = render()
        if r.returncode:
            raise AssertionError(r.stderr)
        cls.docs = [d for d in yaml.safe_load_all(r.stdout) if d]
        cls.kcp = [d for d in cls.docs if d["kind"] == "KubeadmControlPlane"][0]["spec"]["kubeadmConfigSpec"]

    def test_chinh_sach_trung_tl04(self):
        self.assertEqual(self.p["apiVersion"], "audit.k8s.io/v1")
        self.assertEqual(self.p["kind"], "Policy")
        self.assertEqual(self.p["omitStages"], ["RequestReceived"])
        self.assertEqual(self.p["rules"], TL04)

    def test_muc_theo_thao_tac(self):
        p = self.p
        self.assertEqual(muc(p, "", "pods/exec", "create"), "RequestResponse")
        self.assertEqual(muc(p, "", "pods/portforward", "create"), "RequestResponse")
        self.assertEqual(muc(p, "rbac.authorization.k8s.io", "clusterrolebindings", "create"), "RequestResponse")
        self.assertEqual(muc(p, "", "secrets", "get"), "Metadata")
        self.assertEqual(muc(p, "", "secrets", "update"), "Metadata", "Secret không bao giờ ở RequestResponse")
        self.assertEqual(muc(p, "postgresql.cnpg.io", "clusters", "patch"), "RequestResponse")
        self.assertEqual(muc(p, "postgresql.cnpg.io", "clusters", "get"), "None")
        self.assertEqual(muc(p, "apps", "deployments", "patch"), "Metadata")
        self.assertEqual(muc(p, "", "pods", "list", user="system:kube-proxy"), "None")
        self.assertEqual(muc(p, "coordination.k8s.io", "leases", "update"), "None")
        self.assertEqual(muc(p, "", "pods", "get"), "None")

    def test_tep_dat_len_master(self):
        tep = {f["path"]: f for f in self.kcp["files"]}
        self.assertEqual(yaml.safe_load(tep["/etc/kubernetes/audit-policy.yaml"]["content"]), self.p)
        enc = yaml.safe_load(tep["/etc/kubernetes/encryption-config.yaml"]["content"])
        prov = enc["resources"][0]["providers"]
        self.assertEqual(enc["resources"][0]["resources"], ["secrets"])
        self.assertEqual(prov[0]["kms"]["apiVersion"], "v2")
        self.assertEqual(prov[0]["kms"]["name"], "openbao-transit")
        self.assertEqual(prov[0]["kms"]["endpoint"], "unix:///var/run/kms/openbao.sock")
        self.assertEqual(prov[-1], {"identity": {}}, "identity phải đặt cuối")
        for f in tep.values():
            self.assertEqual(f["permissions"], "0600")

    def test_co_api_server(self):
        a = self.kcp["clusterConfiguration"]["apiServer"]["extraArgs"]
        mong = {"anonymous-auth": "false", "profiling": "false", "authorization-mode": "Node,RBAC",
                "enable-admission-plugins": "NodeRestriction", "audit-policy-file": "/etc/kubernetes/audit-policy.yaml",
                "audit-log-path": "/var/log/kubernetes/audit/audit.log", "audit-log-maxage": "7", "audit-log-maxbackup": "10",
                "audit-log-maxsize": "200", "encryption-provider-config": "/etc/kubernetes/encryption-config.yaml",
                "service-account-lookup": "true", "oidc-client-id": "kubernetes", "oidc-username-claim": "preferred_username",
                "oidc-groups-claim": "groups"}
        for k, v in mong.items():
            self.assertEqual(a.get(k), v, k)
        self.assertIn(a["tls-min-version"], ("VersionTLS12", "VersionTLS13"))
        self.assertTrue(a["oidc-issuer-url"].startswith("https://") and a["oidc-issuer-url"].endswith("/realms/platform-ops"))
        self.assertEqual(self.kcp["clusterConfiguration"]["controllerManager"]["extraArgs"]["profiling"], "false")
        self.assertEqual(self.kcp["clusterConfiguration"]["scheduler"]["extraArgs"]["profiling"], "false")
        vol = {x["name"]: x for x in self.kcp["clusterConfiguration"]["apiServer"]["extraVolumes"]}
        self.assertTrue(vol["audit-policy"]["readOnly"] and not vol["audit-log"]["readOnly"])
        self.assertEqual(vol["kms-socket"]["mountPath"], "/var/run/kms")

    def test_tu_choi_tls_duoi_12(self):
        r = render("--set", "apiServer.tlsMinVersion=VersionTLS11")
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("TLS ≥ 1.2", r.stderr)


if __name__ == "__main__":
    unittest.main()
