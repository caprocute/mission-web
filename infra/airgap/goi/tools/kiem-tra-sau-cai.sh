#!/usr/bin/env bash
# Bộ kiểm tra sau cài đặt bản cách ly (INF-23; TL-04 mục 9.9). Chạy lần lượt: kube-bench (CIS), Hubble không có luồng
# ra ngoài cụm, kiểm chữ ký mọi ảnh đang chạy bằng khoá công khai phát hành, kiểm thử khói, PERF-01 rút gọn, diễn tập
# khôi phục một cụm PostgreSQL; xuất biên bản Markdown theo mẫu. Mục nào thiếu công cụ hoặc quyền thì ghi
# "KHÔNG CHẠY ĐƯỢC" kèm lý do thay vì bỏ qua lặng lẽ; biên bản chỉ "ĐẠT" khi mọi mục đều đạt.
# Cách gọi: tools/kiem-tra-sau-cai.sh --ra <thư mục> [--khoa vt-release.pub] [--url-khoi https://okr.qp.local]
#           [--anh-kube-bench <ảnh theo digest trong Harbor nội bộ>] [--pg-cluster pg-core] [--bo-qua-perf]
set -uo pipefail
RA=""; KHOA=""; URL=""; KB=""; PG=pg-core; BO_PERF=0
while [ $# -gt 0 ]; do
  case "$1" in
    --ra) RA=$2; shift 2 ;;
    --khoa) KHOA=$2; shift 2 ;;
    --url-khoi) URL=$2; shift 2 ;;
    --anh-kube-bench) KB=$2; shift 2 ;;
    --pg-cluster) PG=$2; shift 2 ;;
    --bo-qua-perf) BO_PERF=1; shift ;;
    *) echo "kiem-tra-sau-cai: tham số lạ: $1" >&2; exit 2 ;;
  esac
done
[ -n "$RA" ] || { echo "kiem-tra-sau-cai: cần --ra" >&2; exit 2; }
mkdir -p "$RA"; BB="$RA/bien-ban-sau-cai.md"; KQ=()
ghi() { KQ+=("| $1 | $2 | $3 |"); }   # mục, kết quả, ghi chú
co() { command -v "$1" >/dev/null; }
cum() { co kubectl && kubectl version --request-timeout=5s >/dev/null 2>&1; }

# 1. CIS Kubernetes benchmark.
if ! cum; then ghi "kube-bench (CIS)" "KHÔNG CHẠY ĐƯỢC" "không kết nối được cụm"
elif [ -z "$KB" ]; then ghi "kube-bench (CIS)" "KHÔNG CHẠY ĐƯỢC" "thiếu --anh-kube-bench"
elif sed "s#ANH_KUBE_BENCH#$KB#" "$(dirname "$0")/kube-bench-job.yaml" | kubectl apply -f - >/dev/null 2>&1; then
  kubectl wait -n kube-system --for=condition=complete job/kube-bench --timeout=300s >/dev/null 2>&1
  kubectl logs -n kube-system job/kube-bench > "$RA/kube-bench.txt" 2>&1
  if grep -q '\[FAIL\]' "$RA/kube-bench.txt"; then ghi "kube-bench (CIS)" "KHÔNG ĐẠT" "$(grep -c '\[FAIL\]' "$RA/kube-bench.txt") mục FAIL, xem kube-bench.txt"
  else ghi "kube-bench (CIS)" "ĐẠT" "kube-bench.txt"; fi
else ghi "kube-bench (CIS)" "KHÔNG CHẠY ĐƯỢC" "không tạo được Job kube-bench"; fi

# 2. Không có luồng ra ngoài cụm (Hubble, 10 phút gần nhất).
if ! co hubble; then ghi "Không có luồng ra ngoài (Hubble)" "KHÔNG CHẠY ĐƯỢC" "thiếu hubble CLI"
elif hubble observe --since 10m --to-identity world -o compact > "$RA/hubble-world.txt" 2>&1; then
  n=$(grep -c . "$RA/hubble-world.txt")
  if [ "$n" = 0 ]; then ghi "Không có luồng ra ngoài (Hubble)" "ĐẠT" "0 luồng tới identity world"
  else ghi "Không có luồng ra ngoài (Hubble)" "KHÔNG ĐẠT" "$n luồng, xem hubble-world.txt"; fi
else ghi "Không có luồng ra ngoài (Hubble)" "KHÔNG CHẠY ĐƯỢC" "hubble relay không trả lời"; fi

# 3. Chữ ký của mọi ảnh đang chạy.
if ! cum; then ghi "Chữ ký mọi ảnh đang chạy" "KHÔNG CHẠY ĐƯỢC" "không kết nối được cụm"
elif [ ! -f "$KHOA" ] || ! co cosign; then ghi "Chữ ký mọi ảnh đang chạy" "KHÔNG CHẠY ĐƯỢC" "thiếu --khoa hoặc cosign"
else
  kubectl get pods -A -l '!job-name' -o jsonpath='{range .items[*]}{range .spec.containers[*]}{.image}{"\n"}{end}{end}' \
    | sort -u > "$RA/anh-dang-chay.txt"
  hong=0
  while read -r anh; do
    cosign verify --insecure-ignore-tlog=true --key "$KHOA" "$anh" >/dev/null 2>&1 || { echo "$anh" >> "$RA/anh-khong-ky.txt"; hong=$((hong+1)); }
  done < "$RA/anh-dang-chay.txt"
  if [ "$hong" = 0 ]; then ghi "Chữ ký mọi ảnh đang chạy" "ĐẠT" "$(wc -l < "$RA/anh-dang-chay.txt") ảnh"
  else ghi "Chữ ký mọi ảnh đang chạy" "KHÔNG ĐẠT" "$hong ảnh, xem anh-khong-ky.txt"; fi
