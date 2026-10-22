#!/usr/bin/env bash
# Kiểm tĩnh chart cell-base (INF-08): lint, render, kubeconform -strict, Kyverno CLI, các ca phải thất bại.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=$(mktemp -d); trap 'rm -rf "$OUT"' EXIT
dat=0; truot=0
ok()  { echo "  [ĐẠT] $*"; dat=$((dat+1)); }
hong(){ echo "  [HỎNG] $*"; truot=$((truot+1)); }
helm lint . >/dev/null && ok "helm lint" || hong "helm lint"
helm template c . > "$OUT/c.yaml" && ok "render" || hong "render"
kubeconform -strict -summary "$OUT/c.yaml" > "$OUT/kc" 2>&1 && ok "kubeconform -strict ($(tail -1 "$OUT/kc"))" || { hong kubeconform; cat "$OUT/kc"; }
[ "$(grep -c '^kind: Namespace' "$OUT/c.yaml")" = 13 ] && ok "13 namespace" || hong "số namespace sai"
[ "$(grep -c 'name: default-deny-all' "$OUT/c.yaml")" = 13 ] && ok "13 default-deny-all" || hong "thiếu default-deny-all"
kyverno apply tests/kyverno-co-so.yaml --resource "$OUT/c.yaml" > "$OUT/ky" 2>&1 && ok "Kyverno: $(grep -o 'pass: [0-9]*, fail: [0-9]*' "$OUT/ky")" || { hong Kyverno; tail -20 "$OUT/ky"; }
grep -q 'name: observability' "$OUT/c.yaml" && awk '/name: observability$/{f=1} f&&/enforce:/{print; exit}' "$OUT/c.yaml" | grep -q privileged \
  && ok "chỉ observability được miễn Pod Security restricted (SEC-26)" || hong "miễn trừ observability"
if helm template c . --set-json 'flows=[{"name":"x","from":{"namespace":"core"},"to":{"namespace":"secure"},"ports":[80]}]' >/dev/null 2>"$OUT/e"; then
  hong "luồng core → secure lẽ ra bị từ chối"; else grep -q ADR-09 "$OUT/e" && ok "luồng core → secure bị từ chối khi render (ADR-09)" || hong "thông báo không rõ"; fi
echo "Tổng: $dat đạt, $truot hỏng"
[ "$truot" -eq 0 ]
