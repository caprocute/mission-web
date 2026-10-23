#!/usr/bin/env python3
"""Làm mới bí mật của mục tiêu Warpgate (INF-27; SEC-42, SEC-58). CronJob warpgate-lam-moi, chỉ thư viện chuẩn.

1. Token kubectl: với mỗi ServiceAccount <namespace>/warpgate-<vai trò>, xin token ngắn hạn bằng TokenRequest
   (spec.expirationSeconds = tokenTtlGiay; không có Secret token lâu dài) rồi ghi vào KV v2 kv/<kv> trường token, mà
   mục tiêu kubectl của Warpgate tham chiếu bằng secret://. ServiceAccount không có binding thường trực: token chỉ có
   quyền khi broker PAM đã gán binding có thời hạn.
2. Người dùng psql: với mỗi cụm, khi KV chưa có hoặc lease còn dưới nguongGiay giây, đọc người dùng động mới
   database/creds/<cụm>-dba-ops (IN ROLE dba_ops, 4 giờ), ghi mật khẩu vào KV và đổi username của mục tiêu
   psql-<cụm> qua API quản trị Warpgate (username của mục tiêu không nhận tham chiếu secret://). Người dùng cũ không bị
   thu hồi sớm: phiên đang mở chạy tới khi lease của nó hết.

Mỗi việc một dòng JSON dạng ECS trên stdout; chỉ số đẩy lên Pushgateway (PUSHGATEWAY, bỏ trống thì không đẩy).
Biến: CAU_HINH (mặc định /config/lam-moi.json), K8S_API, K8S_TOKEN, K8S_CA, BAO_JWT, BAO_CA, WARPGATE_TOKEN_FILE,
WARPGATE_CA, PUSHGATEWAY. Mã thoát 1 khi có việc lỗi (các việc khác vẫn chạy).
"""
import datetime as dt
import json
import os
import ssl
import sys
import urllib.error
import urllib.request


def bay_gio():
    return dt.datetime.now(dt.timezone.utc)


def ecs(hanh_dong, ket_qua, thong_diep, **them):
    print(json.dumps({"@timestamp": bay_gio().strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z",
                      "event": {"dataset": "security", "module": "pam", "action": hanh_dong, "outcome": ket_qua,
                                "kind": "alert" if ket_qua == "failure" else "event", "category": ["iam"]},
                      "service": {"name": "warpgate-lam-moi"}, "message": thong_diep, "pam": them},
                     ensure_ascii=False), flush=True)


def ctx(ca):
    return ssl.create_default_context(cafile=ca) if ca else ssl.create_default_context()


