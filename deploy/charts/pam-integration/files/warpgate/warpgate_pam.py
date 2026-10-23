#!/usr/bin/env python3
"""Hai việc phụ của pod Warpgate (INF-27; SEC-42, SEC-43). Chỉ dùng thư viện chuẩn.

cau-hinh <mẫu> <đích>
    Init container: chép mẫu cấu hình Warpgate (ConfigMap) sang emptyDir, thay chỗ giữ __OIDC_CLIENT_SECRET__ bằng
    giá trị biến OIDC_CLIENT_SECRET (từ Secret qua secretKeyRef). Warpgate 0.30 chỉ đọc cấu hình từ tệp, không thay
    biến môi trường, nên bí mật không được nằm trong ConfigMap. Giá trị ghi dạng chuỗi JSON (cũng là YAML hợp lệ) để
    ký tự đặc biệt không làm hỏng tệp. Tệp đích quyền 0600.

cau-noi
    Sidecar: nhận sự kiện JSON mà Warpgate gửi qua socket datagram Unix (log.send_to), giữ sự kiện kiểm toán (target
    "audit"), đổi sang syslog RFC 5424 với dữ liệu có cấu trúc SD-ID SD_ID theo hợp đồng trong
    ops/runbooks/pam-cap-quyen.md, gửi qua TLS (mTLS) tới otel-siem. Mất kết nối thì giữ trong hàng đợi bộ nhớ (tối đa
    HANG_DOI_TOI_DA dòng, bỏ dòng cũ nhất và đếm), kết nối lại có lùi dần. Nhật ký vận hành của Warpgate (target
    khác "audit") đã có trên stdout của container warpgate, không gửi SIEM.

Biến của cau-noi: SOCKET, SIEM_HOST, SIEM_PORT, SIEM_SERVER_NAME, TLS_CA, TLS_CRT, TLS_KEY (bỏ trống ba biến TLS thì
gửi TCP thường, chỉ dùng khi kiểm thử), SD_ID, HOSTNAME_PAM, EXTERNAL_HOST, HANG_DOI_TOI_DA.
"""
import collections
import json
import os
import re
import socket
import ssl
import sys
import threading
import time

# Loại sự kiện kiểm toán của Warpgate 0.30.0 (_type, trường theo LogViewer của chính Warpgate) → event_action của hợp
# đồng. Gán loại sang hành động là suy luận: Warpgate không có bước "yêu cầu", "duyệt" (hai bước đó ở tầng Kubernetes
# và broker); cấp, thu hồi vai trò Warpgate là mở, đóng quyền vào mục tiêu.
HANH_DONG = {
    "AccessRoleGranted1": "pam.cap-quyen",
    "AccessRoleRevoked1": "pam.thu-hoi",
    "AdminRoleGranted1": "pam.cap-quyen",
    "AdminRoleRevoked1": "pam.thu-hoi",
    "TicketCreated1": "pam.cap-quyen",
    "TicketDeleted1": "pam.thu-hoi",
    "TargetSessionStarted1": "pam.phien-bat-dau",
    "TargetSessionEnded1": "pam.phien-ket-thuc",
    "KubernetesExecStarted1": "pam.lenh-nhay-cam",
    "KubernetesAttachStarted1": "pam.lenh-nhay-cam",
    "KubernetesPortForwardStarted1": "pam.lenh-nhay-cam",
    "KubernetesDebugContainerCreated1": "pam.lenh-nhay-cam",
    "KubernetesPodCreated1": "pam.lenh-nhay-cam",
    "KubernetesStreamRejected1": "pam.tu-choi",
    "WebApprovalBypassed1": "pam.lenh-nhay-cam",
    "UserAuthenticated1": "pam.dang-nhap",
    "UserAuthenticationFailed1": "pam.dang-nhap-that-bai",
    "UserCreated1": "pam.quan-tri",
    "UserDeleted1": "pam.quan-tri",
    "CredentialCreated1": "pam.quan-tri",
    "CredentialDeleted1": "pam.quan-tri",
}
# Trường Warpgate → tham số SD của hợp đồng (người dùng, phiên, mục tiêu, vai trò, lệnh...).
THAM_SO = [
    ("user", ("username", "grantee_username")),
    ("user_id", ("user_id", "grantee_id")),
    ("session_id", ("session_id", "session")),
    ("target", ("target_name",)),
    ("role", ("role_name", "admin_role_name")),
    ("namespace", ("namespace",)),
    ("pod", ("pod",)),
    ("command", ("command",)),
    ("client_ip", ("client_ip",)),
    ("protocol", ("protocol",)),
    ("reason", ("reason", "status")),
    ("request_id", ("ticket_id",)),
]
NGAT = (0x0A, 0x0D)
# RFC 5424 mục 6.2.3: TIME-SECFRAC tối đa 6 chữ số; Warpgate in nano giây (9 chữ số) nên phải cắt, nếu không receiver
# syslog của otel-siem không tách được dòng.
THOI_DIEM = re.compile(r"^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(\.\d+)?(Z|[+-]\d{2}:\d{2})$")


