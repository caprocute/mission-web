"""Kiểm thử chạy thật vtctl (INF-26) trên stack của dung-stack.sh.

vtctl thật đăng nhập bằng luồng thiết bị với Keycloak vận hành giả lập (có bước lên aal2), gọi tenant-registry thật
trên PostgreSQL thật, gọi kubectl với tài khoản người trực có vai trò operate-app của chart security-cell trên
kube-apiserver thật, và gọi degrade-controller thật. Kiểm từng lệnh của runbook tenant, cell, consumer, degrade:
kết quả trên hệ thống, lệnh ghi không có --confirm không đổi gì và không ghi kiểm toán, mỗi lệnh ghi đã thực hiện
để lại đúng một bản ghi kiểm toán, bốn mắt của chuyển cell, thiếu quyền Kubernetes thì dừng và hướng dẫn PAM.
Không chạy dlq replay, dlq skip, consumer lag (cần Kafka; kiểm bằng go test với kfake) và Job outbox, org.
Chạy: python3 kiem_thu_vtctl.py (sau khi source $W/moi-truong.sh).
"""
import json, os, re, stat, subprocess, sys, time, urllib.request

W = os.environ["W"]
P = {k: os.environ[k] for k in ("P_DC", "P_REG", "P_OIDC", "P_PG")}
GOC = os.path.abspath(os.path.join(os.path.dirname(__file__), "../../../.."))
TENANT, G1, G2 = "0192f1a2-3b4c-7d5e-8f60-718293a4b5c6", "0192f1a2-0000-7c00-8a00-00000000c0b1", "0192f1a2-0000-7c00-8a00-00000000c0b2"
AUDIT = os.path.join(W, "audit.jsonl")
KQ = {"dat": 0, "hong": 0}
open(os.path.join(W, "vtctl.yaml"), "w").write(f"""cellsDir: {GOC}/deploy/cells
auditLog: {AUDIT}
tokenDir: {W}/vtctl-token
environments:
  van-hanh:
    issuer: http://127.0.0.1:{P['P_OIDC']}/realms/platform-ops
    clientId: vtctl
    registryURL: http://127.0.0.1:{P['P_REG']}
    cells:
      g1:
        apiURL: http://127.0.0.1:1
        kubeContext: truc
        namespaces: {{jobs: workers, edge: edge}}
        degradeURL: http://127.0.0.1:{P['P_DC']}
        kafka: {{bootstrap: ["127.0.0.1:1"], plaintext: true}}
      g2:
        apiURL: http://127.0.0.1:1
        kubeContext: khach
        namespaces: {{jobs: workers, edge: edge}}
        degradeURL: http://127.0.0.1:{P['P_DC']}
        kafka: {{bootstrap: ["127.0.0.1:1"], plaintext: true}}
""")
ENV = dict(os.environ, VTCTL_CONFIG=os.path.join(W, "vtctl.yaml"))


def ok(dk, mo_ta):
    KQ["dat" if dk else "hong"] += 1
    print(("  [ĐẠT] " if dk else "  [HỎNG] ") + mo_ta, flush=True)
    return dk


def audit():
    return [json.loads(x) for x in open(AUDIT, encoding="utf-8")] if os.path.exists(AUDIT) else []


def v(*a, cell=None):
    """Chạy vtctl; trả (mã thoát, đầu ra, các bản ghi kiểm toán mới)."""
    truoc = len(audit())
    args = ["vtctl", "-e", "van-hanh"] + (["-c", cell] if cell else []) + list(a)
    r = subprocess.run(args, env=ENV, capture_output=True, text=True, timeout=60)
    return r.returncode, r.stdout + r.stderr, audit()[truoc:]


def sql(q):
    return subprocess.check_output(["psql", "-h", "127.0.0.1", "-p", P["P_PG"], "-U", "postgres", "-d", "mission", "-Atc", q], text=True).strip()


def kj(*a):
    return json.loads(subprocess.check_output(["kubectl", *a, "-o", "json"]))


def muc():
    return json.load(urllib.request.urlopen(f"http://127.0.0.1:{P['P_DC']}/level", timeout=5))


def trang_thai():
    return sql(f"SELECT status FROM registry.tenant WHERE id = '{TENANT}'")


def nguoi_dung(sub, ten):
    req = urllib.request.Request(f"http://127.0.0.1:{P['P_OIDC']}/_dieu-khien/nguoi-dung", method="POST",
                                 data=json.dumps({"sub": sub, "name": ten, "roles": ["platform_admin"]}).encode())
    urllib.request.urlopen(req, timeout=5)