def goi(method, url, body=None, headers=None, ca=None):
    rq = urllib.request.Request(url, method=method, data=None if body is None else json.dumps(body).encode(),
                                headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(rq, context=ctx(ca), timeout=20) as r:
            noi_dung = r.read()
            return r.status, (json.loads(noi_dung) if noi_dung else None)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(errors="replace")


def doc(tep):
    with open(tep, encoding="utf-8") as f:
        return f.read().strip()


def thoi_diem(s):
    try:
        return dt.datetime.fromisoformat(str(s).replace("Z", "+00:00"))
    except ValueError:
        return None


class LamMoi:
    def __init__(self, cfg, env):
        self.cfg = cfg
        self.k8s = env.get("K8S_API", "https://kubernetes.default.svc")
        self.k8s_token = env.get("K8S_TOKEN", "/var/run/secrets/kubernetes.io/serviceaccount/token")
        self.k8s_ca = env.get("K8S_CA", "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt")
        self.bao = cfg["openbao"]["address"].rstrip("/")
        self.bao_jwt = env.get("BAO_JWT", "/var/run/secrets/openbao/token")
        self.bao_ca = env.get("BAO_CA", "/openbao-ca/ca.crt")
        self.wg = cfg["warpgate"].rstrip("/")
        self.wg_token = env.get("WARPGATE_TOKEN_FILE", "/warpgate-api/token")
        self.wg_ca = env.get("WARPGATE_CA", "/warpgate-tls/ca.crt")
        self.kv = cfg["openbao"].get("kvMount", "kv")
        self.bao_token = None
        self.het_han = {}
        self.loi = 0

    def dang_nhap_bao(self):
        o = self.cfg["openbao"]
        st, r = goi("POST", f'{self.bao}/v1/auth/{o["authMount"]}/login', {"role": o["role"], "jwt": doc(self.bao_jwt)}, ca=self.bao_ca)
        if st != 200:
            raise RuntimeError(f"đăng nhập OpenBao lỗi {st}: {r}")
        self.bao_token = r["auth"]["client_token"]

    def bao_goi(self, method, path, body=None):
        return goi(method, f"{self.bao}/v1/{path}", body, {"X-Vault-Token": self.bao_token}, ca=self.bao_ca)

    def kv_doc(self, path):
        st, r = self.bao_goi("GET", f"{self.kv}/data/{path}")
        if st == 404:
            return None
        if st != 200:
            raise RuntimeError(f"đọc kv {path} lỗi {st}: {r}")
        return r["data"]["data"]

    def kv_ghi(self, path, data):
        st, r = self.bao_goi("POST", f"{self.kv}/data/{path}", {"data": data})
        if st not in (200, 204):
            raise RuntimeError(f"ghi kv {path} lỗi {st}: {r}")

    def token_kubectl(self, muc):
        ns, sa = self.cfg["namespace"], muc["serviceAccount"]
        st, r = goi("POST", f"{self.k8s}/api/v1/namespaces/{ns}/serviceaccounts/{sa}/token",
                    {"apiVersion": "authentication.k8s.io/v1", "kind": "TokenRequest",
                     "spec": {"expirationSeconds": int(self.cfg["tokenTtlGiay"])}},
                    {"Authorization": f"Bearer {doc(self.k8s_token)}"}, ca=self.k8s_ca)
        if st != 201:
            raise RuntimeError(f"TokenRequest {ns}/{sa} lỗi {st}: {r}")
        het = r["status"]["expirationTimestamp"]
        self.kv_ghi(muc["kv"], {"token": r["status"]["token"], "het_han": het})
        self.het_han[muc["kv"]] = thoi_diem(het)
        ecs("pam.lam-moi-token-kubectl", "success", f"đã làm mới token {ns}/{sa}", service_account=sa, het_han=het)

    def wg_goi(self, method, path, body=None):
        return goi(method, f"{self.wg}/@warpgate/admin/api{path}", body, {"X-Warpgate-Token": doc(self.wg_token)}, ca=self.wg_ca)

    def doi_username(self, ten, username, mat_khau_ref):
        st, ds = self.wg_goi("GET", "/targets")
        if st != 200:
            raise RuntimeError(f"liệt kê mục tiêu Warpgate lỗi {st}: {ds}")
        t = next((x for x in ds if x.get("name") == ten), None)
        if t is None:
            raise RuntimeError(f"Warpgate chưa có mục tiêu {ten} (nhập ConfigMap warpgate-nhap trước)")
        opt = dict(t["options"])
        opt["username"] = username
        opt["auth"] = {"kind": "Password", "password": mat_khau_ref}
        than = {k: t[k] for k in ("name", "description", "rate_limit_bytes_per_second", "group_id",
                                  "ticket_max_duration_seconds", "ticket_requests_disabled", "ticket_require_approval",
                                  "require_approval", "ticket_max_uses") if t.get(k) is not None}
        than["options"] = opt
        st, r = self.wg_goi("PUT", f'/targets/{t["id"]}', than)
        if st != 200:
            raise RuntimeError(f"cập nhật mục tiêu {ten} lỗi {st}: {r}")

    def nguoi_dung_psql(self, muc, kho):
        cu = self.kv_doc(muc["kv"])
        het_cu = thoi_diem((cu or {}).get("het_han"))
        if het_cu and (het_cu - bay_gio()).total_seconds() > int(self.cfg["nguongGiay"]):
            self.het_han[muc["kv"]] = het_cu
            ecs("pam.lam-moi-psql", "success", f"người dùng {muc['cum']} còn hạn, giữ nguyên", cum=muc["cum"], het_han=cu["het_han"])
            return
        st, r = self.bao_goi("GET", muc["creds"])
        if st != 200:
            raise RuntimeError(f"đọc {muc['creds']} lỗi {st}: {r}")
        het = (bay_gio() + dt.timedelta(seconds=int(r["lease_duration"]))).strftime("%Y-%m-%dT%H:%M:%SZ")
        self.kv_ghi(muc["kv"], {"username": r["data"]["username"], "password": r["data"]["password"],
                                "lease_id": r["lease_id"], "het_han": het})
        self.doi_username(muc["mucTieu"], r["data"]["username"], f'secret://{kho}/{self.kv}/{muc["kv"]}#password')
        self.het_han[muc["kv"]] = thoi_diem(het)
        ecs("pam.lam-moi-psql", "success", f"đã cấp người dùng dba_ops mới cho {muc['cum']}", cum=muc["cum"],
            username=r["data"]["username"], lease_id=r["lease_id"], het_han=het)

    def chay(self, kho="openbao-cell"):
        try:
            self.dang_nhap_bao()
        except (OSError, RuntimeError, KeyError) as e:
            ecs("pam.lam-moi", "failure", str(e))
            self.loi += 1
            return
        for muc in self.cfg.get("kubectl", []):
            try:
                self.token_kubectl(muc)
            except (OSError, RuntimeError, KeyError) as e:
                ecs("pam.lam-moi-token-kubectl", "failure", str(e), service_account=muc["serviceAccount"])
                self.loi += 1
        for muc in self.cfg.get("psql", []):
            try:
                self.nguoi_dung_psql(muc, kho)
            except (OSError, RuntimeError, KeyError, TypeError) as e:
                ecs("pam.lam-moi-psql", "failure", str(e), cum=muc["cum"])
                self.loi += 1

    def chi_so(self):
        d = ["# HELP vt_pam_warpgate_bi_mat_het_han_timestamp_seconds Thời điểm hết hạn bí mật hiện hành của mục tiêu Warpgate.",
             "# TYPE vt_pam_warpgate_bi_mat_het_han_timestamp_seconds gauge"]
        for k, t in sorted(self.het_han.items()):
            if t:
                d.append(f'vt_pam_warpgate_bi_mat_het_han_timestamp_seconds{{kv="{k}"}} {int(t.timestamp())}')
        d += ["# HELP vt_pam_warpgate_lam_moi_loi Số việc lỗi ở lần chạy gần nhất.",
              "# TYPE vt_pam_warpgate_lam_moi_loi gauge", f"vt_pam_warpgate_lam_moi_loi {self.loi}",
              "# HELP vt_pam_warpgate_lam_moi_last_run_timestamp_seconds Thời điểm lần chạy gần nhất kết thúc.",
              "# TYPE vt_pam_warpgate_lam_moi_last_run_timestamp_seconds gauge",
              f"vt_pam_warpgate_lam_moi_last_run_timestamp_seconds {int(bay_gio().timestamp())}"]
        if self.loi == 0:
            d += ["# HELP vt_pam_warpgate_lam_moi_last_success_timestamp_seconds Lần chạy gần nhất không có việc lỗi.",
                  "# TYPE vt_pam_warpgate_lam_moi_last_success_timestamp_seconds gauge",
                  f"vt_pam_warpgate_lam_moi_last_success_timestamp_seconds {int(bay_gio().timestamp())}"]
        return "\n".join(d) + "\n"


def day_chi_so(url, noi_dung):
    rq = urllib.request.Request(f"{url.rstrip('/')}/metrics/job/warpgate-lam-moi", data=noi_dung.encode(), method="POST",
                                headers={"Content-Type": "text/plain; version=0.0.4"})
    with urllib.request.urlopen(rq, timeout=10):
        pass


def main():
    cfg = json.loads(doc(os.environ.get("CAU_HINH", "/config/lam-moi.json")))
    lm = LamMoi(cfg, os.environ)
    lm.chay(cfg.get("kho", "openbao-cell"))
    if os.environ.get("PUSHGATEWAY"):
        try:
            day_chi_so(os.environ["PUSHGATEWAY"], lm.chi_so())
        except OSError as e:
            ecs("pam.lam-moi", "failure", f"không đẩy được chỉ số: {e}")
            lm.loi += 1
    return 1 if lm.loi else 0


if __name__ == "__main__":
    sys.exit(main())
