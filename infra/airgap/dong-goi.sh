#!/usr/bin/env bash
# Đóng gói bản LTS cho mô hình cách ly (INF-23; TL-04 mục 9.9, OPS-12, SEC-45, SEC-38, SEC-55).
# Trình tự theo TL-04: ảnh theo digest dạng thư mục OCI (oras copy --to-oci-layout), chữ ký, SBOM, provenance
# (cosign save), Helm chart (helm package), kho GitOps (git bundle), cơ sở dữ liệu Trivy, tệp .cvd ClamAV, công cụ và
# tài liệu phía khách hàng, bản kê MANIFEST.sha256 ký bằng khoá phát hành ngoại tuyến (cosign sign-blob), nén tar --zstd.
#
# Cách gọi:
#   dong-goi.sh --phien-ban okr-lts-2027.1.0 [--ke deploy/release/<phiên bản>/images.yaml] [--ra <thư mục>]
#               [--khoa hsm://release-offline] [--ref <thẻ Git>] [--trivy-db <thư mục>] [--clamav <thư mục>]
#               [--chart vt-platform]... [--plain-http]
# Khoá mặc định là khoá phát hành ngoại tuyến trong HSM (tham chiếu hsm://release-offline, cosign dịch qua PKCS#11 ở
# máy đóng gói); repo không bao giờ chứa khoá. Khoá tệp chỉ dùng trong kiểm thử, mật khẩu qua biến COSIGN_PASSWORD.
# Không có trivy thì phải đưa --trivy-db (thư mục cơ sở dữ liệu đã tải); không có --clamav thì dừng: gói thiếu cơ sở
# dữ liệu quét là gói không dùng được trong mạng cách ly. --plain-http chỉ dùng cho registry thử cục bộ.
set -euo pipefail

GOC=$(cd "$(dirname "$0")/../.." && pwd)
PHIEN_BAN=""; KE=""; RA="$PWD"; KHOA="hsm://release-offline"; REF=HEAD; TRIVY_DB=""; CLAMAV=""; CHARTS=(); HTTP=()
while [ $# -gt 0 ]; do
  case "$1" in
    --phien-ban) PHIEN_BAN=$2; shift 2 ;;
    --ke) KE=$2; shift 2 ;;
    --ra) RA=$2; shift 2 ;;
    --khoa) KHOA=$2; shift 2 ;;
    --ref) REF=$2; shift 2 ;;
    --trivy-db) TRIVY_DB=$2; shift 2 ;;
    --clamav) CLAMAV=$2; shift 2 ;;
    --chart) CHARTS+=("$2"); shift 2 ;;
    --plain-http) HTTP=(--from-plain-http); shift ;;
    *) echo "dong-goi: tham số lạ: $1" >&2; exit 2 ;;
  esac