def mot_ban_ghi(moi, lenh, ket_qua):
    return len(moi) == 1 and moi[0]["command"] == lenh and moi[0]["outcome"] == ket_qua and moi[0]["actor"]


print("== Đăng nhập")
rc, out, moi = v("login")
ok(rc == 0 and "aal1" in out and not moi, "login luồng thiết bị, bậc aal1, không ghi kiểm toán")
rc, out, _ = v("whoami")
ok(rc == 0 and "platform_admin" in out and "qtnt-mot" in out, "whoami: tài khoản và vai trò từ token")

print("== cell list, tenant status (đọc)")
rc, out, moi = v("cell", "list")
ok(rc == 0 and all(c in out for c in ("CELL-G1", "CELL-G2", "CELL-HN")) and not moi, "cell list đọc registry thật (bước lên aal2 khi cần)")
rc, out, moi = v("tenant", "status", TENANT)
ok(rc == 0 and "status: DANG_VAN_HANH" in out and "CELL-G1" in out and not moi, "tenant status: DANG_VAN_HANH ở CELL-G1")

print("== cell move: đề nghị, bốn mắt, theo dõi, huỷ")
rc, out, moi = v("cell", "move", "request", TENANT, "--target-cell", G2, "--model", "BRIDGE", "--data-region", "HOALAC", "--reason", "QĐ-THU/2026")
ok(rc == 0 and "Chạy thử" in out and not moi,
   "move request không --confirm: chỉ in tóm tắt, không tạo lệnh, không ghi kiểm toán")
rc, out, moi = v("cell", "move", "request", TENANT, "--target-cell", G2, "--model", "BRIDGE", "--data-region", "HOALAC", "--reason", "QĐ-THU/2026", "--confirm")
m = re.search(r"^id: (\S+)", out, re.M)
mid = m.group(1) if m else ""
ok(rc == 0 and "status: CHO_DUYET" in out and mot_ban_ghi(moi, "cell move request", "THANH_CONG"),
   f"move request --confirm: lệnh {mid[:8]}… CHO_DUYET, một bản ghi kiểm toán")
ok(moi and moi[0].get("idempotencyKey"), "bản ghi kiểm toán có Idempotency-Key")
rc, out, moi = v("cell", "move", "approve", TENANT, mid, "--confirm")
ok(rc == 1 and "REGISTRY-403-SAME-APPROVER" in out and mot_ban_ghi(moi, "cell move approve", "THAT_BAI"),
   "người đề nghị tự duyệt bị máy chủ từ chối (bốn mắt), ghi kiểm toán THAT_BAI")
rc, out, moi = v("cell", "move", "status", TENANT, mid)
ok(rc == 0 and "status: CHO_DUYET" in out and not moi, "move status: vẫn CHO_DUYET")
rc, out, moi = v("cell", "move", "cancel", TENANT, mid, "--comment", "thử vận hành", "--confirm")
ok(rc == 0 and "status: DA_HUY" in out and mot_ban_ghi(moi, "cell move cancel", "THANH_CONG"), "move cancel --confirm: DA_HUY")

print("== tenant suspend, activate")
rc, out, moi = v("tenant", "suspend", TENANT, "--reason", "CV-12/2026 hết hạn hợp đồng")
ok(rc == 0 and trang_thai() == "DANG_VAN_HANH" and not moi, "suspend không --confirm: tenant vẫn DANG_VAN_HANH")
rc, out, moi = v("tenant", "suspend", TENANT, "--reason", "CV-12/2026 hết hạn hợp đồng", "--confirm")
ok(rc == 0 and trang_thai() == "TAM_NGUNG" and mot_ban_ghi(moi, "tenant suspend", "THANH_CONG"), "suspend --confirm: TAM_NGUNG")
ok("CV-12/2026" in sql(f"SELECT string_agg(reason, '|') FROM registry.tenant_status_log WHERE tenant_id = '{TENANT}'"),
   "lý do ghi registry.tenant_status_log")
rc, out, moi = v("tenant", "activate", TENANT, "--reason", "PL-3 gia hạn", "--confirm")
ok(rc == 1 and "CONFIG-422-READINESS-FAILED" in out and trang_thai() == "TAM_NGUNG" and mot_ban_ghi(moi, "tenant activate", "THAT_BAI"),
   "activate --confirm tới saga thật, bị chặn ở kiểm tra tổng thể (stack không có tên miền, IdP): 422, giữ TAM_NGUNG")
ok("readinessChecks.DOMAIN: Chưa khai báo tên miền cho tenant" in out and "readinessChecks.IDP" in out,
   "vtctl in từng mục kiểm tra chưa đạt, thông báo tên miền đúng thực trạng")

