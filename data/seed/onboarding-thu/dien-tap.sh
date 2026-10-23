#!/usr/bin/env bash
# VH-04 (OPS-07, TL-04 mục 11.4, QT-01): diễn tập vòng đời một tenant thử qua API tenant-registry (SVC-03).
#
# Các bước: kiểm cell đích → tạo hồ sơ (createConfigTenant) → bước 3 mô hình, cell, gói (updateConfigTenant) →
# bước 4 IdP (quản trị tenant) → cấp phát (provisionConfigTenant, 202 + job, tuỳ chọn lỗi cố ý ở bước Organization
# rồi chạy lại) → sẵn sàng (kiểm IdP, cây tổ chức) → kích hoạt (activateConfigTenant, gửi lại cùng khoá) →
# tạm ngừng (suspendConfigTenant) → kích hoạt lại → đếm sự kiện. Mỗi bước ghi mốc thời gian vào bảng kết quả để
# chép vào biên bản ops/dien-tap/<ngày>-ops-07.md.
#
# Dùng:
#   data/seed/onboarding-thu/dien-tap.sh <hồ-sơ.json> [--loi-buoc-4] [--huy-thu] [--cham-dut]
#     --loi-buoc-4  bật lỗi 503 ở bước Organization Keycloak, chờ saga bù trừ về KHOI_TAO, tắt lỗi rồi cấp phát lại
#                   (chỉ khi có KHUNG_URL; môi trường thật thì gây lỗi bằng tay, xem biên bản mẫu)
#     --huy-thu     tạo thêm một hồ sơ nháp rồi huỷ (KHOI_TAO → DA_CHAM_DUT, cancelConfigTenant)
#     --cham-dut    cuối diễn tập tạm ngừng lại rồi chấm dứt tenant (TAM_NGUNG → DA_CHAM_DUT, terminateConfigTenant);
#                   trạng thái cuối, không quay lại được, chỉ dùng cho tenant thử
#
# Biến môi trường:
#   REGISTRY_URL    gốc API tenant-registry (mặc định http://127.0.0.1:35110, cổng dev compose)
#   TOKEN_QTNT      access token quản trị nền tảng (platform_admin, acr aal2), ví dụ lấy từ vtctl login --step-up
#   TOKEN_QTTENANT  access token quản trị tenant (tenant_admin) của đúng mã đơn vị, cho bước 4
#   KHUNG_URL       gốc khung giả lập (khung-gia-lap/): tự lấy token, giả lập kiểm IdP và cây tổ chức, bật lỗi
#   HAU_TO          hậu tố thêm vào mã đơn vị để chạy lặp (mặc định giờ phút giây); đặt rỗng để giữ mã gốc
#   OUT_DIR         thư mục kết quả (mặc định $HOME/dien-tap-ops-07/<ngày>), không ghi token vào đây
#   CHO_TOI_DA      số giây chờ tối đa một job cấp phát (mặc định 3600)
#   IDP_ISSUER      thay issuer của IdP trong hồ sơ bằng issuer gọi tới được (dev: http://keycloak:8080/realms/mission)
#
# Mã thoát: 0 mọi bước chạy được đều đạt; 1 có bước không đạt; 2 sai tham số hoặc thiếu công cụ.
set -euo pipefail

HO_SO="${1:-}"
[[ -n "$HO_SO" && -f "$HO_SO" ]] || { sed -n '10,27p' "$0"; exit 2; }
shift
LOI_BUOC_4=0
HUY_THU=0
CHAM_DUT=0
for a in "$@"; do
  case "$a" in
    --loi-buoc-4) LOI_BUOC_4=1 ;;
    --huy-thu) HUY_THU=1 ;;
    --cham-dut) CHAM_DUT=1 ;;
    *) echo "Tham số lạ: $a" >&2; exit 2 ;;
  esac
done
for c in curl jq; do command -v "$c" >/dev/null || { echo "Thiếu công cụ $c" >&2; exit 2; }; done
# Idempotency-Key: uuidgen nếu có, không thì lấy từ nhân Linux.
khoa() { if command -v uuidgen >/dev/null; then uuidgen; else cat /proc/sys/kernel/random/uuid; fi; }

