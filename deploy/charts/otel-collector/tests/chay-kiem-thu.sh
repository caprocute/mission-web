#!/usr/bin/env bash
# Kiểm tĩnh chart otel-collector (INF-14): lint, render, kubeconform -strict, otelcol validate cho cấu hình agent và
# gateway (mặc định và kind), cờ thay AGPL chuyển đích trace; luồng SIEM (INF-19): otelcol validate cho otel-siem và
# chặng ra SOC, chạy thật bộ xử lý ECS (tests/kiem-thu-siem.py, gồm sự kiện PAM qua receiver syslog). Cần docker, hoặc OTELCOL=<tệp otelcol-contrib>.
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
    if [ -n "${OTELCOL:-}" ]; then
      K8S_NODE_NAME=n "$OTELCOL" validate --config="$OUT/$ten/$c.yaml" >/dev/null 2>"$OUT/e"
    else
      docker run --rm -v "$OUT/$ten:/c:ro" -e K8S_NODE_NAME=n "$OTEL" validate --config="/c/$c.yaml" >/dev/null 2>"$OUT/e"
    fi && ok "otelcol validate $c ($ten)" || { hong "otelcol validate $c ($ten)"; tail -3 "$OUT/e"; }
  done
done
g="$OUT/mac-dinh/otel-gateway.yaml"
for k in 'routing_key: traceID' 'http.request.header.authorization, action: delete' 'enduser.id, action: hash' 'db.*statement' \
         '(token|code|password)' 'status_codes: \[ERROR\]' 'threshold_ms: 300' 'threshold_ms: 800' 'key: vt.debug' 'sampling_percentage: 10' 'storage: file_storage'; do
  grep -rqE "$k" "$OUT/mac-dinh" && ok "cấu hình có: $k" || hong "thiếu: $k"
done
helm template o . --set agplThayThe=true | grep -q 'endpoint: jaeger-collector' && ok "agplThayThe chuyển trace sang đích Apache-2.0" || hong "cờ agplThayThe"
echo "== Luồng SIEM (INF-19)"
helm template o . --set-json 'siem.soc.brokers=["kafka-soc.vi-du.invalid:9093"]' --set-json 'siem.soc.cidrs=["198.51.100.0/24"]' > "$OUT/soc.yaml" \
  && ok "render có chặng ra SOC" || hong "render có chặng ra SOC"
kubeconform -strict -summary -schema-location default -schema-location "$CRD" "$OUT/soc.yaml" > "$OUT/kc" 2>&1 \
  && ok "kubeconform -strict có SOC ($(tail -1 "$OUT/kc"))" || { hong "kubeconform có SOC"; cat "$OUT/kc"; }
helm template o . --set siem.topics.siem=sys.siem.v2 >/dev/null 2>&1 && hong "đổi tên topic SIEM lẽ ra bị từ chối" || ok "đổi tên topic SIEM bị từ chối khi render"
# Chứng thư giả (chỉ để otelcol validate dựng được receiver TLS), sinh lúc chạy, không lưu.
mkdir -p "$OUT/cert/tls" "$OUT/cert/kafka" "$OUT/cert/soc" "$OUT/siem"
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -keyout "$OUT/cert/k" -out "$OUT/cert/c" -subj /CN=thu -days 1 2>/dev/null
for d in tls soc; do cp "$OUT/cert/c" "$OUT/cert/$d/ca.crt"; cp "$OUT/cert/c" "$OUT/cert/$d/tls.crt"; cp "$OUT/cert/k" "$OUT/cert/$d/tls.key"; done
cp "$OUT/cert/c" "$OUT/cert/kafka/ca.crt"; cp "$OUT/cert/c" "$OUT/cert/kafka/user.crt"; cp "$OUT/cert/k" "$OUT/cert/kafka/user.key"
python3 - "$OUT/soc.yaml" "$OUT/siem" <<'PY'
import sys, yaml
for d in yaml.safe_load_all(open(sys.argv[1])):
    if d and d["kind"] == "ConfigMap" and d["metadata"]["name"] in ("otel-siem", "siem-soc-forwarder"):
        open(f"{sys.argv[2]}/{d['metadata']['name']}.yaml", "w").write(d["data"]["config.yaml"].replace("/etc/otel/", "/cert/"))
PY
for c in otel-siem siem-soc-forwarder; do
  if [ -n "${OTELCOL:-}" ]; then
    sed "s|/cert/|$OUT/cert/|g" "$OUT/siem/$c.yaml" > "$OUT/siem/$c.local.yaml"
    "$OTELCOL" validate --config="$OUT/siem/$c.local.yaml" >/dev/null 2>"$OUT/e"
  else
    docker run --rm -v "$OUT/siem:/c:ro" -v "$OUT/cert:/cert:ro" "$OTEL" validate --config="/c/$c.yaml" >/dev/null 2>"$OUT/e"
  fi && ok "otelcol validate $c" || { hong "otelcol validate $c"; tail -3 "$OUT/e"; }
done
OTEL_IMAGE="$OTEL" python3 tests/kiem-thu-siem.py "$OUT/mac-dinh.yaml" "$OUT/soc.yaml" > "$OUT/ks" 2>&1 || true
sed 's/^/  /' "$OUT/ks"; dat=$((dat + $(grep -c '^\[ĐẠT\]' "$OUT/ks" || true))); truot=$((truot + $(grep -c '^\[HỎNG\]' "$OUT/ks" || true)))
grep -q '^\[' "$OUT/ks" || { hong "kiem-thu-siem.py"; tail -5 "$OUT/ks"; }
echo "Tổng: $dat đạt, $truot hỏng"
[ "$truot" -eq 0 ]
