#!/usr/bin/env bash
# shellcheck disable=SC2015  # mẫu "lệnh && ok || hong" có chủ ý: ok và hong luôn trả 0.
# Kiểm tĩnh chart pam-integration (INF-27): lint, render, kubeconform -strict (CRD ESO, Prometheus Operator, Cilium
# từ danh mục CRDs-catalog), Kyverno vt-pod-hardening trên pod của chart (CronJob và Warpgate), kube-linter cấu hình
# chặt của vt-platform cho Warpgate, promtool, shellcheck, unittest. Unittest có phần chạy SQL thật trên PostgreSQL tạm
# khi máy có sẵn bản cài PostgreSQL ≥ 16 (PG_BIN) và phần chạy thật Warpgate cùng otelcol khi có WARPGATE_BIN và
# OTELCOL; chính sách nhập chạy bằng môi trường CEL của kube-apiserver khi có VAP_CEL (tệp dựng từ tests/vap-cel:
# cd tests/vap-cel && go build -o <tệp> .); không kéo ảnh, không dựng container; thiếu thì phần đó tự bỏ qua và báo.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=$(mktemp -d); trap 'rm -rf "${OUT:?}"' EXIT
CRD='https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'
SHELLCHECK=${SHELLCHECK:-shellcheck}
dat=0; truot=0
ok()  { echo "  [ĐẠT] $*"; dat=$((dat+1)); }
hong(){ echo "  [HỎNG] $*"; truot=$((truot+1)); }
bo()  { echo "  [BỎ QUA] $*"; }
helm lint . >/dev/null && ok "helm lint" || hong "helm lint"
helm template p . > "$OUT/p.yaml" && ok "render ($(grep -c '^kind:' "$OUT/p.yaml") đối tượng)" || hong render
helm template p . --set-json 'pam.proxyCIDRs=["192.0.2.0/28"]' --set quetRbac.thuHoiQuaHan=false \
  --set-json 'warpgate.nguonCIDRs=["198.51.100.0/28"]' --set-json 'warpgate.dich.apiServerCIDRs=["192.0.2.16/28"]' > "$OUT/p2.yaml" \
  && ok "render biến thể (proxy PAM, tắt thu hồi, đường vào và ra của Warpgate)" || hong "render biến thể"
kubeconform -strict -summary -schema-location default -schema-location "$CRD" "$OUT/p.yaml" "$OUT/p2.yaml" > "$OUT/kc" 2>&1 \
  && ok "kubeconform -strict ($(tail -1 "$OUT/kc"))" || { hong kubeconform; cat "$OUT/kc"; }
# Kyverno CLI chỉ đánh giá Pod: tách template của CronJob thành Pod rồi áp vt-pod-hardening.
python3 ../vt-platform/ci/chuan-bi-kyverno.py "$OUT/p.yaml" "$OUT/h.yaml" "$OUT/kv.yaml"
python3 - "$OUT/h.yaml" "$OUT/pods.yaml" <<'PY'
import sys, yaml
out = []
for d in yaml.safe_load_all(open(sys.argv[1])):
    if d and d["kind"] in ("CronJob", "StatefulSet"):
        t = d["spec"]["jobTemplate"]["spec"]["template"] if d["kind"] == "CronJob" else d["spec"]["template"]
        out.append({"apiVersion": "v1", "kind": "Pod", "metadata": {"name": f'{d["kind"].lower()}-{d["metadata"]["name"]}',
                    "namespace": d["metadata"]["namespace"], "labels": t["metadata"].get("labels", {})}, "spec": t["spec"]})
yaml.safe_dump_all(out, open(sys.argv[2], "w"))
PY
kyverno apply ../../policies/vt-pod-hardening.yaml --resource "$OUT/pods.yaml" --values-file "$OUT/kv.yaml" > "$OUT/ky" 2>&1 \
  && grep -q 'fail: 0, warn: 0, error: 0' "$OUT/ky" && ok "Kyverno vt-pod-hardening ($(grep -o 'pass: [0-9]*' "$OUT/ky"))" || { hong Kyverno; tail -20 "$OUT/ky"; }
python3 - "$OUT/p2.yaml" "$OUT/wg.yaml" <<'PY'
import sys, yaml
d = [x for x in yaml.safe_load_all(open(sys.argv[1])) if x and (x["metadata"].get("namespace") == "pam" or x["kind"] == "Namespace")]
yaml.safe_dump_all(d, open(sys.argv[2], "w"))
PY
kube-linter lint --config ../vt-platform/ci/kube-linter.yaml "$OUT/wg.yaml" > "$OUT/kl" 2>&1 \
  && ok "kube-linter Warpgate" || { hong "kube-linter Warpgate"; tail -20 "$OUT/kl"; }
promtool check rules rules/*.yaml > "$OUT/pt" 2>&1 && ok "promtool check rules ($(grep -o '[0-9]* rules found' "$OUT/pt"))" || { hong "promtool check"; cat "$OUT/pt"; }
(cd rules/tests && promtool test rules pam.test.yaml) > "$OUT/pt" 2>&1 && ok "promtool test rules pam.test.yaml" || { hong "promtool test"; tail -30 "$OUT/pt"; }
if command -v "$SHELLCHECK" >/dev/null 2>&1; then
  "$SHELLCHECK" -s sh files/scripts/quet-csdl.sh && "$SHELLCHECK" files/openbao/cau-hinh-pam.sh tests/chay-kiem-thu.sh \
    ../../environments/kind/kiem-thu-pam.sh && ok "shellcheck" || hong "shellcheck"
else bo "shellcheck không có (đặt SHELLCHECK=<tệp>)"; fi
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests -p 'test_*.py' -v > "$OUT/ut" 2>&1 \
  && ok "unittest ($(grep -o 'Ran [0-9]* tests' "$OUT/ut")$(grep -q 'skipped' "$OUT/ut" && echo ", bỏ qua: $(grep -o "skipped '[^']*'" "$OUT/ut" | sort -u | tr '\n' ' ')"))" \
  || { hong unittest; tail -30 "$OUT/ut"; }
echo "Tổng: $dat đạt, $truot hỏng"
[ "$truot" -eq 0 ]