REGISTRY_URL="${REGISTRY_URL:-http://127.0.0.1:35110}"
KHUNG_URL="${KHUNG_URL:-}"
HAU_TO="${HAU_TO-$(date +%H%M%S)}"
CHO_TOI_DA="${CHO_TOI_DA:-3600}"
OUT_DIR="${OUT_DIR:-$HOME/dien-tap-ops-07/$(date +%F)}"
mkdir -p "$OUT_DIR"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

CODE="$(jq -r '.tao.code' "$HO_SO")${HAU_TO:+_$HAU_TO}"
KET_QUA="$OUT_DIR/ket-qua-$CODE.md"
NHAT_KY="$OUT_DIR/nhat-ky-$CODE.jsonl"
: >"$NHAT_KY"
LOI=0

# Thay {code} trong hồ sơ bằng mã đơn vị đang chạy; tên miền thư không nhận dấu gạch dưới.
phan() { jq -c --arg c "$CODE" --arg d "${CODE//_/-}" "$1"' | walk(if type == "string" then gsub("\\{code\\}"; $d) else . end)' "$HO_SO"; }

token_khung() { curl -fsS "$KHUNG_URL/__khung/token?vai-tro=$1&tenant=$CODE"; }
if [[ -n "$KHUNG_URL" ]]; then
  TOKEN_QTNT="$(token_khung platform_admin)"
  TOKEN_QTTENANT="$(token_khung tenant_admin)"
fi
: "${TOKEN_QTNT:?Đặt TOKEN_QTNT (quản trị nền tảng, AAL2) hoặc KHUNG_URL}"

# goi <METHOD> <đường dẫn> <token> [thân] [Content-Type] [If-Match] [Idempotency-Key]
# Đặt MA (mã HTTP), ETAG, VI_TRI (Location), PHAT_LAI (Idempotency-Replayed); thân phản hồi ở $TMP/than.
goi() {
  local m="$1" p="$2" tok="$3" body="${4:-}" ct="${5:-application/json}" im="${6:-}" key="${7:-}"
  local args=(-sS -o "$TMP/than" -D "$TMP/dau" -w '%{http_code}' -X "$m" -H "Authorization: Bearer $tok" -H 'Accept: application/json, application/problem+json')
  [[ -n "$body" ]] && args+=(-H "Content-Type: $ct" --data-binary "$body")
  [[ -n "$im" ]] && args+=(-H "If-Match: \"$im\"")
  [[ "$m" != GET ]] && args+=(-H "Idempotency-Key: ${key:-$(khoa)}")
  MA="$(curl "${args[@]}" "$REGISTRY_URL$p")" || MA=000
  ETAG="$(tr -d '\r' <"$TMP/dau" | awk -F': ' 'tolower($1)=="etag"{gsub(/"/,"",$2); print $2}' | tail -1)"
  VI_TRI="$(tr -d '\r' <"$TMP/dau" | awk -F': ' 'tolower($1)=="location"{print $2}' | tail -1)"
  PHAT_LAI="$(tr -d '\r' <"$TMP/dau" | awk -F': ' 'tolower($1)=="idempotency-replayed"{print $2}' | tail -1)"
  jq -cn --arg m "$m" --arg p "$p" --arg ma "$MA" --arg t "$(date -Is)" --rawfile b "$TMP/than" \
    '{luc: $t, goi: ($m + " " + $p), ma: ($ma | tonumber), than: ($b | (try fromjson catch .))}' >>"$NHAT_KY"
}

loi_van_de() { jq -r '[.code // empty, .detail // .title // empty] | join(": ")' "$TMP/than" 2>/dev/null || true; }