def chuan_thoi_diem(v):
    m = THOI_DIEM.match(str(v or ""))
    if not m:
        return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    return m.group(1) + (m.group(2) or "")[:7] + m.group(3)


def cau_hinh(mau, dich):
    bi_mat = os.environ.get("OIDC_CLIENT_SECRET", "")
    if not bi_mat:
        sys.exit("thiếu OIDC_CLIENT_SECRET")
    noi_dung = open(mau, encoding="utf-8").read()
    if noi_dung.count('"__OIDC_CLIENT_SECRET__"') != 1:
        sys.exit('mẫu cấu hình phải có đúng một chỗ giữ "__OIDC_CLIENT_SECRET__"')
    noi_dung = noi_dung.replace('"__OIDC_CLIENT_SECRET__"', json.dumps(bi_mat))
    fd = os.open(dich + ".tmp", os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        f.write(noi_dung)
    os.replace(dich + ".tmp", dich)


def thoat_sd(v):
    # RFC 5424 mục 6.3.3: trong PARAM-VALUE phải thoát '"', '\' và ']'.
    s = str(v).replace("\\", "\\\\").replace('"', '\\"').replace("]", "\\]")
    return "".join(" " if ord(c) in NGAT else c for c in s)


def sang_syslog(su_kien, sd_id="vtpam@32473", host="warpgate", external_host="warpgate.vt.local"):
    """Một sự kiện JSON của Warpgate → một dòng syslog RFC 5424, hoặc None nếu không phải sự kiện kiểm toán."""
    if su_kien.get("target") != "audit":
        return None
    loai = str(su_kien.get("_type", "")).strip('"')
    hanh_dong = HANH_DONG.get(loai, "pam.khac")
    ts = [("event_action", hanh_dong), ("warpgate_type", loai or "-")]
    for ten, nguon in THAM_SO:
        for k in nguon:
            if su_kien.get(k) not in (None, ""):
                ts.append((ten, su_kien[k]))
                break
    if loai == "TargetSessionStarted1" and su_kien.get("session_id"):
        # Trang phiên (có bản ghi hình) trong giao diện quản trị Warpgate; đường dẫn theo định tuyến của giao diện 0.30.
        ts.append(("recording_uri", f"https://{external_host}/@warpgate/admin#/sessions/{su_kien['session_id']}"))
    if loai == "KubernetesPodCreated1" and str(su_kien.get("privileged")).lower() == "true":
        ts.append(("privileged", "true"))
    if hanh_dong in ("pam.dang-nhap-that-bai", "pam.tu-choi"):
        ts.append(("outcome", "failure"))
    # Mức: 4 (warning) cho thao tác nhạy cảm và thất bại, 6 (informational) cho phần còn lại; facility 10 (authpriv).
    muc = 4 if hanh_dong in ("pam.lenh-nhay-cam", "pam.dang-nhap-that-bai", "pam.tu-choi") else 6
    sd = "[" + sd_id + "".join(f' {k}="{thoat_sd(v)}"' for k, v in ts) + "]"
    thong_diep = "".join(" " if ord(c) in NGAT else c for c in str(su_kien.get("message", loai)))
    thoi_diem = chuan_thoi_diem(su_kien.get("timestamp"))
    return f"<{10 * 8 + muc}>1 {thoi_diem} {host} warpgate - {hanh_dong} {sd} {thong_diep}"


class CauNoi:
    def __init__(self):
        e = os.environ
        self.sock_path = e.get("SOCKET", "/run/warpgate-log/log.sock")
        self.host, self.port = e["SIEM_HOST"], int(e.get("SIEM_PORT", "6514"))
        self.server_name = e.get("SIEM_SERVER_NAME", self.host)
        self.sd_id = e.get("SD_ID", "vtpam@32473")
        self.ten_may = e.get("HOSTNAME_PAM") or socket.gethostname()
        self.external_host = e.get("EXTERNAL_HOST", "warpgate.vt.local")
        self.hang_doi = collections.deque(maxlen=int(e.get("HANG_DOI_TOI_DA", "10000")))
        self.bo = 0
        self.co = threading.Condition()
        self.ctx = None
        if e.get("TLS_CA"):
            self.ctx = ssl.create_default_context(cafile=e["TLS_CA"])
            self.ctx.minimum_version = ssl.TLSVersion.TLSv1_2
            self.ctx.load_cert_chain(e["TLS_CRT"], e["TLS_KEY"])

    def nhan(self):
        try:
            os.unlink(self.sock_path)
        except FileNotFoundError:
            pass
        s = socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM)
        s.bind(self.sock_path)
        os.chmod(self.sock_path, 0o660)
        print(f"cau-noi: nghe {self.sock_path}, gửi {self.host}:{self.port}", flush=True)
        while True:
            goi = s.recv(1 << 20)
            try:
                dong = sang_syslog(json.loads(goi), self.sd_id, self.ten_may, self.external_host)
            except (ValueError, AttributeError):
                continue
            if dong is None:
                continue
            with self.co:
                if len(self.hang_doi) == self.hang_doi.maxlen:
                    self.bo += 1
                    print(f"cau-noi: hàng đợi đầy, đã bỏ {self.bo} dòng", file=sys.stderr, flush=True)
                self.hang_doi.append(dong)
                self.co.notify()

    def gui(self):
        cho = 1
        while True:
            try:
                raw = socket.create_connection((self.host, self.port), timeout=10)
                conn = self.ctx.wrap_socket(raw, server_hostname=self.server_name) if self.ctx else raw
                cho = 1
                while True:
                    with self.co:
                        while not self.hang_doi:
                            self.co.wait()
                        dong = self.hang_doi[0]
                    conn.sendall((dong + "\n").encode("utf-8"))
                    with self.co:
                        if self.hang_doi and self.hang_doi[0] is dong:
                            self.hang_doi.popleft()
            except OSError as loi:
                print(f"cau-noi: lỗi gửi SIEM ({loi}); thử lại sau {cho} giây", file=sys.stderr, flush=True)
                time.sleep(cho)
                cho = min(cho * 2, 30)

    def chay(self):
        threading.Thread(target=self.gui, daemon=True).start()
        self.nhan()


if __name__ == "__main__":
    if len(sys.argv) == 4 and sys.argv[1] == "cau-hinh":
        cau_hinh(sys.argv[2], sys.argv[3])
    elif len(sys.argv) == 2 and sys.argv[1] == "cau-noi":
        CauNoi().chay()
    else:
        sys.exit(__doc__)
