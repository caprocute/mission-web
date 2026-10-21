#!/usr/bin/env bash
# Kiểm ba mô hình phân phối của vt-platform (INF-02, TL-04 mục 2.1, SEC-28): helm lint mọi chart, helm
# template với values-saas.yaml, values-private.yaml, values-airgap.yaml, rồi kubeconform -strict, kube-linter,
# Kyverno CLI, các khẳng định riêng từng mô hình và bảng đối chiếu TL-04 mục 5.2. Hỏng bất kỳ mô hình nào
# (kể cả airgap) trả mã khác 0 để làm đỏ build. Cần helm, kubeconform, kube-linter, kyverno, python3 + PyYAML.
# Dùng: deploy/charts/vt-platform/ci/kiem-ba-mo-hinh.sh
# Release vt-platform cài vào namespace observability: chart Fluent Bit chỉ dùng namespace của release, mọi
# chart còn lại ghi namespace tường minh (TL-04 mục 3.5).
set -euo pipefail
cd "$(dirname "$0")/.."
CHARTS=..
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
CRD_SCHEMA='https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'
# Bộ chính sách thật của cụm (INF-07, deploy/policies), trừ vt-require-signed-images vì kiểm chữ ký cần registry
# thật (kiểm trên kind ở deploy/environments/kind/kiem-thu-chinh-sach.sh). Ngoại lệ lấy ở exceptions/, bỏ tệp mẫu "_".
CS_DIR=$CHARTS/../policies
CHINH_SACH=(); for p in "$CS_DIR"/vt-*.yaml; do [ "$(basename "$p")" = vt-require-signed-images.yaml ] || CHINH_SACH+=("$p"); done
MIEN_TRU=(); for e in "$CS_DIR"/exceptions/*.yaml; do [[ "$(basename "$e")" == _* ]] || MIEN_TRU+=(--exceptions "$e"); done
dat=0; truot=0
ok()  { echo "  [ĐẠT] $*"; dat=$((dat+1)); }
hong(){ echo "  [HỎNG] $*"; truot=$((truot+1)); }

echo "== helm lint các chart dịch vụ và dependency build"
ci/cap-nhat-phu-thuoc.sh
for c in mission-core scoring-service evidence-service ledger-service esign-service document-service \
         notification-service integration-gateway ai-gateway analytics search web-frontend iam; do
  helm lint "$CHARTS/$c" >/dev/null 2>"$OUT/lint.log" && ok "helm lint $c" || { hong "helm lint $c"; cat "$OUT/lint.log"; }
done
helm dependency build . >/dev/null && ok "helm dependency build vt-platform" || hong "dependency vt-platform"
helm lint . -f values-saas.yaml -f ci/values-ci.yaml >/dev/null && ok "helm lint vt-platform" || hong "helm lint vt-platform"

echo "== Ngoại lệ chính sách còn hạn, đủ chú thích (deploy/policies/README.md)"
python3 "$CS_DIR/kiem-ngoai-le.py" > "$OUT/nl.log" 2>&1 && ok "$(tail -1 "$OUT/nl.log")" || { hong "ngoại lệ chính sách"; cat "$OUT/nl.log"; }

for m in saas private airgap; do
  echo "== Mô hình $m"
  f="$OUT/$m.yaml"
  if ! helm template vt . -n observability -f "values-$m.yaml" -f ci/values-ci.yaml > "$f" 2>"$OUT/err.log"; then
    hong "helm template $m"; cat "$OUT/err.log"; continue
  fi
  ok "helm template $m ($(grep -c '^kind:' "$f") đối tượng)"
  if kubeconform -strict -summary -kubernetes-version 1.33.0 -schema-location default -schema-location "$CRD_SCHEMA" \
       "$f" > "$OUT/kc.log" 2>&1; then
    ok "kubeconform -strict ($(tail -1 "$OUT/kc.log"))"
  else
    hong "kubeconform $m"; grep -v '^Summary' "$OUT/kc.log" | head -20 || true
  fi
  # kube-linter với cấu hình chặt của vt-service chỉ chạy trên đối tượng do chart dựng bằng vt-service sinh ra
  # (nhãn app.kubernetes.io/part-of: vt-platform). Chart bên thứ ba và chart chưa chuyển sang vt-service (APISIX,
  # ngăn xếp quan sát, org-service, workflow-service, phần nạp realm) chỉ qua kubeconform và Kyverno ở đây.
  python3 -c 'import sys,yaml; d=[x for x in yaml.safe_load_all(open(sys.argv[1])) if isinstance(x,dict) and (x.get("metadata",{}).get("labels") or {}).get("app.kubernetes.io/part-of")=="vt-platform" and not (x["metadata"]["labels"].get("helm.sh/chart") or "api-gateway").startswith("api-gateway")]; yaml.safe_dump_all(d,open(sys.argv[2],"w")); print(len(d))' \
    "$f" "$OUT/$m-vt.yaml" > "$OUT/sl.log"
  if kube-linter lint --config ci/kube-linter.yaml "$OUT/$m-vt.yaml" > "$OUT/kl.log" 2>&1; then
    ok "kube-linter ($(cat "$OUT/sl.log") đối tượng của chart dựng trên vt-service)"
  else
    hong "kube-linter $m"; grep -E '^/|Error' "$OUT/kl.log" | sed "s|^$OUT/||" | cut -c1-260 | head -30 || true
  fi
  python3 ci/chuan-bi-kyverno.py "$f" "$OUT/$m-harbor.yaml" "$OUT/kv.yaml"
  MT=("${MIEN_TRU[@]}")
  grep -qE '^\s*sizing: compact' "values-$m.yaml" && MT+=(--exceptions "$CS_DIR/ho-so/compact/mot-vung-su-co.yaml")
  if kyverno apply "${CHINH_SACH[@]}" "${MT[@]}" --resource "$OUT/$m-harbor.yaml" --values-file "$OUT/kv.yaml" > "$OUT/ky.log" 2>&1 \
       && grep -q 'fail: 0, warn: 0, error: 0' "$OUT/ky.log"; then
    ok "Kyverno deploy/policies ($(grep -o 'pass: [0-9]*' "$OUT/ky.log"), ảnh qua proxy-cache Harbor)"
  else
    hong "Kyverno $m"; grep -E 'failed|fail:' "$OUT/ky.log" | head -20 || true
  fi
  python3 ci/kiem-mo-hinh.py "$m" "$f" > "$OUT/km.log" 2>&1 && ok "khẳng định riêng của $m: $(tail -1 "$OUT/km.log")" \
    || { hong "khẳng định riêng của $m"; cat "$OUT/km.log"; }
done

echo "== Đối chiếu bảng TL-04 mục 5.2 (values hạng L)"
if python3 ci/doi-chieu-tl04.py "$OUT/saas.yaml" --md "${BANG_RA:-$OUT/bang.md}" > "$OUT/dc.log" 2>&1; then
  ok "$(grep "^Tổng" "$OUT/dc.log")"
else
  hong "bảng đối chiếu có ô lệch chưa ghi"; grep 'LỆCH' "$OUT/dc.log" || true
fi

echo
echo "Tổng: $dat đạt, $truot hỏng"
[ "$truot" -eq 0 ]
