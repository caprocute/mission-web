#!/usr/bin/env python3
"""Sao chép bản ghi phiên Warpgate sang bucket Object Lock (INF-27; SEC-42 lưu 1 năm bất biến, SEC-44).

CronJob warpgate-ghi-phien gắn PVC của Warpgate ở chế độ chỉ đọc (cùng node với pod Warpgate), duyệt THU_MUC, chép mỗi
tệp đã không đổi trong ON_DINH_PHUT phút lên s3://BUCKET/TIEN_TO/<đường dẫn tương đối> khi trên bucket chưa có khoá đó
hoặc kích thước khác. Bucket (chart rgw-cell, khoá buckets.pam-ghi-phien) bật versioning và Object Lock COMPLIANCE
365 ngày mặc định, nên mỗi đối tượng bị khoá ngay khi ghi; tài khoản S3 chỉ có PutObject và ListBucket, không xoá
được. Tệp đổi sau khi đã chép (phiên dài) thành phiên bản mới; bản cũ vẫn bị khoá.

S3 SigV4 tự ký bằng thư viện chuẩn (không cần aws-cli), thân tải lên không ký (UNSIGNED-PAYLOAD qua TLS) và có
Content-MD5 vì S3 đòi kiểm toàn vẹn cho PutObject trên bucket có Object Lock.

Biến: THU_MUC (mặc định /data/recordings), S3_ENDPOINT, S3_REGION, BUCKET, TIEN_TO, ON_DINH_PHUT, S3_KHOA (thư mục
gắn Secret rgw-pam-ghi-phien, tệp access-key và secret-key; không có thì đọc AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY),
S3_CA (bỏ trống: kho tin cậy của hệ thống), PUSHGATEWAY, CELL. Mã thoát 1 khi có tệp lỗi.
"""
import base64
import datetime as dt
import hashlib
import hmac
import http.client
import json
import os
import ssl
import sys
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

NS = "{http://s3.amazonaws.com/doc/2006-03-01/}"


def ecs(hanh_dong, ket_qua, thong_diep, **them):
    print(json.dumps({"@timestamp": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z",
                      "event": {"dataset": "security", "module": "pam", "action": hanh_dong, "outcome": ket_qua,
                                "kind": "alert" if ket_qua == "failure" else "event"},
                      "service": {"name": "warpgate-ghi-phien"}, "message": thong_diep, "pam": them},
                     ensure_ascii=False), flush=True)


def _k(key, msg):
    return hmac.new(key, msg.encode(), hashlib.sha256).digest()


def ky_sigv4(method, host, path, query, headers, ak, sk, region, now, payload_hash="UNSIGNED-PAYLOAD"):
    """Trả headers đã thêm Authorization theo AWS SigV4 (dịch vụ s3). path đã mã hoá URI; query: dict."""
    amz = now.strftime("%Y%m%dT%H%M%SZ")
    ngay = amz[:8]
    h = {k.lower(): str(v).strip() for k, v in headers.items()}
    h["host"] = host
    h["x-amz-date"] = amz
    h["x-amz-content-sha256"] = payload_hash
    ten_ky = ";".join(sorted(h))
    cq = "&".join(f"{urllib.parse.quote(k, safe='-_.~')}={urllib.parse.quote(str(v), safe='-_.~')}" for k, v in sorted(query.items()))
    yeu_cau = "\n".join([method, path, cq, "".join(f"{k}:{h[k]}\n" for k in sorted(h)), ten_ky, payload_hash])
    pham_vi = f"{ngay}/{region}/s3/aws4_request"
    chuoi = "\n".join(["AWS4-HMAC-SHA256", amz, pham_vi, hashlib.sha256(yeu_cau.encode()).hexdigest()])
    khoa = _k(_k(_k(_k(("AWS4" + sk).encode(), ngay), region), "s3"), "aws4_request")
    chu_ky = hmac.new(khoa, chuoi.encode(), hashlib.sha256).hexdigest()
    h["authorization"] = f"AWS4-HMAC-SHA256 Credential={ak}/{pham_vi}, SignedHeaders={ten_ky}, Signature={chu_ky}"
    return h


