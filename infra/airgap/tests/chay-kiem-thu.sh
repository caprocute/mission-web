#!/usr/bin/env bash
# shellcheck disable=SC2015  # mẫu "lệnh && ok || hong" chủ ý: ok/hong luôn trả 0
# Kiểm thử bộ công cụ cài cách ly (INF-23). Phần tĩnh: shellcheck, tệp kê ảnh (theo digest, từ chối thẻ và digest giả),
# đổi khoá Kyverno, đổi registry trên bản render (cả manifest hook). Phần chạy thử (cần Docker): hai registry cục bộ làm registry
# phát hành và Harbor của khách hàng, ảnh thử ký bằng khoá cosign sinh lúc chạy (không commit), đóng gói, kiểm gói, sửa
# một byte thì kiểm gói và quy trình nạp dừng, nạp ảnh và chữ ký sang registry khách hàng rồi kiểm chữ ký ở đó.
# Cần helm, shellcheck, python3 + PyYAML; phần chạy thử cần thêm docker, oras, cosign, zstd. KHONG_DOCKER=1 bỏ phần chạy thử.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 2
GOC=$(cd ../.. && pwd); PF="$GOC/deploy/charts/vt-platform"
W=$(mktemp -d); DAT=0; HONG=0; DS=()
don() { for c in "${DS[@]}"; do docker rm -f "$c" >/dev/null 2>&1; done; rm -rf "${W:?}"; }
trap don EXIT
ok()   { echo "  [ĐẠT] $*"; DAT=$((DAT+1)); }
hong() { echo "  [HỎNG] $*"; HONG=$((HONG+1)); }

