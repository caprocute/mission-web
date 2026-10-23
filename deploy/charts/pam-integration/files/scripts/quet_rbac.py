#!/usr/bin/env python3
"""Quét RBAC "không có cửa sau" (INF-27; TL-04 mục 9.3 RBAC SEC-29, SEC-42).

Đọc mọi ClusterRoleBinding, RoleBinding và ClusterRole của cụm bằng token ServiceAccount, tìm:
  - cua-sau: binding tới vai trò đặc quyền không mang nhãn PAM và không thuộc danh sách hệ thống được phép;
  - sai-hop-dong: binding mang nhãn PAM nhưng thiếu hoặc sai chú thích (yêu cầu, người duyệt, thời hạn > 4 giờ,
    chủ thể không phải một người dùng OIDC, vai trò hay vùng namespace không đúng, platform-admin thiếu hai người duyệt);
  - qua-han: binding PAM đã quá vt.io/het-han. Khi thuHoiQuaHan bật, binding PAM quá hạn cộng ân hạn bị xoá (chỉ thu
    hồi, không bao giờ cấp quyền); binding cửa sau chỉ báo động, không tự xoá.
Kết quả: mỗi phát hiện một dòng JSON dạng ECS trên stdout (event.dataset security, Fluent Bit đưa vào sys.audit.v1
rồi otel-siem chuyển sys.siem.v1), chỉ số Prometheus đẩy lên Pushgateway.

Chỉ dùng thư viện chuẩn. Cấu hình: tệp JSON ở biến CAU_HINH (mặc định /config/cau-hinh.json).
"""
import datetime as dt
import json
import os
import re
import ssl
import sys
import urllib.request

SA = "/var/run/secrets/kubernetes.io/serviceaccount"
CHI_SO_NHAY_CAM = {"pods/exec", "pods/attach", "pods/portforward", "nodes/proxy", "secrets"}
DONG_TU_LEO_QUYEN = {"escalate", "bind", "impersonate"}


def thoi_luong(s):
    """'4h', '10m', '90s' → timedelta."""
    m = re.fullmatch(r"(\d+)([hms])", str(s).strip())
    if not m:
        raise ValueError(f"thời lượng không hợp lệ: {s}")
    n, u = int(m.group(1)), m.group(2)
    return dt.timedelta(hours=n) if u == "h" else dt.timedelta(minutes=n) if u == "m" else dt.timedelta(seconds=n)


def thoi_diem(s):
    """RFC 3339 → datetime có múi giờ; None nếu không đọc được."""
    if not isinstance(s, str) or not s:
        return None
    try:
        t = dt.datetime.fromisoformat(s.replace("Z", "+00:00"))
    except ValueError:
        return None
    return t if t.tzinfo else None


def quy_tac_dac_quyen(rules):
    """ClusterRole có quy tắc cho phép leo quyền hoặc truy cập nhạy cảm."""
    for r in rules or []:
        verbs = set(r.get("verbs", []))
        res = set(r.get("resources", []))
        if "*" in verbs and ("*" in res or r.get("nonResourceURLs")):
            return True
        if verbs & DONG_TU_LEO_QUYEN:
            return True
        if res & CHI_SO_NHAY_CAM and verbs & {"*", "create", "get", "list", "watch"}:
            # pods/exec, pods/attach, pods/portforward cần create hoặc get; secrets đọc bằng get, list, watch.
            return True
        if "*" in res and ("" in r.get("apiGroups", []) or "*" in r.get("apiGroups", [])):
            return True
    return False


def vai_tro_dac_quyen(clusterroles, ds_cau_hinh):
    ten = set(ds_cau_hinh)
    for cr in clusterroles:
        md = cr.get("metadata", {})
        if (md.get("labels") or {}).get("vt.io/cap-phat") == "pam-4h" or quy_tac_dac_quyen(cr.get("rules")):
            ten.add(md.get("name"))
    return ten


def chu_the(s, ns_binding=None):
    ns = s.get("namespace") or (ns_binding if s.get("kind") == "ServiceAccount" else "")
    return f'{s.get("kind")}:{ns or ""}/{s.get("name")}'


