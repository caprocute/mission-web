#!/usr/bin/env bash
# Kiểm thử kịch bản diễn tập DR của QA-15: shellcheck, dr-chuyen-vung.sh với kubectl giả (chạy thử không đổi gì,
# --confirm đổi đúng cụm, dừng khi phía đích không phải bản sao, DR-10 khi Hoà Lạc mất, DR-16 cần cả hai TTDL),
# rồi mô phỏng hai TTDL bằng PostgreSQL cục bộ (bỏ qua khi không có PostgreSQL hoặc KHONG_PG=1) và DR-13 với PowerDNS
# cục bộ (bỏ qua khi không có pdns_server hoặc KHONG_PDNS=1).
# Mẫu "lệnh && ok || hong" là chủ ý: ok chỉ in và cộng đếm, không thất bại.
# shellcheck disable=SC2015
set -euo pipefail
cd "$(dirname "$0")/.."
DAT=0; HONG=0
ok() { echo "  [ĐẠT] $*"; DAT=$((DAT + 1)); }
hong() { echo "  [HỎNG] $*"; HONG=$((HONG + 1)); }
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT

shellcheck -x dr-chuyen-vung.sh mo-phong-pg-hai-ttdl.sh dr13-geodns-cuc-bo.sh tests/*.sh && ok "shellcheck" || hong "shellcheck"

export GIA=$W/gia
mkdir -p "$W/bin" && ln -s "$PWD/tests/kubectl-gia.sh" "$W/bin/kubectl"
sed -e 's/^CTX_HL=.*/CTX_HL=kind-vt-dr-hl/' -e 's/^CTX_SN=.*/CTX_SN=kind-vt-dr-sn/' -e 's/^CHE_DO=.*/CHE_DO=truc-tiep/' \
  -e 's/^CUM_PG=.*/CUM_PG="pg-core pg-ledger"/' moi-truong.mau.env > "$W/kind.env"
dung_trang_thai() {   # $1 TTDL đang primary
  rm -rf "$GIA"; mkdir -p "$GIA/kind-vt-dr-hl" "$GIA/kind-vt-dr-sn"
  for c in pg-core pg-ledger; do
    if [ "$1" = hl ]; then echo f > "$GIA/kind-vt-dr-hl/$c-hl"; echo t > "$GIA/kind-vt-dr-sn/$c-sn"
    else echo t > "$GIA/kind-vt-dr-hl/$c-hl"; echo f > "$GIA/kind-vt-dr-sn/$c-sn"; fi
  done
}
chay() { PATH="$W/bin:$PATH" ./dr-chuyen-vung.sh "$@"; }

dung_trang_thai hl
chay dr10 --env "$W/kind.env" > "$W/thu.log" 2>&1 && ! grep -q 'patch\|annotate' "$GIA/goi.log" \
  && grep -q 'CHẠY THỬ' "$W/thu.log" && ok "chạy thử chỉ in kế hoạch, không vá CR nào" || hong "chạy thử"

dung_trang_thai hl
if chay dr10 --env "$W/kind.env" --nhat-ky "$W/dr10.jsonl" --confirm > "$W/dr10.log" 2>&1 \
   && [ "$(cat "$GIA/kind-vt-dr-sn/pg-core-sn")" = f ] && [ "$(cat "$GIA/kind-vt-dr-sn/pg-ledger-sn")" = f ] \
   && grep -q 'annotate cluster pg-core-hl --overwrite cnpg.io/fencedInstances=\["\*"\]' "$GIA/goi.log" \
   && grep -q 'annotate cluster pg-core-hl cnpg.io/fencedInstances-' "$GIA/goi.log" \
   && grep -q 'scale kafkamirrormaker2 mm2-hl-to-sn --replicas=0' "$GIA/goi.log" \
   && [ "$(grep -c '"buoc"' "$W/dr10.jsonl")" -ge 6 ]; then
  ok "DR-10 --confirm: rào chắn Hoà Lạc, phía Nam thăng cấp, dừng MirrorMaker 2, ghi $(grep -c '"buoc"' "$W/dr10.jsonl") mốc"
else hong "DR-10 --confirm"; tail -5 "$W/dr10.log"; fi

