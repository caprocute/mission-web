#!/usr/bin/env bash
# VH-07: thu bằng chứng tự động cho hồ sơ cấp độ 4 (TL-04 mục 9.7) từ cụm thử (kind hoặc staging).
#
# Kết quả ghi vào một thư mục có ngày, mặc định NGOÀI repo ($HOME/bang-chung-cap-do-4/<YYYY-MM-DD>), vì bằng
# chứng có thể chứa tên khoá, danh sách ảnh, sự kiện của cụm. Hồ sơ trong repo chỉ ghi tham chiếu tới thư mục này.
# Script không nhận bí mật qua tham số; thông tin truy cập lấy từ biến môi trường của người chạy và không được ghi ra tệp.
#
# Biến môi trường (tuỳ chọn, thiếu thì bỏ qua bước tương ứng):
#   OUT_DIR             thư mục gốc chứa bằng chứng (mặc định $HOME/bang-chung-cap-do-4)
#   KUBECONFIG          cụm cần thu (kubectl dùng ngữ cảnh hiện hành)
#   COSIGN_KEY          khoá công khai hoặc tham chiếu KMS để `cosign verify` (SEC-38)
#   MAU_KIEM_CHU_KY     số ảnh kiểm ngẫu nhiên chữ ký, mặc định 5
#   KC_URL, KC_REALM, KC_TOKEN   API quản trị Keycloak để tính tỷ lệ MFA (SEC-13); KC_TOKEN là token quản trị ngắn hạn
#   BAO_ADDR, BAO_TOKEN          OpenBao để liệt kê khoá Transit theo tenant (SEC-11), chỉ tên và thuộc tính
#
# Dùng: bash docs/attt/cap-do-4/thu-bang-chung.sh [--ngay YYYY-MM-DD]
set -uo pipefail

NGAY=$(date +%F)
if [[ "${1:-}" == "--ngay" && -n "${2:-}" ]]; then NGAY="$2"; fi
GOC="${OUT_DIR:-$HOME/bang-chung-cap-do-4}"
DICH="$GOC/$NGAY"
mkdir -p "$DICH/sbom"
TOM_TAT="$DICH/tom-tat.txt"
: >"$TOM_TAT"

ghi() { printf '%-10s %-28s %s\n' "$1" "$2" "$3" | tee -a "$TOM_TAT"; }
co() { command -v "$1" >/dev/null 2>&1; }
cum() { co kubectl && kubectl version --request-timeout=5s >/dev/null 2>&1; }

echo "Thu bằng chứng cấp độ 4 vào $DICH"
{
  echo "Ngày thu: $NGAY"
  echo "Ngữ cảnh kubectl: $(co kubectl && kubectl config current-context 2>/dev/null || echo 'không có')"
  echo "Phiên bản repo: $(git -C "$(dirname "$0")" rev-parse --short HEAD 2>/dev/null || echo 'không rõ')"
  echo
} >>"$TOM_TAT"

# 1. Ảnh đang chạy theo digest (SEC-55) và kiểm ngẫu nhiên chữ ký (SEC-38)
if cum; then
  kubectl get pods -A -o jsonpath='{range .items[*]}{range .status.containerStatuses[*]}{.imageID}{"\n"}{end}{end}' 2>/dev/null \
    | sed 's#^[a-z-]*://##' | grep . | sort -u >"$DICH/anh-dang-chay.txt"
  ghi "OK" "anh-dang-chay" "$(wc -l <"$DICH/anh-dang-chay.txt") ảnh theo digest"
  if co cosign && [[ -n "${COSIGN_KEY:-}" ]]; then
    : >"$DICH/cosign-kiem-ngau-nhien.txt"
    shuf -n "${MAU_KIEM_CHU_KY:-5}" "$DICH/anh-dang-chay.txt" 2>/dev/null | while read -r anh; do
      if cosign verify --key "$COSIGN_KEY" "$anh" >/dev/null 2>&1; then kq="HỢP LỆ"; else kq="KHÔNG HỢP LỆ"; fi
      echo "$kq $anh" >>"$DICH/cosign-kiem-ngau-nhien.txt"
    done
    ghi "OK" "cosign-kiem-ngau-nhien" "$(grep -c '^HỢP LỆ' "$DICH/cosign-kiem-ngau-nhien.txt") hợp lệ"
    # SBOM CycloneDX gắn vào ảnh (SEC-23)
    n=0
    while read -r anh; do
      tep="$DICH/sbom/$(echo "$anh" | tr '/:@' '___').cdx.json"
      if cosign download sbom "$anh" >"$tep" 2>/dev/null && [[ -s "$tep" ]]; then n=$((n + 1)); else rm -f "$tep"; fi
    done <"$DICH/anh-dang-chay.txt"
    ghi "OK" "sbom" "$n SBOM tải được"
  else
    ghi "BỎ QUA" "cosign, sbom" "thiếu cosign hoặc COSIGN_KEY"
  fi
else
  ghi "BỎ QUA" "anh-dang-chay" "không kết nối được cụm"