def duoc_phep(b, cho_phep):
    ten = b["metadata"]["name"]
    cts = [chu_the(s, b["metadata"].get("namespace")) for s in b.get("subjects") or []]
    for muc in cho_phep:
        if re.search(muc["ten"], ten) and all(re.search(muc["chuThe"], c) for c in cts):
            return True
    return False


def kiem_hop_dong(b, kind, hd, now):
    """Lỗi hợp đồng của một binding mang nhãn PAM; trả (danh sách lỗi, hết hạn hay không, thời điểm hết hạn)."""
    md = b["metadata"]
    ct = md.get("annotations") or {}
    k = hd["chuThich"]
    loi = []
    vai_tro = b["roleRef"]["name"]
    if not md["name"].startswith(hd["tienToTen"]):
        loi.append(f'tên không bắt đầu bằng {hd["tienToTen"]}')
    if vai_tro not in hd["vaiTroPam"]:
        loi.append(f"vai trò {vai_tro} không cấp qua PAM")
    if kind == "ClusterRoleBinding" and vai_tro not in hd["vaiTroToanCum"]:
        loi.append(f"{vai_tro} chỉ được gán bằng RoleBinding theo namespace")
    if kind == "RoleBinding":
        if vai_tro in hd["vaiTroToanCum"]:
            loi.append(f"{vai_tro} phải là ClusterRoleBinding")
        vung = hd["vungNamespace"].get(vai_tro)
        if vung is not None and md.get("namespace") not in vung:
            loi.append(f'namespace {md.get("namespace")} ngoài vùng của {vai_tro}')
    sub = b.get("subjects") or []
    if len(sub) != 1 or sub[0].get("kind") != "User" or not str(sub[0].get("name", "")).startswith("oidc:"):
        loi.append("chủ thể phải là đúng một người dùng OIDC (oidc:<tên>)")
    for khoa in ("yeuCau", "nguoiYeuCau", "nguoiDuyet", "capLuc", "hetHan"):
        if not ct.get(k[khoa]):
            loi.append(f"thiếu chú thích {k[khoa]}")
    duyet = [x.strip() for x in ct.get(k["nguoiDuyet"], "").split(",") if x.strip()]
    can = hd["soNguoiDuyet"].get(vai_tro, 1)
    if len(set(duyet)) < can:
        loi.append(f"{vai_tro} cần {can} người duyệt khác nhau, có {len(set(duyet))}")
    if ct.get(k["nguoiYeuCau"]) and ct.get(k["nguoiYeuCau"]) in duyet:
        loi.append("người yêu cầu tự duyệt")
    cap = thoi_diem(ct.get(k["capLuc"]))
    het = thoi_diem(ct.get(k["hetHan"]))
    if ct.get(k["capLuc"]) and cap is None:
        loi.append(f'{k["capLuc"]} không phải RFC 3339')
    if ct.get(k["hetHan"]) and het is None:
        loi.append(f'{k["hetHan"]} không phải RFC 3339')
    if cap and het:
        if het <= cap:
            loi.append("hết hạn trước thời điểm cấp")
        elif het - cap > thoi_luong(hd["thoiHanToiDa"]):
            loi.append(f'thời hạn {het - cap} vượt {hd["thoiHanToiDa"]}')
        tao = thoi_diem(md.get("creationTimestamp"))
        if tao and abs(tao - cap) > thoi_luong(hd["lechDongHo"]):
            loi.append(f"{k['capLuc']} lệch creationTimestamp quá {hd['lechDongHo']}")
        if tao and het - tao > thoi_luong(hd["thoiHanToiDa"]) + thoi_luong(hd["lechDongHo"]):
            loi.append(f"thời hạn tính từ creationTimestamp vượt {hd['thoiHanToiDa']}")
    return loi, (het is not None and het <= now), het


