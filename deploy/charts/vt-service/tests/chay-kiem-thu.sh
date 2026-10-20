#!/usr/bin/env bash
# Kiểm thử tĩnh chart thư viện vt-service (INF-01): lint, render, kubeconform -strict kèm schema CRD,
# kube-linter, golden TL-04 mục 5.5 và các ca phải thất bại. Chạy được trong CI (cần helm, kubeconform,
# kube-linter, python3 + PyYAML). Dùng:  deploy/charts/vt-service/tests/chay-kiem-thu.sh
set -euo pipefail
cd "$(dirname "$0")"
VD=vt-service-example
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
# Schema CRD (Argo Rollouts, KEDA, ESO, Prometheus Operator) lấy từ catalog CRDs của datreeio.
CRD_SCHEMA='https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'
dat=0; truot=0
ok()  { echo "  [ĐẠT] $*"; dat=$((dat+1)); }
hong(){ echo "  [HỎNG] $*"; truot=$((truot+1)); }

echo "== Chuẩn bị phụ thuộc"
helm dependency update "$VD" >/dev/null

echo "== helm lint"
helm lint "$VD" >/dev/null && ok "helm lint (org-service)" || hong "helm lint"
helm lint "$VD" -f "$VD/values-rollout-keda.yaml" >/dev/null && ok "helm lint (Rollout + KEDA)" || hong "helm lint rollout"

echo "== helm template và kiểm tra schema"
for bien in values.yaml values-rollout-keda.yaml values-compact.yaml values-kind.yaml; do
  f="$OUT/${bien%.yaml}.out.yaml"
  args=(); [ "$bien" != values.yaml ] && args=(-f "$VD/$bien")
  if helm template t "$VD" "${args[@]}" > "$f"; then ok "render $bien"; else hong "render $bien"; continue; fi
  if kubeconform -strict -summary -kubernetes-version 1.33.0 \
       -schema-location default -schema-location "$CRD_SCHEMA" "$f" > "$OUT/kc.log" 2>&1; then
    ok "kubeconform -strict $bien ($(tail -1 "$OUT/kc.log"))"
  else
    hong "kubeconform $bien"; cat "$OUT/kc.log"
  fi
  # kube-linter không hiểu CRD Rollout, nên với ca Rollout bỏ hai kiểm tra Service/NetworkPolicy "treo".
  kl_them=(); [ "$bien" = values-rollout-keda.yaml ] && kl_them=(--exclude dangling-service,dangling-networkpolicy)
  if kube-linter lint --config kube-linter.yaml "${kl_them[@]}" "$f" > "$OUT/kl.log" 2>&1; then
    ok "kube-linter $bien"
  else
    hong "kube-linter $bien"; cat "$OUT/kl.log"
  fi
done

echo "== Golden TL-04 mục 5.5 (Deployment, HPA, PDB của org-service)"
python3 so-khop-golden.py golden/org-service-tl04.yaml "$OUT/values.out.yaml" && ok "golden khớp" || hong "golden lệch"
# Golden phải bắt được sai lệch: với behavior mặc định (+4 pod/30 s) bản render không còn khớp mục 5.5.
helm template t "$VD" --set-json 'autoscaling.hpa.behavior.scaleUp.policies=[{"type":"Percent","value":100,"periodSeconds":30},{"type":"Pods","value":4,"periodSeconds":30}]' > "$OUT/lech.yaml"
if python3 so-khop-golden.py golden/org-service-tl04.yaml "$OUT/lech.yaml" > /dev/null; then
  hong "golden không phát hiện sai lệch behavior"; else ok "golden phát hiện sai lệch behavior"; fi

echo "== Spread theo hồ sơ"
grep -q 'topology.kubernetes.io/zone' "$OUT/values.out.yaml" && grep -q 'kubernetes.io/hostname' "$OUT/values.out.yaml" \
  && ok "hồ sơ thường: spread theo vùng (DoNotSchedule) và theo node (ScheduleAnyway)" || hong "spread hồ sơ thường"
! grep -q 'topology.kubernetes.io/zone' "$OUT/values-compact.out.yaml" \
  && ok "hồ sơ compact: chỉ còn ràng buộc theo node" || hong "spread compact"

echo "== Các ca phải thất bại"
phai_hong() { # $1 mô tả, $2 mẫu thông báo, còn lại là tham số helm
  local mota=$1 mau=$2; shift 2
  if helm template t "$VD" "$@" > /dev/null 2> "$OUT/err.log"; then hong "$mota: render lẽ ra phải thất bại"
  elif grep -q "$mau" "$OUT/err.log"; then ok "$mota → $(grep -o "vt-service\[[^]]*\]: [^(]*" "$OUT/err.log" | head -1)"
  else hong "$mota: thông báo không rõ"; cat "$OUT/err.log"; fi
}
phai_hong "ảnh không có digest" "digest" --set image.digest=1.4.2
phai_hong "ảnh dạng tag trong repository" "không được chứa digest" --set image.repository=harbor.vt.local/x@sha256:abc
phai_hong "bật đồng thời HPA và ScaledObject" "không được bật đồng thời" --set autoscaling.keda.enabled=true
phai_hong "requests.memory khác limits.memory" "requests.memory" --set resources.requests.memory=2Gi
phai_hong "nodePool lạ" "nodePool" --set nodePool=gpu-x

echo
echo "Tổng: $dat đạt, $truot hỏng"
[ "$truot" -eq 0 ]