fi

# 2. Nhật ký từ chối của Kyverno (SEC-28, SEC-38)
if cum; then
  {
    kubectl get policyreports,clusterpolicyreports -A -o wide 2>/dev/null
    echo
    kubectl get events -A --field-selector reason=PolicyViolation -o wide 2>/dev/null
  } >"$DICH/kyverno-tu-choi.txt"
  ghi "OK" "kyverno-tu-choi" "$(grep -c . "$DICH/kyverno-tu-choi.txt") dòng"
else
  ghi "BỎ QUA" "kyverno-tu-choi" "không kết nối được cụm"
fi

# 3. mTLS: PeerAuthentication và ztunnel (SEC-21)
if cum; then
  kubectl get peerauthentications.security.istio.io -A -o yaml >"$DICH/peer-authentication.yaml" 2>/dev/null \
    && ghi "OK" "peer-authentication" "$(grep -c 'mode: STRICT' "$DICH/peer-authentication.yaml") khai báo STRICT" \
    || ghi "LỖI" "peer-authentication" "không đọc được CRD của Istio"
  if co istioctl; then
    istioctl ztunnel-config workloads -o json >"$DICH/ztunnel-workloads.json" 2>/dev/null \
      && ghi "OK" "ztunnel-workloads" "đã xuất" || ghi "LỖI" "ztunnel-workloads" "istioctl lỗi"
  else
    ghi "BỎ QUA" "ztunnel-workloads" "thiếu istioctl"
  fi
else
  ghi "BỎ QUA" "peer-authentication" "không kết nối được cụm"
fi

# 4. Sao lưu: Velero và restore-check (SEC-44, OPS-18)
if cum; then
  {
    kubectl get backups.velero.io -A -o wide 2>/dev/null
    echo
    kubectl get cronjobs,jobs -A -l app.kubernetes.io/name=backup-cell -o wide 2>/dev/null
  } >"$DICH/sao-luu.txt"
  ghi "OK" "sao-luu" "$(grep -c . "$DICH/sao-luu.txt") dòng"
else
  ghi "BỎ QUA" "sao-luu" "không kết nối được cụm"
fi

# 5. Tỷ lệ người dùng có MFA (SEC-13)
if co curl && co python3 && [[ -n "${KC_URL:-}" && -n "${KC_REALM:-}" && -n "${KC_TOKEN:-}" ]]; then
  KC_URL="$KC_URL" KC_REALM="$KC_REALM" KC_TOKEN="$KC_TOKEN" python3 - >"$DICH/mfa.txt" 2>&1 <<'PY'
import json, os, urllib.request
base = f"{os.environ['KC_URL'].rstrip('/')}/admin/realms/{os.environ['KC_REALM']}"
hdr = {"Authorization": "Bearer " + os.environ["KC_TOKEN"]}
def get(path):
    req = urllib.request.Request(base + path, headers=hdr)
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)
tong = co_mfa = 0
first = 0
while True:
    trang = get(f"/users?enabled=true&briefRepresentation=true&first={first}&max=200")
    if not trang:
        break
    for u in trang:
        tong += 1
        loai = {c.get("type") for c in get(f"/users/{u['id']}/credentials")}
        if loai & {"otp", "webauthn", "webauthn-passwordless"}:
            co_mfa += 1
    first += len(trang)
ty_le = (100.0 * co_mfa / tong) if tong else 0.0
print(f"Người dùng đang bật: {tong}")
print(f"Có yếu tố thứ hai (OTP hoặc WebAuthn): {co_mfa}")
print(f"Tỷ lệ: {ty_le:.2f}%")
PY
  ghi "OK" "mfa" "$(tail -1 "$DICH/mfa.txt")"
else
  ghi "BỎ QUA" "mfa" "thiếu KC_URL, KC_REALM hoặc KC_TOKEN"
fi

# 6. Khoá Transit theo tenant (SEC-11): chỉ tên và thuộc tính, không có vật liệu khoá
if co bao && [[ -n "${BAO_ADDR:-}" && -n "${BAO_TOKEN:-}" ]]; then
  : >"$DICH/khoa-transit.txt"
  bao list -format=json transit/keys 2>/dev/null | python3 -c 'import json,sys; [print(k) for k in json.load(sys.stdin) if k.startswith("kek-tenant-")]' \
    | while read -r k; do
      bao read -format=json "transit/keys/$k" 2>/dev/null \
        | python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; print(sys.argv[1], "type="+str(d.get("type")), "exportable="+str(d.get("exportable")), "deletion_allowed="+str(d.get("deletion_allowed")), "latest_version="+str(d.get("latest_version")))' "$k" \
        >>"$DICH/khoa-transit.txt"
    done
  ghi "OK" "khoa-transit" "$(grep -c . "$DICH/khoa-transit.txt") khoá tenant"
else
  ghi "BỎ QUA" "khoa-transit" "thiếu bao, BAO_ADDR hoặc BAO_TOKEN"
fi

echo
echo "Xong. Tóm tắt: $TOM_TAT"