def danh_gia(crbs, rbs, clusterroles, cfg, now):
    """Phân loại toàn bộ binding. Trả (phát hiện, binding PAM đang hiệu lực, binding cần thu hồi)."""
    hd = cfg["hopDong"]
    q = cfg["quetRbac"]
    dac_quyen = vai_tro_dac_quyen(clusterroles, q["vaiTroDacQuyen"])
    nhan_k, nhan_v = next(iter(hd["nhan"].items()))
    phat_hien, dang_mo, thu_hoi = [], [], []
    an_han = thoi_luong(q["anHan"])
    for kind, ds in (("ClusterRoleBinding", crbs), ("RoleBinding", rbs)):
        for b in ds:
            md = b["metadata"]
            vai_tro = b["roleRef"]["name"]
            # RoleBinding tới Role (không phải ClusterRole) chỉ xét khi trùng tên vai trò đặc quyền.
            co_dac_quyen = vai_tro in dac_quyen
            la_pam = (md.get("labels") or {}).get(nhan_k) == nhan_v
            goc = {"kind": kind, "namespace": md.get("namespace", ""), "ten": md["name"], "vai_tro": vai_tro,
                   "chu_the": [chu_the(s, md.get("namespace")) for s in b.get("subjects") or []]}
            if la_pam:
                loi, het, han = kiem_hop_dong(b, kind, hd, now)
                if loi:
                    phat_hien.append({**goc, "loai": "sai-hop-dong", "chi_tiet": "; ".join(loi)})
                if het:
                    phat_hien.append({**goc, "loai": "qua-han", "chi_tiet": f"hết hạn lúc {han.isoformat()}"})
                    if q.get("thuHoiQuaHan") and now - han >= an_han:
                        thu_hoi.append({**goc, "het_han": han.isoformat()})
                else:
                    dang_mo.append(goc)
            elif co_dac_quyen and not duoc_phep(b, q["choPhep"]):
                phat_hien.append({**goc, "loai": "cua-sau", "chi_tiet": f"binding tới {vai_tro} không do PAM tạo"})
    return phat_hien, dang_mo, thu_hoi


def nhan_prom(**kv):
    return ",".join(f'{k}="{str(v).replace(chr(92), chr(92) * 2).replace(chr(34), chr(92) + chr(34))}"' for k, v in kv.items())


def chi_so(phat_hien, dang_mo, so_thu_hoi, now):
    d = ["# HELP vt_pam_cua_sau_rbac 1 với mỗi binding đặc quyền không do PAM tạo, sai hợp đồng hoặc quá hạn.",
         "# TYPE vt_pam_cua_sau_rbac gauge"]
    for p in phat_hien:
        d.append(f'vt_pam_cua_sau_rbac{{{nhan_prom(loai=p["loai"], kind=p["kind"], namespace=p["namespace"], binding=p["ten"], role=p["vai_tro"])}}} 1')
    d += ["# HELP vt_pam_binding_dang_mo Số binding PAM còn hiệu lực theo vai trò.", "# TYPE vt_pam_binding_dang_mo gauge"]
    dem = {}
    for b in dang_mo:
        dem[b["vai_tro"]] = dem.get(b["vai_tro"], 0) + 1
    for r, n in sorted(dem.items()):
        d.append(f'vt_pam_binding_dang_mo{{{nhan_prom(role=r)}}} {n}')
    d += ["# HELP vt_pam_thu_hoi_lan_quet Số binding PAM quá hạn bị thu hồi ở lần quét này.",
          "# TYPE vt_pam_thu_hoi_lan_quet gauge", f"vt_pam_thu_hoi_lan_quet {so_thu_hoi}",
          "# HELP vt_pam_quet_last_run_timestamp_seconds Thời điểm lần quét chạy xong.",
          "# TYPE vt_pam_quet_last_run_timestamp_seconds gauge",
          f"vt_pam_quet_last_run_timestamp_seconds {int(now.timestamp())}"]
    return "\n".join(d) + "\n"


