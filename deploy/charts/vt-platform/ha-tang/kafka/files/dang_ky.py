"""Đăng ký lược đồ Avro của danh mục sự kiện vào Apicurio Registry 3 (INF-10, TL-03 "Đăng ký lược đồ").

Chạy trong Job sau mỗi lần cài hoặc nâng cấp chart kafka. Đặt quy tắc toàn cục (COMPATIBILITY, VALIDITY), rồi đăng ký
từng artifact theo thứ tự trong thu-tu.json (lược đồ được tham chiếu trước). Phiên bản trùng nội dung được dùng lại
(ifExists=FIND_OR_CREATE_VERSION); phiên bản không tương thích bị Apicurio từ chối và Job thất bại, chặn bản phát
hành mang lược đồ phá vỡ người tiêu thụ (Apicurio 3 trả 400 RuleViolationException). Chỉ dùng thư viện chuẩn của Python.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = os.environ["REGISTRY_URL"].rstrip("/") + "/apis/registry/v3"
THU_MUC = os.environ.get("THU_MUC", "/cfg")
NHOM = "default"


def goi(phuong_thuc, duong_dan, du_lieu=None):
    req = urllib.request.Request(API + duong_dan, method=phuong_thuc,
                                 data=None if du_lieu is None else json.dumps(du_lieu).encode(),
                                 headers={"Content-Type": "application/json", "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            noi_dung = r.read()
            return r.status, json.loads(noi_dung) if noi_dung else None
    except urllib.error.HTTPError as e:
        noi_dung = e.read()
        try:
            return e.code, json.loads(noi_dung)
        except ValueError:
            return e.code, noi_dung.decode(errors="replace")


def cho_san_sang():
    for _ in range(60):
        try:
            if goi("GET", "/system/info")[0] == 200:
                return
        except OSError:
            pass
        time.sleep(5)
    sys.exit("Apicurio chưa sẵn sàng sau 5 phút")


def quy_tac():
    for loai in ("COMPATIBILITY", "VALIDITY"):
        gt = os.environ.get("QUY_TAC_" + loai)
        if not gt:
            continue
        ma, _ = goi("POST", "/admin/rules", {"ruleType": loai, "config": gt})
        if ma == 409:
            ma, _ = goi("PUT", "/admin/rules/" + loai, {"ruleType": loai, "config": gt})
        if ma not in (200, 204):
            sys.exit(f"không đặt được quy tắc {loai}={gt}: HTTP {ma}")
        print(f"quy tắc toàn cục {loai}={gt}")


def dang_ky():
    with open(os.path.join(THU_MUC, "thu-tu.json"), encoding="utf-8") as f:
        ds = json.load(f)
    phien_ban, loi = {}, 0
    for a in ds:
        with open(os.path.join(THU_MUC, a["artifactId"] + ".avsc"), encoding="utf-8") as f:
            noi_dung = f.read()
        thieu = [t["artifactId"] for t in a.get("thamChieu", []) if t["artifactId"] not in phien_ban]
        if thieu:
            loi += 1
            print(f"LỖI {a['artifactId']}: lược đồ được tham chiếu chưa đăng ký được {thieu}", file=sys.stderr)
            continue
        tham_chieu = [{"groupId": NHOM, "artifactId": t["artifactId"], "version": phien_ban[t["artifactId"]],
                       "name": t["ten"]} for t in a.get("thamChieu", [])]
        ma, kq = goi("POST", f"/groups/{NHOM}/artifacts?ifExists=FIND_OR_CREATE_VERSION", {
            "artifactId": a["artifactId"], "artifactType": "AVRO",
            "firstVersion": {"content": {"content": noi_dung, "contentType": "application/json",
                                         "references": tham_chieu}}})
        if ma != 200:
            loi += 1
            print(f"LỖI {a['artifactId']}: HTTP {ma} {kq}", file=sys.stderr)
            continue
        phien_ban[a["artifactId"]] = kq["version"]["version"]
        print(f"{a['artifactId']} phiên bản {kq['version']['version']} (globalId {kq['version']['globalId']})")
    return loi


if __name__ == "__main__":
    cho_san_sang()
    quy_tac()
    so_loi = dang_ky()
    print(f"đăng ký xong, lỗi: {so_loi}")
    sys.exit(1 if so_loi else 0)