class S3:
    def __init__(self, endpoint, region, ak, sk, ca=None):
        u = urllib.parse.urlsplit(endpoint)
        self.https = u.scheme == "https"
        self.host = u.netloc
        self.region, self.ak, self.sk = region, ak, sk
        self.ctx = ssl.create_default_context(cafile=ca) if ca else ssl.create_default_context()

    def _conn(self):
        if self.https:
            return http.client.HTTPSConnection(self.host, timeout=60, context=self.ctx)
        return http.client.HTTPConnection(self.host, timeout=60)

    def goi(self, method, path, query=None, headers=None, body=None):
        query = query or {}
        h = ky_sigv4(method, self.host, path, query, headers or {}, self.ak, self.sk, self.region, dt.datetime.now(dt.timezone.utc))
        url = path + ("?" + urllib.parse.urlencode(sorted(query.items()), quote_via=urllib.parse.quote) if query else "")
        c = self._conn()
        try:
            c.request(method, url, body=body, headers=h)
            r = c.getresponse()
            return r.status, r.read(), dict(r.getheaders())
        finally:
            c.close()

    def liet_ke(self, bucket, tien_to):
        """{khoá: kích thước} của phiên bản hiện hành dưới tiền tố (ListObjectsV2, phân trang)."""
        kq, tiep = {}, None
        while True:
            q = {"list-type": "2", "prefix": tien_to}
            if tiep:
                q["continuation-token"] = tiep
            st, body, _ = self.goi("GET", "/" + urllib.parse.quote(bucket), q)
            if st != 200:
                raise RuntimeError(f"ListObjectsV2 lỗi {st}: {body[:300]!r}")
            x = ET.fromstring(body)
            for c in x.findall(f"{NS}Contents"):
                kq[c.find(f"{NS}Key").text] = int(c.find(f"{NS}Size").text)
            if (x.findtext(f"{NS}IsTruncated") or "false") != "true":
                return kq
            tiep = x.findtext(f"{NS}NextContinuationToken")

    def tai_len(self, bucket, khoa, tep):
        md5 = hashlib.md5()
        with open(tep, "rb") as f:
            for khoi in iter(lambda: f.read(1 << 20), b""):
                md5.update(khoi)
        co = os.path.getsize(tep)
        h = {"content-md5": base64.b64encode(md5.digest()).decode(), "content-length": str(co),
             "content-type": "application/octet-stream"}
        with open(tep, "rb") as f:
            st, body, _ = self.goi("PUT", "/" + urllib.parse.quote(bucket) + "/" + urllib.parse.quote(khoa, safe="/-_.~"), headers=h, body=f)
        if st != 200:
            raise RuntimeError(f"PutObject {khoa} lỗi {st}: {body[:300]!r}")
        return co


def tep_on_dinh(goc, on_dinh_giay, now):
    for thu_muc, _, tep in os.walk(goc):
        for t in sorted(tep):
            p = os.path.join(thu_muc, t)
            try:
                st = os.stat(p)
            except FileNotFoundError:
                continue
            yield p, st.st_size, now - st.st_mtime >= on_dinh_giay


def khoa_s3(env):
    """(access key, secret key): từ tệp trong S3_KHOA (Secret gắn dạng tệp) hoặc biến môi trường."""
    thu_muc = env.get("S3_KHOA")
    if thu_muc:
        with open(os.path.join(thu_muc, "access-key"), encoding="utf-8") as a, \
                open(os.path.join(thu_muc, "secret-key"), encoding="utf-8") as b:
            return a.read().strip(), b.read().strip()
    return env["AWS_ACCESS_KEY_ID"], env["AWS_SECRET_ACCESS_KEY"]