dung_trang_thai hl; touch "$GIA/kind-vt-dr-hl/mat"
chay dr10 --env "$W/kind.env" --nhat-ky "$W/dr10b.jsonl" --confirm > "$W/dr10b.log" 2>&1 \
  && [ "$(cat "$GIA/kind-vt-dr-sn/pg-core-sn")" = f ] && grep -q 'khong-toi-duoc-coi-nhu-da-rao' "$W/dr10b.jsonl" \
  && ! grep -q 'kind-vt-dr-hl.*patch' "$GIA/goi.log" \
  && ok "DR-10 khi Hoà Lạc mất hẳn: coi như đã rào chắn, chỉ vá phía Nam" || { hong "DR-10 Hoà Lạc mất"; tail -5 "$W/dr10b.log"; }

dung_trang_thai sn
chay dr10 --env "$W/kind.env" --confirm > "$W/sai.log" 2>&1 \
  && hong "DR-10 vẫn chạy khi phía Nam đã là primary" || ok "DR-10 dừng khi phía đích không phải bản sao"

dung_trang_thai sn
chay dr16 --env "$W/kind.env" --nhat-ky "$W/dr16.jsonl" --confirm > "$W/dr16.log" 2>&1 \
  && [ "$(cat "$GIA/kind-vt-dr-hl/pg-core-hl")" = f ] && [ "$(cat "$GIA/kind-vt-dr-sn/pg-core-sn")" = t ] \
  && grep -q 'kiem-lech' "$W/dr16.jsonl" \
  && ok "DR-16 --confirm: chờ bắt kịp, Hoà Lạc primary, phía Nam trở lại bản sao" || { hong "DR-16"; tail -5 "$W/dr16.log"; }

dung_trang_thai sn; touch "$GIA/kind-vt-dr-sn/mat"
chay dr16 --env "$W/kind.env" --confirm > /dev/null 2>&1 && hong "DR-16 chạy khi thiếu một TTDL" \
  || ok "DR-16 từ chối khi không tới được cả hai TTDL"

if [ "${KHONG_PG:-0}" = 1 ] || ! find /usr/lib/postgresql -name pg_rewind 2>/dev/null | grep -q .; then
  echo "  [BỎ QUA] mô phỏng PostgreSQL hai TTDL: không có PostgreSQL"
else
  if ./mo-phong-pg-hai-ttdl.sh --chay-truoc 8 --chay-phia-nam 5 --bien-ban "$W/a.json" > "$W/a.log" 2>&1 \
     && python3 -c 'import json,sys; k=json.load(open(sys.argv[1])); assert k["dr10"]["rpoGiay"] <= 900 and k["dr16"]["giaoDichMat"] == 0' "$W/a.json"; then
    ok "mô phỏng DR-10/DR-16: $(grep -E 'RPO:' "$W/a.log" | sed 's/^\[[^]]*\] //')"
  else hong "mô phỏng DR-10/DR-16"; tail -5 "$W/a.log"; fi
  if ./mo-phong-pg-hai-ttdl.sh --chay-truoc 5 --wal-dut-truoc 20 --chay-phia-nam 3 --bien-ban "$W/b.json" > "$W/b.log" 2>&1 \
     && python3 -c 'import json,sys; k=json.load(open(sys.argv[1]))["dr10"]; assert k["giaoDichMat"] > 0 and 15 <= k["rpoGiay"] <= 65' "$W/b.json"; then
    ok "mô phỏng luồng WAL đứt 20 s trước sự cố: RPO bị chặn bởi archive_timeout 60 s ($(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["dr10"]["rpoGiay"])' "$W/b.json") s)"
  else hong "mô phỏng WAL đứt"; tail -5 "$W/b.log"; fi
fi

if [ "${KHONG_PDNS:-0}" = 1 ] || ! command -v pdns_server >/dev/null || ! command -v dig >/dev/null; then
  echo "  [BỎ QUA] DR-13 GeoDNS cục bộ: không có pdns_server hoặc dig"
elif ./dr13-geodns-cuc-bo.sh --bien-ban "$W/c.json" > "$W/c.log" 2>&1; then
  ok "DR-13 GeoDNS cục bộ: $(python3 -c 'import json,sys; k=json.load(open(sys.argv[1])); print("PowerDNS", k["powerdns"], "chuyển sau", k["chuyenGiay"], "s, chờ lớn nhất", k["choLonNhatGiay"], "s")' "$W/c.json")"
else hong "DR-13 GeoDNS cục bộ"; grep HỎNG "$W/c.log" || tail -5 "$W/c.log"; fi

echo; echo "Tổng: $DAT đạt, $HONG hỏng"
[ "$HONG" = 0 ]
