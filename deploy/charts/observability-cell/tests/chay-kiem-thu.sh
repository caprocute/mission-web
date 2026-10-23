#!/usr/bin/env bash
# Kiểm tĩnh chart observability-cell (INF-14): lint, render, kubeconform -strict, fluent-bit --dry-run cho cấu hình
# của cụm thật và kind, kiểm thử redact.lua bằng bộ dòng mẫu (JWT, CCCD, số điện thoại, email), đường cảnh báo lên
# Alertmanager cụm quản trị (promtool, amtool). Cần docker, helm, kubeconform, promtool, amtool.
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
  args=(-f tests/values-ci.yaml); [ "$ten" = kind ] && args=(-f tests/values-kind.yaml)
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
echo "== Đường cảnh báo lên Alertmanager cụm quản trị (INF-15)"
helm template oc . -n observability > /dev/null 2>"$OUT/loi" && hong "render không dừng khi canhBaoQuanTri.diaChi trống" \
  || { grep -q "canhBaoQuanTri.diaChi trống" "$OUT/loi" && ok "render dừng khi chưa khai địa chỉ Alertmanager cụm quản trị"; }
python3 - "$OUT/mac-dinh.yaml" "$OUT" <<'PY' > "$OUT/kq" || true
import sys, yaml
docs = [d for d in yaml.safe_load_all(open(sys.argv[1])) if d]
def ok(c, s): print(("[ĐẠT] " if c else "[HỎNG] ") + s)
sec = next(d for d in docs if d["kind"] == "Secret" and d["metadata"]["name"] == "prometheus-alertmanager-quan-tri")
prom = next(d for d in docs if d["kind"] == "Prometheus")["spec"]
ok(prom.get("additionalAlertManagerConfigs", {}).get("name") == "prometheus-alertmanager-quan-tri"
   and "alertmanager-quan-tri-tls" in prom.get("secrets", []), "Prometheus đọc khối alertmanagers và gắn chứng chỉ mTLS")
ok(prom["alerting"]["alertmanagers"][0]["name"] == "kps-alertmanager", "vẫn gửi Alertmanager trong cell (suy giảm)")
am = yaml.safe_load(sec["stringData"]["alertmanagers.yaml"])
ok(am[0]["static_configs"][0]["targets"] == ["alertmanager-0.quan-tri.invalid:9443", "alertmanager-1.quan-tri.invalid:9443"]
   and am[0]["scheme"] == "https" and "cert_file" in am[0]["tls_config"], "địa chỉ lấy từ values, https kèm chứng chỉ khách")
ok(am[0]["alert_relabel_configs"][0] == {"source_labels": ["severity"], "regex": "control", "action": "drop"},
   "cảnh báo điều khiển suy giảm (severity=control) không lên cụm quản trị")
np = next(d for d in docs if d["kind"] == "NetworkPolicy" and d["metadata"]["name"] == "prometheus-to-mgmt-alertmanager")
ok(np["spec"]["egress"][0]["ports"] == [{"protocol": "TCP", "port": 9443}], "NetworkPolicy mở đúng cổng Alertmanager cụm quản trị")
# Ghép thành cấu hình Prometheus có chứng chỉ giả để promtool kiểm cú pháp và tệp tham chiếu.
d = sys.argv[2] + "/am"
import os; os.makedirs(d, exist_ok=True)
for f in ("ca.crt", "tls.crt", "tls.key"): open(f"{d}/{f}", "w").write("x")
txt = sec["stringData"]["alertmanagers.yaml"].replace("/etc/prometheus/secrets/alertmanager-quan-tri-tls", d)
yaml.safe_dump({"global": {"external_labels": {"cell": "g4"}}, "alerting": {"alertmanagers": yaml.safe_load(txt)}},
               open(sys.argv[2] + "/prometheus.yml", "w"))
PY
sed 's/^/  /' "$OUT/kq"; dat=$((dat + $(grep -c '^\[ĐẠT\]' "$OUT/kq" || true))); truot=$((truot + $(grep -c '^\[HỎNG\]' "$OUT/kq" || true)))
promtool check config --syntax-only "$OUT/prometheus.yml" > "$OUT/pt" 2>&1 && ok "promtool check config khối alertmanagers" || { hong "promtool check config"; cat "$OUT/pt"; }
# Định tuyến ở cụm quản trị cho cảnh báo từ luật của cell (nhãn cell do external_labels gắn).
AMC=../observability-mgmt/alertmanager/alertmanager.yaml
for ca in "goi-truc,dba,nhan-tin-sre alertname=OutboxBacklog severity=critical doi=sre-dba cell=g4" \
          "goi-truc,soc alertname=LedgerChainBroken severity=critical doi=soc cell=g4" \
          "nhan-tin-sre alertname=CephSyncBehind severity=warning doi=sre-luu-tru cell=g4" \
          "goi-truc alertname=SLOReadLatencyBurn severity=critical slo=read-latency cell=g4"; do
  set -- $ca; mong=$1; shift
  thuc=$(amtool config routes test --config.file=$AMC "$@" 2>&1 | tail -1)
  [ "$thuc" = "$mong" ] && ok "cụm quản trị định tuyến $1 → $thuc" || hong "$* → $thuc (mong đợi $mong)"
done
echo "Tổng: $dat đạt, $truot hỏng"
[ "$truot" -eq 0 ]