def chay(env, now=None):
    now = now or time.time()
    goc = env.get("THU_MUC", "/data/recordings")
    bucket, tien_to = env["BUCKET"], env.get("TIEN_TO", "ghi-phien").strip("/")
    s3 = S3(env["S3_ENDPOINT"], env.get("S3_REGION", "us-east-1"), *khoa_s3(env), env.get("S3_CA") or None)
    da_co = s3.liet_ke(bucket, tien_to + "/")
    chep = cho = loi = 0
    cu_nhat_chua_chep = None
    for p, co, on_dinh in tep_on_dinh(goc, int(env.get("ON_DINH_PHUT", "10")) * 60, now):
        khoa = f"{tien_to}/{os.path.relpath(p, goc)}"
        if da_co.get(khoa) == co:
            continue
        if not on_dinh:
            cho += 1
            continue
        try:
            s3.tai_len(bucket, khoa, p)
            chep += 1
            ecs("pam.ghi-phien-sao-chep", "success", f"đã chép {khoa}", khoa=khoa, kich_thuoc=co)
        except (OSError, RuntimeError) as e:
            loi += 1
            m = os.stat(p).st_mtime
            cu_nhat_chua_chep = m if cu_nhat_chua_chep is None else min(cu_nhat_chua_chep, m)
            ecs("pam.ghi-phien-sao-chep", "failure", str(e), khoa=khoa)
    return {"chep": chep, "cho": cho, "loi": loi, "cu_nhat_chua_chep": cu_nhat_chua_chep, "now": now}


def chi_so(kq, cell):
    d = []
    if kq["loi"] == 0:
        d += ["# HELP vt_pam_ghi_phien_last_success_timestamp_seconds Lần sao chép bản ghi phiên gần nhất không có lỗi.",
              "# TYPE vt_pam_ghi_phien_last_success_timestamp_seconds gauge",
              f'vt_pam_ghi_phien_last_success_timestamp_seconds{{cell="{cell}"}} {int(kq["now"])}']
    d += ["# HELP vt_pam_ghi_phien_loi Số tệp chép lỗi ở lần chạy gần nhất.", "# TYPE vt_pam_ghi_phien_loi gauge",
          f'vt_pam_ghi_phien_loi{{cell="{cell}"}} {kq["loi"]}',
          "# HELP vt_pam_ghi_phien_da_chep Số tệp đã chép ở lần chạy gần nhất.", "# TYPE vt_pam_ghi_phien_da_chep gauge",
          f'vt_pam_ghi_phien_da_chep{{cell="{cell}"}} {kq["chep"]}',
          "# HELP vt_pam_ghi_phien_cho_on_dinh Số tệp đang ghi dở (chưa đủ thời gian ổn định).",
          "# TYPE vt_pam_ghi_phien_cho_on_dinh gauge", f'vt_pam_ghi_phien_cho_on_dinh{{cell="{cell}"}} {kq["cho"]}',
          "# HELP vt_pam_ghi_phien_last_run_timestamp_seconds Thời điểm lần chạy gần nhất kết thúc.",
          "# TYPE vt_pam_ghi_phien_last_run_timestamp_seconds gauge",
          f'vt_pam_ghi_phien_last_run_timestamp_seconds{{cell="{cell}"}} {int(kq["now"])}']
    return "\n".join(d) + "\n"


def main():
    env = os.environ
    try:
        kq = chay(env)
    except (OSError, RuntimeError, KeyError) as e:
        ecs("pam.ghi-phien-sao-chep", "failure", f"không chạy được: {e}")
        kq = {"chep": 0, "cho": 0, "loi": 1, "cu_nhat_chua_chep": None, "now": time.time()}
    ecs("pam.ghi-phien-sao-chep", "success" if kq["loi"] == 0 else "failure",
        f'chép {kq["chep"]}, chờ ổn định {kq["cho"]}, lỗi {kq["loi"]}', **{k: kq[k] for k in ("chep", "cho", "loi")})
    if env.get("PUSHGATEWAY"):
        # POST chỉ thay các chỉ số cùng tên: lần lỗi không xoá vt_pam_ghi_phien_last_success_timestamp_seconds cũ, nên
        # cảnh báo PamGhiPhienCu đo được thời gian từ lần thành công gần nhất.
        rq = urllib.request.Request(f'{env["PUSHGATEWAY"].rstrip("/")}/metrics/job/warpgate-ghi-phien',
                                    data=chi_so(kq, env.get("CELL", "")).encode(), method="POST",
                                    headers={"Content-Type": "text/plain; version=0.0.4"})
        try:
            with urllib.request.urlopen(rq, timeout=10):
                pass
        except OSError as e:
            ecs("pam.ghi-phien-sao-chep", "failure", f"không đẩy được chỉ số: {e}")
            return 1
    return 1 if kq["loi"] else 0


if __name__ == "__main__":
    sys.exit(main())