BUOC=0
T0="$(date +%s.%N)"
printf '| Bước | Nội dung | Kết quả | Thời gian (giây) | Ghi chú |\n|---|---|---|---|---|\n' >"$KET_QUA"
ghi() { # ghi <nội dung> <ĐẠT|KHÔNG ĐẠT|CHƯA CHẠY> <bắt đầu> <ghi chú>
  BUOC=$((BUOC + 1))
  local dt; dt="$(awk -v a="$3" -v b="$(date +%s.%N)" 'BEGIN{printf "%.1f", b-a}')"
  printf '| %d | %s | %s | %s | %s |\n' "$BUOC" "$1" "$2" "$dt" "$4" >>"$KET_QUA"
  printf '[%2d] %-52s %-10s %7ss  %s\n' "$BUOC" "$1" "$2" "$dt" "$4"
  [[ "$2" == "KHÔNG ĐẠT" ]] && LOI=1
  return 0
}
ket_thuc() {
  local tong; tong="$(awk -v a="$T0" -v b="$(date +%s.%N)" 'BEGIN{printf "%.1f", b-a}')"
  printf '\nTổng thời gian: %s giây. Mã đơn vị: `%s`, tenant: `%s`.\n' "$tong" "$CODE" "${ID:-chưa tạo}" >>"$KET_QUA"
  echo "Tổng thời gian: ${tong}s. Kết quả: $KET_QUA; nhật ký phản hồi: $NHAT_KY"
  exit "$LOI"
}
# doc_tenant: đọc lại hồ sơ, đặt ST (trạng thái) và V (phiên bản cho If-Match); không gọi trong $(...) để giữ biến.
doc_tenant() { goi GET "/api/v1/config/tenants/$ID" "$TOKEN_QTNT"; ST="$(jq -r '.status' "$TMP/than")"; V="$ETAG"; }

# cho_job <đường dẫn job>: chờ job tới HOAN_TAT hoặc THAT_BAI; in trạng thái cuối.
cho_job() {
  local het=$((SECONDS + CHO_TOI_DA)) st
  while ((SECONDS < het)); do
    goi GET "$1" "$TOKEN_QTNT"
    st="$(jq -r '.status' "$TMP/than")"
    [[ "$st" == HOAN_TAT || "$st" == THAT_BAI ]] && { echo "$st"; return; }
    sleep 3
  done
  echo HET_GIO
}

echo "Diễn tập OPS-07: hồ sơ $(basename "$HO_SO"), mã đơn vị $CODE, registry $REGISTRY_URL"
CELL="$(jq -r '.buoc3.cellCode' "$HO_SO")"
MO_HINH="$(jq -r '.buoc3.tenancyModel' "$HO_SO")"

# 1. Cell đích có trong danh bạ và đang hoạt động.
t="$(date +%s.%N)"
goi GET /api/v1/registry/cells "$TOKEN_QTNT"
if [[ "$MA" == 200 ]] && jq -e --arg c "$CELL" '(.items // .) | map(select(.code == $c)) | length > 0' "$TMP/than" >/dev/null; then
  ghi "Cell đích $CELL có trong danh bạ SVC-03" "ĐẠT" "$t" ""
else
  ghi "Cell đích $CELL có trong danh bạ SVC-03" "KHÔNG ĐẠT" "$t" "HTTP $MA $(loi_van_de)"; ket_thuc
fi

