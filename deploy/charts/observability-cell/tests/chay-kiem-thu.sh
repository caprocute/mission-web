#!/usr/bin/env bash
# Kiểm tĩnh chart observability-cell (INF-14): lint, render, kubeconform -strict, fluent-bit --dry-run cho cấu hình
# của cụm thật và kind, kiểm thử redact.lua bằng bộ dòng mẫu (JWT, CCCD, số điện thoại, email). Cần docker.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=$(mktemp -d); trap 'rm -rf "$OUT"' EXIT
CRD='https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'
FB=cr.fluentbit.io/fluent/fluent-bit@sha256:2a5cb41f99b7c5f3386bb34d42ce3b19eb47fbef6d93b64c0bd4afe9e6ec389c
dat=0; truot=0
ok()  { echo "  [ĐẠT] $*"; dat=$((dat+1)); }
hong(){ echo "  [HỎNG] $*"; truot=$((truot+1)); }
helm dependency build . >/dev/null
helm lint . >/dev/null && ok "helm lint" || hong "helm lint"
for ten in mac-dinh kind; do
  args=(); [ "$ten" = kind ] && args=(-f tests/values-kind.yaml)
  helm template oc . -n observability "${args[@]}" > "$OUT/$ten.yaml" && ok "render $ten" || hong "render $ten"
  # CRD của Prometheus Operator lớn và đã có schema chính thức; kubeconform bỏ qua kind CustomResourceDefinition.
  kubeconform -strict -summary -skip CustomResourceDefinition -schema-location default -schema-location "$CRD" "$OUT/$ten.yaml" > "$OUT/kc" 2>&1 \
    && ok "kubeconform -strict $ten ($(tail -1 "$OUT/kc"))" || { hong "kubeconform $ten"; tail -5 "$OUT/kc"; }
  mkdir -p "$OUT/$ten/conf" "$OUT/$ten/scripts"
  python3 - "$OUT/$ten.yaml" "$OUT/$ten" <<'PY'
import sys, yaml
for d in yaml.safe_load_all(open(sys.argv[1])):
    if d and d["kind"] == "ConfigMap" and "fluent-bit" in d["metadata"]["name"]:
        for k, v in d["data"].items():
            open(f"{sys.argv[2]}/{'scripts' if k.endswith('.lua') else 'conf'}/{k}", "w").write(v)
PY
  docker run --rm -e CELL=x -v "$OUT/$ten/conf:/fluent-bit/etc/conf:ro" -v "$OUT/$ten/scripts:/fluent-bit/scripts:ro" "$FB" \
    /fluent-bit/bin/fluent-bit -c /fluent-bit/etc/conf/fluent-bit.conf --dry-run 2>&1 | grep -q "configuration test is successful" \
    && ok "fluent-bit --dry-run ($ten)" || hong "fluent-bit --dry-run ($ten)"
done
grep -q 'storage.type        filesystem' "$OUT/mac-dinh.yaml" && grep -q 'Mem_Buf_Limit       64M' "$OUT/mac-dinh.yaml" \
  && grep -q 'Retry_Limit         no_limits' "$OUT/mac-dinh.yaml" && grep -q 'Topics              sys.audit.v1' "$OUT/mac-dinh.yaml" \
  && ok "bộ đệm đĩa, Mem_Buf_Limit 64M, không giới hạn thử lại, luồng kiểm toán sys.audit.v1" || hong "cấu hình Fluent Bit lệch TL-04"
# JWT giả ghép từ từng phần lúc chạy để gitleaks (SEC-09) không coi tệp kiểm thử là chứa token thật
JWT_GIA="$(printf '%s.%s.%s' 'eyJhbGciOiJSUzI1NiJ9' 'eyJzdWIiOiJ1In0' 'c2lnbmF0dXJl')"
printf '{"message":"login token=%s ok","trace":{"id":"0123456789012345678901234567abcd"}}\n' "$JWT_GIA" > "$OUT/mau.jsonl"
cat >> "$OUT/mau.jsonl" <<'J'
{"message":"CCCD 001203004567 của cán bộ","kubernetes":{"pod_id":"001203004567"}}
{"message":"gọi 0912345678 hoặc +84912345678, email nguyen.van.a@cqt.gov.vn"}
{"message":"mã nhiệm vụ 123456 lúc 2026-10-21T20:52:40.123456789Z giữ nguyên","nested":{"x":"cccd 079123456789"}}
J
cp files/redact.lua "$OUT/redact.lua"
docker run --rm -v "$OUT:/d" "$FB" /fluent-bit/bin/fluent-bit -q -i tail -p path=/d/mau.jsonl -p read_from_head=true -p parser=json \
  -p exit_on_eof=true -F lua -p script=/d/redact.lua -p call=redact_pii -m '*' -o stdout -p format=json_lines -m '*' -f 1 \
  -R /fluent-bit/etc/parsers.conf > "$OUT/ra.txt" 2>&1 || true
python3 - "$OUT/ra.txt" <<'PY' > "$OUT/kq" || true
import json, re, sys
d = [json.loads(l) for l in open(sys.argv[1]) if l.startswith("{")]
assert len(d) == 4, len(d)
m = {x["message"].split()[0]: x for x in d}
def ok(c, s): print(("[ĐẠT] " if c else "[HỎNG] ") + s)
ok("eyJ" not in json.dumps(d), "redact.lua che JWT")
ok("001203004567" not in m["CCCD"]["message"] and "079123456789" not in json.dumps(m["mã"]), "redact.lua che số CCCD 12 chữ số (cả trường lồng)")
ok("0912345678" not in m["gọi"]["message"] and "+84912345678" not in m["gọi"]["message"], "redact.lua che số điện thoại")
ok("@" not in m["gọi"]["message"], "redact.lua che email")
ok("123456 " in m["mã"]["message"] and "20:52:40.123456789Z" in m["mã"]["message"], "giữ nguyên số ngắn và thời gian")
ok(m["login"]["trace"]["id"] == "0123456789012345678901234567abcd" and m["CCCD"]["kubernetes"]["pod_id"] == "001203004567",
   "không đụng trace.id và siêu dữ liệu kubernetes")
PY
sed 's/^/  /' "$OUT/kq"; dat=$((dat + $(grep -c '^\[ĐẠT\]' "$OUT/kq" || true))); truot=$((truot + $(grep -c '^\[HỎNG\]' "$OUT/kq" || true)))
grep -q '^\[' "$OUT/kq" || { hong "chạy thử redact.lua"; cat "$OUT/ra.txt" | tail -5; }
echo "== Luật SLO, cảnh báo và tín hiệu năng lực (INF-15)"
promtool check rules rules/*.yaml > "$OUT/pt" 2>&1 && ok "promtool check rules ($(grep -o '[0-9]* rules found' "$OUT/pt" | awk '{s+=$1} END {print s}') luật)" || { hong "promtool check rules"; cat "$OUT/pt"; }
for t in rules/tests/*.test.yaml; do
  (cd rules/tests && promtool test rules "$(basename "$t")") > "$OUT/pt" 2>&1 && ok "promtool test rules $(basename "$t")" || { hong "promtool test $(basename "$t")"; tail -20 "$OUT/pt"; }
done
n=$(grep -A2 "^kind: PrometheusRule" "$OUT/mac-dinh.yaml" | grep -c "name: vt-" || true)
[ "$n" -ge 3 ] && ok "chart sinh $n PrometheusRule từ rules/*.yaml" || hong "PrometheusRule: $n"
echo "Tổng: $dat đạt, $truot hỏng"
[ "$truot" -eq 0 ]
