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
for bien in values.yaml values-rollout-keda.yaml values-compact.yaml values-kind.yaml values-keda-kafka.yaml; do
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

echo "== KEDA theo độ trễ Kafka và cửa sổ tăng trước (TL-04 mục 6.3, 6.4; INF-16)"
python3 - "$OUT/values-keda-kafka.out.yaml" > "$OUT/keda.log" <<'PY' && { sed 's/^/  [ĐẠT] /' "$OUT/keda.log"; dat=$((dat+$(wc -l < "$OUT/keda.log"))); } || { cat "$OUT/keda.log"; hong "ScaledObject worker"; }
import sys, yaml
docs = [d for d in yaml.safe_load_all(open(sys.argv[1])) if d]
so = [d for d in docs if d["kind"] == "ScaledObject"][0]["spec"]
tr = {t.get("name", t["type"]): t for t in so["triggers"]}
k = tr["lag-product-evidence-accepted-v1"]["metadata"]
assert (k["topic"], k["lagThreshold"], k["activationLagThreshold"], k["consumerGroup"]) == ("product.evidence.accepted.v1", "500", "10", "scoring-service")
assert k["allowIdleConsumers"] == "false" and k["limitToPartitionsWithLag"] == "true" and k["tls"] == "enable"
assert tr["lag-recompute"]["metadata"]["lagThreshold"] == "200" and tr["lag-recompute"]["authenticationRef"]["name"] == "scoring-service-kafka-tls"
print("hai trigger Kafka đúng mẫu TL-04 mục 6.3 (ngưỡng 500/10 và 200/1, mTLS)")
assert tr["cpu"]["metadata"]["value"] == "80" and (so["minReplicaCount"], so["maxReplicaCount"], so["cooldownPeriod"]) == (2, 24, 600)
b = so["advanced"]["horizontalPodAutoscalerConfig"]["behavior"]
assert b["scaleUp"]["policies"] == [{"type": "Pods", "value": 6, "periodSeconds": 30}] and b["scaleDown"]["stabilizationWindowSeconds"] == 900
print("CPU 80%, 2/24 pod, cooldown 600 s, behavior riêng của worker (+6 pod/30 s, giảm sau 900 s)")
cr = {n: t["metadata"] for n, t in tr.items() if t["type"] == "cron"}
assert cr["cuoi-quy"]["desiredReplicas"] == "8" and cr["cuoi-quy"]["timezone"] == "Asia/Ho_Chi_Minh"
assert cr["ky-q2-2027-tinh-a-0"] == {"timezone": "Asia/Ho_Chi_Minh", "start": "0 6 28-30 6 *", "end": "0 23 28-30 6 *", "desiredReplicas": "10"}
assert cr["ky-q2-2027-tinh-a-1"]["start"] == "0 6 1-12 7 *"
print("cửa sổ deadline-default giữ làm sàn, cửa sổ theo kỳ của cell thành hai trigger cron theo tháng")
ta = [d for d in docs if d["kind"] == "TriggerAuthentication"][0]
assert {r["parameter"]: (r["name"], r["key"]) for r in ta["spec"]["secretTargetRef"]} == {
    "ca": ("kafka-scoring-service-tls", "ca.crt"), "cert": ("kafka-scoring-service-tls", "user.crt"), "key": ("kafka-scoring-service-tls", "user.key")}
print("TriggerAuthentication đọc chứng chỉ KafkaUser của chính service")
assert not [d for d in docs if d["kind"] == "HorizontalPodAutoscaler"]
print("không sinh HPA riêng bên cạnh ScaledObject (mỗi workload một bộ co giãn)")
PY

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