# 2. Tạo hồ sơ (KHOI_TAO).
t="$(date +%s.%N)"
# Mã và tên tenant đều phải không trùng: khi chạy lặp, thêm hậu tố vào cả hai.
goi POST /api/v1/config/tenants "$TOKEN_QTNT" "$(phan '.tao | .code = $c' | jq -c --arg h "$HAU_TO" 'if $h != "" then .name += " – lần " + $h else . end')"
if [[ "$MA" == 201 ]]; then
  ID="$(jq -r '.id' "$TMP/than")"; V="$ETAG"
  ghi "Tạo hồ sơ tenant ($MO_HINH)" "ĐẠT" "$t" "$(jq -r '"\(.status), mã hồ sơ \(.onboardingCode // "-")"' "$TMP/than")"
else
  ghi "Tạo hồ sơ tenant ($MO_HINH)" "KHÔNG ĐẠT" "$t" "HTTP $MA $(loi_van_de)"; ket_thuc
fi

# 3. Bước 3 wizard: mô hình, cell, Policy Pack, UI Pack (quản trị nền tảng).
t="$(date +%s.%N)"
goi PATCH "/api/v1/config/tenants/$ID" "$TOKEN_QTNT" "$(phan '.buoc3')" application/merge-patch+json "$V"
if [[ "$MA" == 200 ]]; then V="$ETAG"; ghi "Bước 3: mô hình, cell, Policy Pack, UI Pack" "ĐẠT" "$t" "$(jq -r '"kek \(.kekMode // .kekRef // "-")"' "$TMP/than")"
else ghi "Bước 3: mô hình, cell, Policy Pack, UI Pack" "KHÔNG ĐẠT" "$t" "HTTP $MA $(loi_van_de)"; ket_thuc; fi

# 4. Bước 4 wizard: IdP liên kết (quản trị tenant của đúng mã đơn vị).
t="$(date +%s.%N)"
if [[ -z "${TOKEN_QTTENANT:-}" ]]; then
  ghi "Bước 4: khai IdP liên kết" "CHƯA CHẠY" "$t" "thiếu TOKEN_QTTENANT"
else
  goi PATCH "/api/v1/config/tenants/$ID" "$TOKEN_QTTENANT" "$(phan '.buoc4' | jq -c --arg i "${IDP_ISSUER:-}" 'if $i != "" then .idps |= map(.issuer = $i) else . end')" application/merge-patch+json "$V"
  if [[ "$MA" == 200 ]]; then V="$ETAG"; ghi "Bước 4: khai IdP liên kết" "ĐẠT" "$t" ""
  else ghi "Bước 4: khai IdP liên kết" "KHÔNG ĐẠT" "$t" "HTTP $MA $(loi_van_de)"; ket_thuc; fi
fi

# cap_phat <nội dung> <kết quả job mong đợi> <trạng thái tenant mong đợi>
cap_phat() {
  local t; t="$(date +%s.%N)"
  goi POST "/api/v1/config/tenants/$ID/provision" "$TOKEN_QTNT" "" "" "$V"
  if [[ "$MA" == 503 ]]; then
    ghi "$1" "CHƯA CHẠY" "$t" "503: saga cấp phát chưa cấu hình ở môi trường này (thiếu PROVISION_*)"; ket_thuc
  elif [[ "$MA" != 202 ]]; then
    ghi "$1" "KHÔNG ĐẠT" "$t" "HTTP $MA $(loi_van_de)"; ket_thuc
  fi
  local job="$VI_TRI" kq st buoc
  kq="$(cho_job "$job")"
  buoc="$(jq -r '.message // ""' "$TMP/than" | tr '|' '/')"
  doc_tenant
  if [[ "$kq" == "$2" && "$ST" == "$3" ]]; then ghi "$1" "ĐẠT" "$t" "job $kq, tenant $ST. $buoc"
  else ghi "$1" "KHÔNG ĐẠT" "$t" "job $kq, tenant $ST (mong đợi $2, $3). $buoc"; ket_thuc; fi
}

# 5. Cấp phát; tuỳ chọn lỗi cố ý ở bước Organization (bước 4 của TL-04 11.4) rồi chạy lại để kiểm idempotent.
if ((LOI_BUOC_4)); then
  if [[ -n "$KHUNG_URL" ]]; then
    curl -fsS -X POST "$KHUNG_URL/__khung/loi-org?bat=1"
    cap_phat "Cấp phát lần 1, lỗi cố ý ở bước Organization" THAT_BAI KHOI_TAO
    curl -fsS -X POST "$KHUNG_URL/__khung/loi-org?bat=0"
  else
    ghi "Cấp phát lần 1, lỗi cố ý ở bước Organization" "CHƯA CHẠY" "$(date +%s.%N)" "cần KHUNG_URL; môi trường thật gây lỗi bằng tay"
  fi
fi
cap_phat "Cấp phát (saga PROVISION) tới DANG_CAU_HINH" HOAN_TAT DANG_CAU_HINH

# 6. Sẵn sàng kích hoạt: kiểm kết nối IdP, nạp cây tổ chức, không còn lệch mức Cao.
t="$(date +%s.%N)"
ALIASES="$(jq -r '.buoc4.idps[].idpAlias' "$HO_SO")"
if [[ -n "$KHUNG_URL" ]]; then
  q="$(printf 'idp=%s&' $ALIASES)"
  curl -fsS -X POST "$KHUNG_URL/__khung/san-sang/$ID?${q%&}"
  ghi "Kiểm IdP, cây tổ chức, lệch dữ liệu (giả lập)" "ĐẠT" "$t" "khung giả lập ghi kết quả thay org-service, IdP thật"
else
  ok=1
  for al in $ALIASES; do
    goi POST "/api/v1/config/tenants/$ID/idp/test" "${TOKEN_QTTENANT:-$TOKEN_QTNT}" "{\"idpAlias\":\"$al\"}"
    [[ "$MA" == 200 ]] && jq -e ".success == true" "$TMP/than" >/dev/null || ok=0
  done
  if ((ok)); then ghi "Kiểm kết nối IdP" "ĐẠT" "$t" "cây tổ chức nạp bằng tay theo OPS-07 bước 4"
  else ghi "Kiểm kết nối IdP" "KHÔNG ĐẠT" "$t" "HTTP $MA $(loi_van_de)"; fi
fi

# kich_hoat <nội dung> <lý do> [khoá]
kich_hoat() {
  local t; t="$(date +%s.%N)"
  goi POST "/api/v1/config/tenants/$ID/activate" "$TOKEN_QTNT" "{\"reason\":\"$2\"}" application/json "$V" "${3:-}"
  local st; st="$(jq -r '.status // empty' "$TMP/than")"
  if [[ "$MA" == 200 && "$st" == DANG_VAN_HANH ]]; then V="$ETAG"; ghi "$1" "ĐẠT" "$t" "$st"
  elif [[ "$MA" == 503 ]]; then ghi "$1" "CHƯA CHẠY" "$t" "503: saga chưa cấu hình"; ket_thuc
  else ghi "$1" "KHÔNG ĐẠT" "$t" "HTTP $MA $(loi_van_de)"; ket_thuc; fi
}

# 7. Kích hoạt (SAN_SANG → DANG_VAN_HANH) và gửi lại cùng Idempotency-Key.
KHOA="$(khoa)"
V_TRUOC="$V"
kich_hoat "Kích hoạt: DANG_CAU_HINH → SAN_SANG → DANG_VAN_HANH" "Biên bản nghiệm thu thử VH-04" "$KHOA"
t="$(date +%s.%N)"
goi POST "/api/v1/config/tenants/$ID/activate" "$TOKEN_QTNT" '{"reason":"Biên bản nghiệm thu thử VH-04"}' application/json "$V_TRUOC" "$KHOA"
if [[ "$MA" == 200 && "$PHAT_LAI" == true ]]; then ghi "Gửi lại kích hoạt cùng khoá (idempotent)" "ĐẠT" "$t" "trả phản hồi đã lưu"
else ghi "Gửi lại kích hoạt cùng khoá (idempotent)" "KHÔNG ĐẠT" "$t" "HTTP $MA, Idempotency-Replayed=$PHAT_LAI"; fi

# 8. Tạm ngừng (DANG_VAN_HANH → TAM_NGUNG).
t="$(date +%s.%N)"
goi POST "/api/v1/config/tenants/$ID/suspend" "$TOKEN_QTNT" '{"reason":"Diễn tập tạm ngừng VH-04"}' application/json "$V"
st="$(jq -r '.status // empty' "$TMP/than")"
if [[ "$MA" == 200 && "$st" == TAM_NGUNG ]]; then V="$ETAG"; ghi "Tạm ngừng: DANG_VAN_HANH → TAM_NGUNG" "ĐẠT" "$t" "$st"
else ghi "Tạm ngừng: DANG_VAN_HANH → TAM_NGUNG" "KHÔNG ĐẠT" "$t" "HTTP $MA $(loi_van_de)"; ket_thuc; fi

# 9. Kích hoạt lại (TAM_NGUNG → DANG_VAN_HANH).
kich_hoat "Kích hoạt lại: TAM_NGUNG → DANG_VAN_HANH" "Kết thúc diễn tập tạm ngừng VH-04"

# 10. Nhật ký trạng thái và sự kiện.
t="$(date +%s.%N)"
goi GET "/api/v1/config/tenants/$ID" "$TOKEN_QTNT"
CHUOI="$(jq -r '[.statusLog // [] | reverse[] | .toStatus] | join(" → ")' "$TMP/than")"
if [[ -n "$KHUNG_URL" ]]; then
  SK="$(curl -fsS "$KHUNG_URL/__khung/su-kien/$ID")"
  n="$(jq -r '."sys.tenant.provisioned.v1" // 0' <<<"$SK")"
  [[ "$n" -ge 1 ]] && kq="ĐẠT" || kq="KHÔNG ĐẠT"
  ghi "Nhật ký trạng thái và sự kiện outbox" "$kq" "$t" "$CHUOI; sự kiện $(jq -r 'to_entries | map("\(.key)=\(.value)") | join(", ")' <<<"$SK")"
else
  ghi "Nhật ký trạng thái" "ĐẠT" "$t" "$CHUOI; kiểm sys.tenant.provisioned.v1 trên Kafka theo biên bản"
fi

# 11. Tuỳ chọn: tạm ngừng lại rồi chấm dứt (TAM_NGUNG → DA_CHAM_DUT, trạng thái cuối).
if ((CHAM_DUT)); then
  t="$(date +%s.%N)"
  goi POST "/api/v1/config/tenants/$ID/suspend" "$TOKEN_QTNT" '{"reason":"Diễn tập chấm dứt VH-04"}' application/json "$V"
  [[ "$MA" == 200 ]] && V="$ETAG"
  goi POST "/api/v1/config/tenants/$ID/terminate" "$TOKEN_QTNT" '{"reason":"Diễn tập chấm dứt VH-04","handoverRef":"BB-BG-THU-VH04"}' application/json "$V"
  st="$(jq -r '.status // empty' "$TMP/than")"
  if [[ "$MA" == 200 && "$st" == DA_CHAM_DUT ]]; then V="$ETAG"; ghi "Chấm dứt: TAM_NGUNG → DA_CHAM_DUT" "ĐẠT" "$t" "$st"
  else ghi "Chấm dứt: TAM_NGUNG → DA_CHAM_DUT" "KHÔNG ĐẠT" "$t" "HTTP $MA $(loi_van_de)"; fi
  t="$(date +%s.%N)"
  goi POST "/api/v1/config/tenants/$ID/activate" "$TOKEN_QTNT" '{"reason":"Thử kích hoạt sau chấm dứt"}' application/json "$V"
  if [[ "$MA" == 409 ]]; then ghi "Kích hoạt sau chấm dứt bị từ chối" "ĐẠT" "$t" "409 $(loi_van_de)"
  else ghi "Kích hoạt sau chấm dứt bị từ chối" "KHÔNG ĐẠT" "$t" "HTTP $MA"; fi
fi

# 12. Tuỳ chọn: huỷ một hồ sơ nháp (KHOI_TAO → DA_CHAM_DUT).
if ((HUY_THU)); then
  t="$(date +%s.%N)"
  CODE_HUY="${CODE}_huy"
  goi POST /api/v1/config/tenants "$TOKEN_QTNT" "$(phan '.tao' | jq -c --arg c "$CODE_HUY" '.code = $c | .name += " – hồ sơ huỷ" | .adminContact.email = ("quantri@" + ($c | gsub("_"; "-")) + ".thu-nghiem.gov.vn")')"
  if [[ "$MA" == 201 ]]; then
    HID="$(jq -r '.id' "$TMP/than")"
    goi DELETE "/api/v1/config/tenants/$HID" "$TOKEN_QTNT" "" "" "$ETAG"
    goi GET "/api/v1/config/tenants/$HID" "$TOKEN_QTNT"
    st="$(jq -r '.status' "$TMP/than")"
    [[ "$st" == DA_CHAM_DUT ]] && kq="ĐẠT" || kq="KHÔNG ĐẠT"
    ghi "Huỷ hồ sơ nháp: KHOI_TAO → DA_CHAM_DUT" "$kq" "$t" "$st"
  else
    ghi "Huỷ hồ sơ nháp: KHOI_TAO → DA_CHAM_DUT" "KHÔNG ĐẠT" "$t" "HTTP $MA $(loi_van_de)"
  fi
fi

ket_thuc
