#!/usr/bin/env bash
# Kiểm tĩnh chart security-cell (INF-19): lint, render, kubeconform -strict (schema Tetragon ở tests/crd-schemas, sinh
# bằng vt-platform/ci/tao-schema-crd.py từ CRD Tetragon v1.5.0), Kyverno vt-pod-hardening trên pod của chart, promtool,
# chạy shellcheck cho hai script, falco --validate và --dry-run (khi có docker và ảnh Falco), unittest các tính chất then chốt.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=$(mktemp -d); trap 'rm -rf "$OUT"' EXIT
CRD='https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'
FALCO=${FALCO_IMAGE:-falcosecurity/falco@sha256:69e957f91b3a6beb5c82e2afae33ec3e4479dab2d4421475d288d0cd113cedf6}
SHELLCHECK=${SHELLCHECK:-shellcheck}
dat=0; truot=0
ok()  { echo "  [ĐẠT] $*"; dat=$((dat+1)); }
hong(){ echo "  [HỎNG] $*"; truot=$((truot+1)); }
bo()  { echo "  [BỎ QUA] $*"; }
helm lint . >/dev/null && ok "helm lint" || hong "helm lint"
helm template s . > "$OUT/s.yaml" && ok "render ($(grep -c '^kind:' "$OUT/s.yaml") đối tượng)" || hong render
kubeconform -strict -summary -schema-location default -schema-location 'tests/crd-schemas/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json' \
  -schema-location "$CRD" "$OUT/s.yaml" > "$OUT/kc" 2>&1 && ok "kubeconform -strict ($(tail -1 "$OUT/kc"))" || { hong kubeconform; cat "$OUT/kc"; }
# Kyverno CLI chỉ đánh giá Pod: tách template của Deployment, DaemonSet, CronJob thành Pod rồi áp vt-pod-hardening.
python3 ../vt-platform/ci/chuan-bi-kyverno.py "$OUT/s.yaml" "$OUT/h.yaml" "$OUT/kv.yaml"
python3 - "$OUT/h.yaml" "$OUT/pods.yaml" <<'PY'
import sys, yaml
out = []
for d in yaml.safe_load_all(open(sys.argv[1])):
    if not d: continue
    t = d["spec"]["jobTemplate"]["spec"]["template"] if d["kind"] == "CronJob" else d["spec"]["template"] if d["kind"] in ("Deployment", "DaemonSet") else None
    if t:
        out.append({"apiVersion": "v1", "kind": "Pod", "metadata": {"name": f'{d["kind"].lower()}-{d["metadata"]["name"]}',
                    "namespace": d["metadata"]["namespace"], "labels": t["metadata"].get("labels", {})}, "spec": t["spec"]})
yaml.safe_dump_all(out, open(sys.argv[2], "w"))
PY
kyverno apply ../../policies/vt-pod-hardening.yaml --resource "$OUT/pods.yaml" --values-file "$OUT/kv.yaml" > "$OUT/ky" 2>&1 \
  && grep -q 'fail: 0, warn: 0, error: 0' "$OUT/ky" && ok "Kyverno vt-pod-hardening ($(grep -o 'pass: [0-9]*' "$OUT/ky"))" || { hong Kyverno; tail -20 "$OUT/ky"; }
promtool check rules rules/*.yaml > "$OUT/pt" 2>&1 && ok "promtool check rules ($(grep -o '[0-9]* rules found' "$OUT/pt"))" || { hong "promtool check"; cat "$OUT/pt"; }
for t in rules/tests/*.test.yaml; do
  (cd rules/tests && promtool test rules "$(basename "$t")") > "$OUT/pt" 2>&1 && ok "promtool test rules $(basename "$t")" || { hong "promtool test $(basename "$t")"; tail -20 "$OUT/pt"; }
done
if command -v "$SHELLCHECK" >/dev/null 2>&1; then
  "$SHELLCHECK" -s sh files/scripts/*.sh && ok "shellcheck files/scripts" || hong "shellcheck files/scripts"
else bo "shellcheck không có (đặt SHELLCHECK=<tệp>)"; fi
# falco --validate: chỉ chạy khi ảnh Falco đã có sẵn trên máy (không tự kéo ảnh).
mkdir -p "$OUT/falco"
python3 - "$OUT/s.yaml" "$OUT/falco" <<'PY'
import sys, yaml
for d in yaml.safe_load_all(open(sys.argv[1])):
    if d and d["kind"] == "ConfigMap" and d["metadata"]["name"] == "falco":
        for k, v in d["data"].items():
            open(f"{sys.argv[2]}/{k}", "w").write(v)
PY
if command -v docker >/dev/null 2>&1 && docker image inspect "$FALCO" >/dev/null 2>&1; then
  docker run --rm -v "$OUT/falco:/v:ro" "$FALCO" falco -V /etc/falco/falco_rules.yaml -V /v/vt-rules.yaml > "$OUT/fv" 2>&1 \
    && grep -q '/v/vt-rules.yaml: Ok$' "$OUT/fv" && ok "falco --validate vt-rules.yaml (không cảnh báo)" || { hong "falco --validate"; tail -20 "$OUT/fv"; }
  docker run --rm -v "$OUT/falco:/v:ro" "$FALCO" sh -c 'cp /v/vt-rules.yaml /etc/falco/rules.d/ && falco -c /v/falco.yaml --dry-run' > "$OUT/fd" 2>&1 \
    && ok "falco --dry-run với falco.yaml của chart" || { hong "falco --dry-run"; tail -10 "$OUT/fd"; }
else bo "falco --validate: chưa có ảnh $FALCO (docker pull trước, khoảng 47 MB)"; fi
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests -p 'test_*.py' > "$OUT/ut" 2>&1 \
  && ok "unittest ($(grep -o 'Ran [0-9]* tests' "$OUT/ut"))" || { hong unittest; tail -30 "$OUT/ut"; }
echo "Tổng: $dat đạt, $truot hỏng"
[ "$truot" -eq 0 ]