shellcheck dong-goi.sh goi/tools/*.sh tests/*.sh && ok "shellcheck" || hong "shellcheck"

# Tệp kê ảnh từ bản render values-airgap (digest giả của CI chỉ được nhận khi có --cho-phep-gia).
[ -d "$PF/charts" ] || bash "$PF/ci/cap-nhat-phu-thuoc.sh" >/dev/null
helm template vt "$PF" -f "$PF/values-airgap.yaml" -f "$PF/ci/values-ci.yaml" > "$W/ag.yaml" 2>"$W/loi" || cat "$W/loi"
python3 ke-anh.py --phien-ban okr-lts-thu --render "$W/ag.yaml" --ra "$W/rel" --cho-phep-gia > "$W/ke" 2>&1 \
  && ok "tệp kê ảnh: $(grep -o '[0-9]* ảnh theo digest' "$W/ke")" || { hong "tệp kê ảnh"; cat "$W/ke"; }
python3 ke-anh.py --phien-ban okr-lts-thu --render "$W/ag.yaml" --ra "$W/rel2" > /dev/null 2>&1 \
  && hong "digest giả lọt tệp kê" || ok "từ chối digest giả chưa được pipeline thay"
printf 'kind: Pod\nmetadata: {name: x}\nspec: {containers: [{name: a, image: "harbor.vt.local/okr/a:1.0"}]}\n' > "$W/the.yaml"
python3 ke-anh.py --phien-ban t --render "$W/the.yaml" --ra "$W/rel3" > "$W/ke3" 2>&1 \
  && hong "ảnh theo thẻ lọt tệp kê" || ok "từ chối ảnh theo thẻ ($(grep -c 'LỖI' "$W/ke3") lỗi)"

# Đổi khoá Kyverno sang khoá công khai phát hành.
openssl ecparam -name prime256v1 -genkey -noout -out "$W/k.pem" 2>/dev/null && openssl ec -in "$W/k.pem" -pubout -out "$W/k.pub" 2>/dev/null
mkdir -p "$W/kv"
python3 goi/tools/doi-khoa-kyverno.py --khoa "$W/k.pub" --registry-goc harbor.vt.local --kho harbor.qp.local --ra "$W/kv" \
  "$GOC"/deploy/policies/vt-*.yaml > /dev/null && python3 - "$W/kv" <<'EOF' && ok "Kyverno: khoá kms đổi thành publicKeys, registry đổi sang Harbor khách hàng, điều kiện SLSA giữ nguyên" || hong "đổi khoá Kyverno"
import glob, sys, yaml
t = "".join(open(f).read() for f in glob.glob(sys.argv[1] + "/*.yaml"))
assert "hashivault://" not in t and "harbor.vt.local" not in t and "BEGIN PUBLIC KEY" in t
p = yaml.safe_load(open(sys.argv[1] + "/vt-require-signed-images.yaml"))
v = p["spec"]["rules"][0]["verifyImages"][0]
assert v["imageReferences"] == ["harbor.qp.local/*"]
assert v["attestors"][0]["entries"][0]["keys"]["publicKeys"].startswith("-----BEGIN PUBLIC KEY-----")
assert v["attestations"][0]["conditions"][0]["all"][1]["value"] == "https://jenkins.vt.local/okr-builder"
EOF

# Đổi registry trên bản render (cả manifest hook): mọi ảnh trỏ sang Harbor khách hàng; ảnh thiếu trong bảng thì dừng.
python3 - "$W/rel/okr-lts-thu/images.yaml" > "$W/anh-da-nap.tsv" <<'EOF'
import sys, yaml
for r in yaml.safe_load(open(sys.argv[1]))["images"]:
    dau, _, con = r.partition("/")
    duong = con if ("." in dau or ":" in dau) and con else r
    print(f"{r}\tharbor.qp.local/okr/{duong}")
EOF
python3 goi/tools/doi-registry.py "$W/anh-da-nap.tsv" < "$W/ag.yaml" > "$W/ag-kh.yaml" 2>"$W/loi"
python3 - "$W/ag-kh.yaml" "$W/rel/okr-lts-thu/images.yaml" <<'EOF' && ok "đổi registry: mọi ảnh trong bản render trỏ Harbor khách hàng, digest giữ nguyên" || { hong "đổi registry"; tail -3 "$W/loi"; }
import re, sys, yaml
t = open(sys.argv[1]).read()
anh = set(re.findall(r"image:\s*[\"']?(\S+@sha256:[0-9a-f]{64})", t))
assert anh and all(a.startswith("harbor.qp.local/okr/") for a in anh), sorted(a for a in anh if not a.startswith("harbor.qp.local/"))
goc = {r.split("@")[1] for r in yaml.safe_load(open(sys.argv[2]))["images"]}
assert {a.split("@")[1] for a in anh} <= goc
EOF
head -n -1 "$W/anh-da-nap.tsv" > "$W/thieu.tsv"
python3 goi/tools/doi-registry.py "$W/thieu.tsv" < "$W/ag.yaml" > /dev/null 2>&1 \
  && hong "đổi registry để lọt ảnh chưa có trong gói" || ok "đổi registry dừng khi có ảnh chưa nạp"

# Chạy thử với registry cục bộ.
if [ "${KHONG_DOCKER:-0}" = 1 ] || ! docker info >/dev/null 2>&1; then
  echo "  [BỎ QUA] chạy thử với registry cục bộ: không có Docker"
else
  for c in oras cosign zstd; do command -v $c >/dev/null || { hong "thiếu $c cho phần chạy thử"; }; done
  REG=${REGISTRY_IMAGE:-mirror.gcr.io/library/registry:2}; BB=${BUSYBOX_IMAGE:-mirror.gcr.io/library/busybox:1.37}
  docker pull -q "$REG" >/dev/null && docker pull -q "$BB" >/dev/null
  for p in nguon khach; do
    DS+=("ag-$p-$$"); docker run -d --name "ag-$p-$$" -p 127.0.0.1::5000 "$REG" >/dev/null
  done
  NGUON=127.0.0.1:$(docker port "ag-nguon-$$" 5000 | head -1 | cut -d: -f2)
  KHACH=127.0.0.1:$(docker port "ag-khach-$$" 5000 | head -1 | cut -d: -f2)
  sleep 2
  docker tag "$BB" "$NGUON/okr/busybox:thu" && docker push -q "$NGUON/okr/busybox:thu" >/dev/null
  DG=$(oras resolve --plain-http "$NGUON/okr/busybox:thu")
  ANH="$NGUON/okr/busybox@$DG"
  export COSIGN_PASSWORD=thu-chi-dung-mot-lan
  (cd "$W" && cosign generate-key-pair >/dev/null 2>&1)
  cosign sign --yes --tlog-upload=false --allow-insecure-registry --allow-http-registry --key "$W/cosign.key" "$ANH" >/dev/null 2>&1
  mkdir -p "$W/rt/okr-lts-thu" "$W/trivy" "$W/clamav" "$W/ra"
  printf 'release: okr-lts-thu\nimages:\n  - %s\n' "$ANH" > "$W/rt/okr-lts-thu/images.yaml"
  echo "db giả cho kiểm thử" > "$W/trivy/trivy.db"; echo "cvd giả cho kiểm thử" > "$W/clamav/main.cvd"
  if bash dong-goi.sh --phien-ban okr-lts-thu --ke "$W/rt/okr-lts-thu/images.yaml" --ra "$W/ra" --khoa "$W/cosign.key" \
       --trivy-db "$W/trivy" --clamav "$W/clamav" --plain-http > "$W/dg" 2>&1; then
    ok "đóng gói: $(tail -1 "$W/dg" | sed 's#.*/##')"
  else hong "đóng gói"; tail -5 "$W/dg"; echo; echo "Tổng: $DAT đạt, $HONG hỏng"; exit 1; fi
  mkdir -p "$W/kh" && tar --zstd -xf "$W/ra/okr-lts-thu.tar.zst" -C "$W/kh"
  G="$W/kh/okr-lts-thu"
  for f in MANIFEST.sha256 MANIFEST.sha256.sig gitops.bundle charts images/anh.tsv signatures trivy-db/trivy.db clamav/main.cvd tools/kiem-goi.sh tools/kyverno/vt-require-signed-images.yaml docs/cach-ly.md; do
    [ -e "$G/$f" ] || hong "gói thiếu $f"
  done
  git -C "$W" clone -q "$G/gitops.bundle" kho 2>/dev/null && ok "gitops.bundle clone được" || hong "gitops.bundle"
  "$G/tools/kiem-goi.sh" --khoa "$W/cosign.pub" > "$W/kg" 2>&1 && ok "kiểm gói: $(cat "$W/kg")" || { hong "kiểm gói"; cat "$W/kg"; }
  "$G/tools/kiem-goi.sh" --khoa "$W/k.pub" > /dev/null 2>&1 && hong "khoá lạ vẫn qua kiểm gói" || ok "khoá công khai khác: kiểm gói dừng"
  cp -a "$G" "$W/sua" && printf 'X' | dd of="$W/sua/clamav/main.cvd" bs=1 seek=0 conv=notrunc 2>/dev/null
  if "$W/sua/tools/kiem-goi.sh" --khoa "$W/cosign.pub" > /dev/null 2>&1 \
     || ( "$W/sua/tools/kiem-goi.sh" --khoa "$W/cosign.pub" >/dev/null 2>&1 && "$W/sua/tools/load-images.sh" --kho "$KHACH/qp" --plain-http ) >/dev/null 2>&1; then
    hong "sửa một byte mà kiểm gói vẫn đạt"
  else ok "sửa một byte: sha256sum -c báo lỗi, quy trình nạp dừng"; fi
  mkdir -p "$W/sua2" && cp -a "$G" "$W/sua2/g" && echo x > "$W/sua2/g/tools/them.sh"
  "$W/sua2/g/tools/kiem-goi.sh" --khoa "$W/cosign.pub" > /dev/null 2>&1 && hong "tệp lạ lọt" || ok "tệp lạ ngoài bản kê: kiểm gói dừng"
  if "$G/tools/load-images.sh" --kho "$KHACH/qp" --plain-http > "$W/ld" 2>&1; then
    ok "nạp ảnh: $(tail -1 "$W/ld" | cut -d'(' -f1)"
    DICH=$(cut -f2 "$W/kh/anh-da-nap.tsv")
    [ "${DICH#*@}" = "$DG" ] && ok "digest giữ nguyên sau khi nạp" || hong "digest đổi: $DICH"
    cosign verify --insecure-ignore-tlog=true --allow-insecure-registry --allow-http-registry --key "$W/cosign.pub" "$DICH" >/dev/null 2>&1 \
      && ok "chữ ký kiểm được ở registry khách hàng bằng khoá công khai phát hành" || hong "chữ ký ở registry khách hàng"
    cosign verify --insecure-ignore-tlog=true --allow-insecure-registry --allow-http-registry --key "$W/k.pub" "$DICH" >/dev/null 2>&1 \
      && hong "khoá lạ vẫn kiểm được" || ok "khoá khác bị từ chối khi kiểm ảnh"
  else hong "nạp ảnh"; tail -5 "$W/ld"; fi
  # cai-dat.sh từ gói (chỉ render): bảng đối chiếu thử phủ mọi ảnh của bản render CI.
  cp "$W/anh-da-nap.tsv" "$W/kh/anh-da-nap.tsv"
  if "$G/tools/cai-dat.sh" --kho harbor.qp.local/okr --khoa "$W/cosign.pub" --values "$PF/ci/values-ci.yaml" \
       --chi-render "$W/cai.yaml" > "$W/cd" 2>&1 && ! grep -Eq 'image: "?harbor\.vt\.local' "$W/cai.yaml" \
       && grep -q 'BEGIN PUBLIC KEY' "$W/kh/kyverno-khach-hang/vt-require-signed-images.yaml"; then
    ok "cai-dat.sh: render từ chart trong gói, ảnh trỏ Harbor khách hàng, Kyverno dùng khoá phát hành"
  else hong "cai-dat.sh"; tail -5 "$W/cd"; fi
fi

echo; echo "Tổng: $DAT đạt, $HONG hỏng"
[ "$HONG" = 0 ]