fi

# 4. Kiểm thử khói: trang web và API sức khoẻ qua cổng.
if [ -z "$URL" ]; then ghi "Kiểm thử khói" "KHÔNG CHẠY ĐƯỢC" "thiếu --url-khoi"
elif curl -fsS -o /dev/null "$URL/" && curl -fsS -o /dev/null "$URL/api/v1/health"; then ghi "Kiểm thử khói" "ĐẠT" "$URL"
else ghi "Kiểm thử khói" "KHÔNG ĐẠT" "$URL không trả 2xx"; fi

# 5. PERF-01 rút gọn (ngưỡng p95 của TL-04 mục 7.3, tải nhỏ). k6 giấy phép AGPL nên không đi kèm gói; dùng k6 của
# khách hàng nếu có.
if [ "$BO_PERF" = 1 ]; then ghi "PERF-01 rút gọn" "KHÔNG CHẠY ĐƯỢC" "bỏ qua theo tham số (giai đoạn phát triển chưa đo tải, YC-PT-19)"
elif ! co k6 || [ -z "$URL" ]; then ghi "PERF-01 rút gọn" "KHÔNG CHẠY ĐƯỢC" "thiếu k6 hoặc --url-khoi"
elif K6_URL=$URL k6 run --quiet "$(dirname "$0")/perf-01-rut-gon.js" > "$RA/perf-01.txt" 2>&1; then ghi "PERF-01 rút gọn" "ĐẠT" "perf-01.txt"
else ghi "PERF-01 rút gọn" "KHÔNG ĐẠT" "xem perf-01.txt"; fi

# 6. Diễn tập khôi phục: dựng cụm CNPG mới từ bản sao lưu gần nhất của cụm $PG, chờ sẵn sàng rồi xoá.
if ! cum || ! kubectl get clusters.postgresql.cnpg.io -A >/dev/null 2>&1; then
  ghi "Diễn tập khôi phục PostgreSQL" "KHÔNG CHẠY ĐƯỢC" "không có cụm hoặc CNPG"
else
  ns_pg=$(kubectl get clusters.postgresql.cnpg.io -A --field-selector "metadata.name=$PG" -o jsonpath='{.items[0].metadata.namespace}' 2>/dev/null)
  if [ -z "$ns_pg" ]; then ghi "Diễn tập khôi phục PostgreSQL" "KHÔNG CHẠY ĐƯỢC" "không thấy cụm $PG"
  else
    kubectl apply -f - >/dev/null 2>&1 <<YAML
apiVersion: postgresql.cnpg.io/v1
kind: Cluster
metadata: { name: ${PG}-dien-tap, namespace: ${ns_pg}, labels: { vt.io/dien-tap: "true" } }
spec:
  instances: 1
  bootstrap: { recovery: { source: ${PG} } }
  externalClusters: [{ name: ${PG}, plugin: { name: barman-cloud.cloudnative-pg.io, parameters: { barmanObjectName: ${PG}-backup, serverName: ${PG} } } }]
  storage: { size: 20Gi }
YAML
    if kubectl wait -n "$ns_pg" --for=condition=Ready "cluster/${PG}-dien-tap" --timeout=1800s >/dev/null 2>&1; then
      ghi "Diễn tập khôi phục PostgreSQL" "ĐẠT" "cụm ${PG}-dien-tap sẵn sàng, đã xoá"
    else ghi "Diễn tập khôi phục PostgreSQL" "KHÔNG ĐẠT" "cụm khôi phục không sẵn sàng sau 30 phút"; fi
    kubectl delete -n "$ns_pg" "cluster/${PG}-dien-tap" --wait=false >/dev/null 2>&1
  fi
fi

CHUNG="ĐẠT"; for d in "${KQ[@]}"; do [[ "$d" == *"| ĐẠT |"* ]] || CHUNG="CHƯA ĐẠT"; done
{
  echo "# Biên bản kiểm tra sau cài đặt bản cách ly Mission"
  echo
  echo "- Bản cài: $(cat "$(dirname "$0")/../PHIEN-BAN" 2>/dev/null || echo 'không rõ')"
  echo "- Thời điểm: $(date '+%Y-%m-%d %H:%M %z')"
  echo "- Người thực hiện: ……………………  Đại diện đơn vị: ……………………"
  echo "- Kết luận chung: **$CHUNG**"
  echo
  echo "| Mục kiểm tra | Kết quả | Ghi chú |"
  echo "|---|---|---|"
  printf '%s\n' "${KQ[@]}"
} > "$BB"
echo "kiem-tra-sau-cai: $CHUNG → $BB"
[ "$CHUNG" = "ĐẠT" ]