def ecs(hanh_dong, ket_qua, thong_diep, now, **them):
    return json.dumps({"@timestamp": now.strftime("%Y-%m-%dT%H:%M:%S.%fZ")[:-4] + "Z",
                       "event": {"dataset": "security", "module": "pam", "kind": "alert" if ket_qua == "failure" else "event",
                                 "category": ["iam", "configuration"], "action": hanh_dong, "outcome": ket_qua},
                       "service": {"name": "pam-quet-rbac"}, "message": thong_diep, "pam": them}, ensure_ascii=False)


class Api:
    def __init__(self, may=None, token=None, ca=None):
        may = may or "https://{}:{}".format(os.environ["KUBERNETES_SERVICE_HOST"], os.environ["KUBERNETES_SERVICE_PORT"])
        self.may = may
        self.token = token or open(f"{SA}/token").read().strip()
        self.ctx = ssl.create_default_context(cafile=ca or f"{SA}/ca.crt")

    def goi(self, phuong_thuc, duong_dan):
        req = urllib.request.Request(self.may + duong_dan, method=phuong_thuc,
                                     headers={"Authorization": f"Bearer {self.token}", "Accept": "application/json"})
        with urllib.request.urlopen(req, context=self.ctx, timeout=30) as r:
            return json.load(r)

    def ds(self, duong_dan):
        muc, tiep = [], ""
        while True:
            r = self.goi("GET", duong_dan + "?limit=500" + (f"&continue={tiep}" if tiep else ""))
            muc += r.get("items", [])
            tiep = r.get("metadata", {}).get("continue")
            if not tiep:
                return muc


def day_chi_so(noi_dung, url, cell):
    req = urllib.request.Request(f"{url}/metrics/job/pam-quet-rbac/cell/{cell}", data=noi_dung.encode(), method="PUT",
                                 headers={"Content-Type": "text/plain; version=0.0.4"})
    urllib.request.urlopen(req, timeout=15).close()


def main():
    cfg = json.load(open(os.environ.get("CAU_HINH", "/config/cau-hinh.json")))
    now = dt.datetime.now(dt.timezone.utc)
    api = Api()
    g = "/apis/rbac.authorization.k8s.io/v1"
    phat_hien, dang_mo, thu_hoi = danh_gia(api.ds(f"{g}/clusterrolebindings"), api.ds(f"{g}/rolebindings"),
                                          api.ds(f"{g}/clusterroles"), cfg, now)
    da_thu_hoi = 0
    for b in thu_hoi:
        duong = (f'{g}/clusterrolebindings/{b["ten"]}' if b["kind"] == "ClusterRoleBinding"
                 else f'{g}/namespaces/{b["namespace"]}/rolebindings/{b["ten"]}')
        try:
            api.goi("DELETE", duong)
            da_thu_hoi += 1
            print(ecs("pam.thu-hoi", "success", f'Thu hồi binding PAM quá hạn {b["kind"]} {b["namespace"]}/{b["ten"]}', now, **b))
        except Exception as e:  # noqa: BLE001 - ghi lỗi rồi quét tiếp
            print(ecs("pam.thu-hoi", "failure", f'Không thu hồi được {b["ten"]}: {e}', now, **b))
    for p in phat_hien:
        print(ecs(f'pam.{p["loai"]}-rbac', "failure", f'{p["kind"]} {p["namespace"]}/{p["ten"]} → {p["vai_tro"]}: {p["chi_tiet"]}', now, **p))
    print(ecs("pam.quet-rbac", "success", f"Quét xong: {len(phat_hien)} phát hiện, {len(dang_mo)} binding PAM hiệu lực, "
              f"{da_thu_hoi} thu hồi", now, phat_hien=len(phat_hien), dang_mo=len(dang_mo), thu_hoi=da_thu_hoi))
    noi_dung = chi_so(phat_hien, dang_mo, da_thu_hoi, now)
    if os.environ.get("PUSHGATEWAY"):
        day_chi_so(noi_dung, os.environ["PUSHGATEWAY"], os.environ.get("CELL", "khong-ro"))
    else:
        sys.stdout.write(noi_dung)


if __name__ == "__main__":
    main()