print("== consumer restart (kubectl với vai trò operate-app)")
anno = lambda: kj("-n", "workers", "get", "deploy", "scoring-service")["spec"]["template"]["metadata"].get("annotations") or {}
rc, out, moi = v("consumer", "restart", "--deployment", "scoring-service", "-n", "workers", "--reason", "PHIEU-0001", cell="g1")
ok(rc == 0 and "kubectl.kubernetes.io/restartedAt" not in anno() and not moi, "restart không --confirm: Deployment không đổi")
rc, out, moi = v("consumer", "restart", "--deployment", "scoring-service", "-n", "workers", "--reason", "PHIEU-0001", "--wait", "0", "--confirm", cell="g1")
ok(rc == 0 and "kubectl.kubernetes.io/restartedAt" in anno() and mot_ban_ghi(moi, "consumer restart", "THANH_CONG"),
   "restart --confirm bằng tài khoản người trực: template được gắn restartedAt")
rc, out, moi = v("consumer", "restart", "--deployment", "scoring-service", "-n", "workers", "--reason", "PHIEU-0002", "--wait", "0", "--confirm", cell="g2")
ok(rc == 1 and "PAM" in out and not moi,
   "tài khoản không có operate-app: dừng trước khi ghi, hướng dẫn xin quyền qua PAM")

print("== degrade (bộ điều khiển suy giảm thật)")
rc, out, moi = v("degrade", "status", cell="g1")
ok(rc == 0 and "level: 0" in out and not moi, "degrade status: mức 0")
rc, out, moi = v("degrade", "set", "2", "--reason", "PHIEU-0001", cell="g1")
ok(rc == 0 and "Chạy thử" in out and muc()["level"] == 0 and not moi, "degrade set không --confirm: mức không đổi")
rc, out, moi = v("degrade", "set", "2", "--reason", "CPU 85%", "--confirm", cell="g1")
ok(rc == 0 and muc()["level"] == 2 and mot_ban_ghi(moi, "degrade set", "THANH_CONG"), "degrade set 2 --confirm bằng tài khoản người trực: mức 2")
nhan = lambda: {i["metadata"]["labels"]["vt.io/degrade-level"] for i in kj("get", "apisixpluginconfigs", "-A", "-l", "vt.io/degrade-managed=true")["items"]}
ok(nhan() == {"2"}, "mọi ApisixPluginConfig mang nhãn mức 2")
rc, out, moi = v("degrade", "pin", "3", "--for", "2m", "--reason", "CPU primary 95%", "--confirm", cell="g1")
s = muc()
ok(rc == 0 and s["level"] == 3 and s["manual"] == 3 and s["pin"]["until"] and mot_ban_ghi(moi, "degrade pin", "THANH_CONG"),
   "degrade pin 3 --for 2m: mức 3, có hạn ghim")
rc, out, moi = v("degrade", "clear", "--reason", "CPU về 60%", "--confirm", cell="g1")
s = muc()
ok(rc == 0 and s["manual"] is None and s["level"] == 3 and s.get("holdDownUntil") and mot_ban_ghi(moi, "degrade clear", "THANH_CONG"),
   "degrade clear: bỏ ghim, giữ mức trong thời gian chống dao động")
rc, out, moi = v("degrade", "set", "1", "--reason", "PHIEU-0003", "--confirm", cell="g2")
ok(rc == 1 and "PAM" in out and muc()["manual"] is None and mot_ban_ghi(moi, "degrade set", "THAT_BAI"),
   "tài khoản không đọc được token bộ điều khiển: dừng, hướng dẫn PAM, mức không đổi")

print("== tenant terminate (bản ghi cuối)")
rc, out, moi = v("tenant", "terminate", TENANT, "--reason", "QĐ-99/2026 chấm dứt", "--handover-ref", "BB-01/2026", "--confirm")
ok(rc == 0 and trang_thai() == "DA_CHAM_DUT" and mot_ban_ghi(moi, "tenant terminate", "THANH_CONG"), "terminate --confirm: DA_CHAM_DUT")

print("== Tệp kiểm toán")
st = os.stat(AUDIT)
ok(stat.S_IMODE(st.st_mode) == 0o600, "audit.jsonl quyền 0600")
a = audit()
ok(all(x.get("actor") and x.get("env") == "van-hanh" and x.get("time") for x in a) and "token" not in json.dumps(a).lower().replace("idempotency", ""),
   f"{len(a)} bản ghi đủ người chạy, môi trường, thời điểm; không chứa token")

print(f"\nTổng: {KQ['dat']} đạt, {KQ['hong']} hỏng")
sys.exit(1 if KQ["hong"] else 0)