done
[[ "$PHIEN_BAN" =~ ^[a-z0-9][a-z0-9.-]*$ ]] || { echo "dong-goi: cần --phien-ban (chữ thường, số, dấu chấm, gạch ngang)" >&2; exit 2; }
KE=${KE:-$GOC/deploy/release/$PHIEN_BAN/images.yaml}
[ ${#CHARTS[@]} -gt 0 ] || CHARTS=(vt-platform)
for c in oras cosign helm git sha256sum zstd python3; do
  command -v "$c" >/dev/null || { echo "dong-goi: thiếu công cụ $c" >&2; exit 2; }
done
[ -f "$KE" ] || { echo "dong-goi: không thấy tệp kê ảnh $KE (sinh bằng infra/airgap/ke-anh.py)" >&2; exit 2; }
if [ -z "$CLAMAV" ] || ! compgen -G "$CLAMAV/*.cvd" >/dev/null; then
  echo "dong-goi: cần --clamav <thư mục có *.cvd>" >&2; exit 2
fi
[ -n "$TRIVY_DB" ] || command -v trivy >/dev/null || { echo "dong-goi: cần trivy hoặc --trivy-db" >&2; exit 2; }
COSIGN_HTTP=(); [ ${#HTTP[@]} -eq 0 ] || COSIGN_HTTP=(--allow-insecure-registry --allow-http-registry)

D="$RA/$PHIEN_BAN"
[ ! -e "$D" ] || { echo "dong-goi: $D đã có, không ghi đè" >&2; exit 2; }
mkdir -p "$D"/{images,signatures,charts,trivy-db,clamav,tools/kyverno,docs}

# 1. Ảnh và chữ ký. Thẻ trong thư mục OCI là <tên ảnh>_<12 ký tự đầu digest>; images/anh.tsv ghi thẻ → tham chiếu gốc
# để tools/load-images.sh đổi tên theo kho của khách hàng.
python3 - "$KE" > "$D/images/anh.tsv" <<'PY'
import re, sys, yaml
k = yaml.safe_load(open(sys.argv[1], encoding="utf-8"))
seen = set()
for r in k["images"]:
    if not re.fullmatch(r"[^\s@]+@sha256:[0-9a-f]{64}", r):
        sys.exit(f"dong-goi: ảnh không theo digest: {r}")
    ten = r.split("@")[0].rsplit("/", 1)[-1].split(":")[0]
    the = f"{ten}_{r.split('sha256:')[1][:12]}"
    if the in seen:
        sys.exit(f"dong-goi: trùng thẻ {the}")
    seen.add(the)
    print(f"{the}\t{r}")
PY
SO=0
while IFS=$'\t' read -r the anh; do
  oras copy "${HTTP[@]}" --to-oci-layout "$anh" "$D/images:$the" >/dev/null
  cosign save "${COSIGN_HTTP[@]}" "$anh" --dir "$D/signatures/$the"
  SO=$((SO+1))
done < "$D/images/anh.tsv"
echo "dong-goi: $SO ảnh và chữ ký"

# 2. Chart: chỉ chart được nêu (mặc định vt-platform, đã gồm chart con). Không đóng gói observability-mgmt
# (Grafana, Loki, Tempo giấy phép AGPL) khi pháp chế chưa duyệt (TL-04 mục 10.1).
for c in "${CHARTS[@]}"; do
  [ -d "$GOC/deploy/charts/$c/charts" ] || [ ! -f "$GOC/deploy/charts/$c/Chart.lock" ] \
    || helm dependency build "$GOC/deploy/charts/$c" >/dev/null
  helm package "$GOC/deploy/charts/$c" -d "$D/charts" >/dev/null
done

# 3. Kho GitOps tại thẻ phát hành.
git -C "$GOC" bundle create "$D/gitops.bundle" "$REF" 2>/dev/null
git -C "$GOC" rev-parse "$REF^{commit}" > "$D/gitops.commit"

# 4. Cơ sở dữ liệu quét tại thời điểm phát hành.
if [ -n "$TRIVY_DB" ]; then cp -r "$TRIVY_DB"/. "$D/trivy-db/"; else trivy image --download-db-only --cache-dir "$D/trivy-db"; fi
cp "$CLAMAV"/*.cvd "$D/clamav/"

# 5. Công cụ và tài liệu phía khách hàng.
cp -r "$GOC/infra/airgap/goi/." "$D/"
cp "$GOC/docs/quan-tri-cai-dat/cach-ly.md" "$D/docs/"
cp "$GOC/deploy/charts/vt-platform/values-airgap.yaml" "$D/docs/"
cp "$GOC"/deploy/policies/vt-*.yaml "$D/tools/kyverno/"
echo "$PHIEN_BAN" > "$D/PHIEN-BAN"

# 6. Bản kê và chữ ký.
( cd "$D" && find . -type f ! -name 'MANIFEST*' -exec sha256sum {} + | sort -k2 > MANIFEST.sha256 )
cosign sign-blob --yes --tlog-upload=false --key "$KHOA" --output-signature "$D/MANIFEST.sha256.sig" "$D/MANIFEST.sha256" >/dev/null
tar --zstd -cf "$RA/$PHIEN_BAN.tar.zst" -C "$RA" "$PHIEN_BAN"
echo "dong-goi: $RA/$PHIEN_BAN.tar.zst ($(wc -l < "$D/MANIFEST.sha256") tệp trong bản kê)"
