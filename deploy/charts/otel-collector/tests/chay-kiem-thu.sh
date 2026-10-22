#!/usr/bin/env bash
# Kiểm tĩnh chart otel-collector (INF-14): lint, render, kubeconform -strict, otelcol validate cho cấu hình agent và
# gateway (mặc định và kind), cờ thay AGPL chuyển đích trace. Cần docker để chạy otelcol-contrib.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=$(mktemp -d); trap 'rm -rf "$OUT"' EXIT
CRD='https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'
OTEL=ghcr.io/open-telemetry/opentelemetry-collector-releases/opentelemetry-collector-contrib@sha256:886722fe0f37af9d1fe24d29529253ec59fbf263b3b1df4facaf221373e19d23
dat=0; truot=0
ok()  { echo "  [ĐẠT] $*"; dat=$((dat+1)); }
hong(){ echo "  [HỎNG] $*"; truot=$((truot+1)); }
helm lint . >/dev/null && ok "helm lint" || hong "helm lint"
for ten in mac-dinh kind; do
  args=(); [ "$ten" = kind ] && args=(-f tests/values-kind.yaml)
  helm template o . "${args[@]}" > "$OUT/$ten.yaml" && ok "render $ten" || hong "render $ten"
  kubeconform -strict -summary -schema-location default -schema-location "$CRD" "$OUT/$ten.yaml" > "$OUT/kc" 2>&1 \
    && ok "kubeconform -strict $ten ($(tail -1 "$OUT/kc"))" || { hong "kubeconform $ten"; cat "$OUT/kc"; }
  mkdir -p "$OUT/$ten"
  python3 - "$OUT/$ten.yaml" "$OUT/$ten" <<'PY'
import sys, yaml
for d in yaml.safe_load_all(open(sys.argv[1])):
    if d and d["kind"] == "ConfigMap":
        open(f"{sys.argv[2]}/{d['metadata']['name']}.yaml", "w").write(d["data"]["config.yaml"])
PY
  for c in otel-agent otel-gateway; do
    docker run --rm -v "$OUT/$ten:/c:ro" -e K8S_NODE_NAME=n "$OTEL" validate --config="/c/$c.yaml" >/dev/null 2>"$OUT/e" \
      && ok "otelcol validate $c ($ten)" || { hong "otelcol validate $c ($ten)"; tail -3 "$OUT/e"; }
  done
done
g="$OUT/mac-dinh/otel-gateway.yaml"
for k in 'routing_key: traceID' 'http.request.header.authorization, action: delete' 'enduser.id, action: hash' 'db.*statement' \
         '(token|code|password)' 'status_codes: \[ERROR\]' 'threshold_ms: 300' 'threshold_ms: 800' 'key: vt.debug' 'sampling_percentage: 10' 'storage: file_storage'; do
  grep -rqE "$k" "$OUT/mac-dinh" && ok "cấu hình có: $k" || hong "thiếu: $k"
done
helm template o . --set agplThayThe=true | grep -q 'endpoint: jaeger-collector' && ok "agplThayThe chuyển trace sang đích Apache-2.0" || hong "cờ agplThayThe"
echo "Tổng: $dat đạt, $truot hỏng"
[ "$truot" -eq 0 ]
