#!/usr/bin/env bash
# Kiểm thử tĩnh kho GitOps (INF-04): helm lint, render chart deploy/argocd, kubeconform -strict với schema CRD của
# Argo CD 3.1 và Argo Rollouts 1.8 (tests/crd-schemas, sinh bằng vt-platform/ci/tao-schema-crd.py), mô phỏng bộ sinh
# ApplicationSet (tests/kiem-appset.py), render thử nguồn Helm của từng Application ở CELL-G4, Rollout canary của
# mission-core, kiểm thử đơn vị script sinh lịch đóng băng. Cần helm, kubeconform, python3 + PyYAML, jsonschema.
set -uo pipefail
cd "$(dirname "$0")/.."
GOC=$(cd ../.. && pwd)
W=$(mktemp -d); trap 'rm -rf "${W:?}"' EXIT
DAT=0; HONG=0
ok()   { echo "  [ĐẠT] $*"; DAT=$((DAT+1)); }
hong() { echo "  [HỎNG] $*"; HONG=$((HONG+1)); }
kc()   { kubeconform -strict -summary -kubernetes-version 1.33.0 -schema-location default \
           -schema-location "tests/crd-schemas/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json" \
           -schema-location 'https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json' "$@"; }

helm lint . >/dev/null 2>&1 && ok "helm lint" || hong "helm lint"
helm template t . -f values-dong-bang.yaml > "$W/goc.yaml" 2>"$W/loi" && ok "render values.yaml" || { hong "render"; cat "$W/loi"; }
helm template t . -f "$GOC/deploy/environments/kind/values-kind-gitops.yaml" > "$W/kind.yaml" 2>"$W/loi" \
  && ok "render values kind" || { hong "render kind"; cat "$W/loi"; }
for f in goc kind; do
  kc "$W/$f.yaml" > "$W/kc" 2>&1 && ok "kubeconform $f ($(tail -1 "$W/kc"))" || { hong "kubeconform $f"; head -20 "$W/kc"; }
done
kc ung-dung-goc.yaml > "$W/kc" 2>&1 && ok "kubeconform ứng dụng gốc" || { hong "kubeconform ứng dụng gốc"; cat "$W/kc"; }

python3 tests/kiem-appset.py "$W/goc.yaml" --xuat "$W/ts.json" > "$W/appset" 2>&1 \
  && ok "bộ sinh: $(tail -1 "$W/appset")" || { hong "bộ sinh ApplicationSet"; cat "$W/appset"; }

# AppProject: hai cửa sổ đóng băng cố định của OPS-06, không tài nguyên cấp cụm với okr-apps, chỉ release-manager sync.
python3 - "$W/goc.yaml" <<'EOF' && ok "AppProject: chữ ký, cửa sổ đóng băng OPS-06, okr-apps không tài nguyên cấp cụm" || hong "AppProject"
import sys, yaml
p = {d["metadata"]["name"]: d["spec"] for d in yaml.safe_load_all(open(sys.argv[1])) if d and d["kind"] == "AppProject"}
a = p["okr-apps"]
assert a["clusterResourceWhitelist"] == [] and a["signatureKeys"]
assert {"group": "rbac.authorization.k8s.io", "kind": "*"} in a["namespaceResourceBlacklist"]
w = {(x["schedule"], x["duration"]) for x in a["syncWindows"] if x["kind"] == "deny" and x["manualSync"]}
assert w >= {("0 0 24 3,6,9,12 *", "408h"), ("0 0 20 1 *", "384h")}, w
assert all(x["timeZone"] == "Asia/Ho_Chi_Minh" for x in a["syncWindows"])
for ten, s in p.items():
    assert [r["name"] for r in s["roles"]] == ["release-manager"]
    assert all(", sync, " not in pol or "release-manager" in pol for r in s["roles"] for pol in r["policies"])
EOF

# Render thử nguồn Helm của từng Application ở CELL-G4 (giống repo-server của Argo CD: valueFiles bỏ tệp thiếu,
# valuesObject, parameters). Digest của production còn rỗng nên thay bằng digest giả cho bước này.
cd "$GOC/deploy/charts/vt-platform"
python3 - "$W/ts.json" "$W" <<'EOF'
import json, os, sys, yaml
ts, w = json.load(open(sys.argv[1])), sys.argv[2]
with open(f"{w}/lenh", "w") as out:
    for a in ts:
        h = a["helm"]
        yaml.safe_dump(h["valuesObject"], open(f"{w}/{a['ten']}.yaml", "w"))
        args = [f"-f {f}" for f in h.get("valueFiles", []) if os.path.exists(f)] + [f"-f {w}/{a['ten']}.yaml"]
        gia = "sha256:" + "1" * 64
        for p in h.get("parameters", []):
            v = p["value"]
            if p["name"].endswith(".digest"):
                v = gia
            elif p["name"].endswith(".tag"):
                v = "gitops@" + gia
            args.append(f"--set {p['name']}={v}")
        out.write(f"{a['ten']}\t{' '.join(args)}\n")
EOF
LOI_RENDER=0; SO=0
while IFS=$'\t' read -r ten args; do
  SO=$((SO+1))
  # shellcheck disable=SC2086
  if ! helm template "$ten" . -f ci/values-ci.yaml $args > "$W/app.yaml" 2> "$W/loi-app"; then
    LOI_RENDER=$((LOI_RENDER+1)); echo "    $ten: $(tail -1 "$W/loi-app")"
  fi
done < "$W/lenh"
[ "$LOI_RENDER" = 0 ] && ok "render nguồn Helm của $SO Application ở CELL-G4" || hong "render $LOI_RENDER/$SO Application"
cd "$GOC/deploy/argocd"

# Rollout canary của mission-core theo mẫu TL-04: 5 → phân tích SLI → 25 → 50 → 100, APISIX, abort giữ 600 s.
helm template m "$GOC/deploy/charts/mission-core" --set global.cell=g4 > "$W/mc.yaml" 2>"$W/loi" || cat "$W/loi"
kc "$W/mc.yaml" > "$W/kc" 2>&1 && ok "kubeconform mission-core ($(tail -1 "$W/kc"))" || { hong "kubeconform mission-core"; head "$W/kc"; }
python3 - "$W/mc.yaml" <<'EOF' && ok "Rollout mission-core: bước canary, AnalysisTemplate slo-api, Service stable/canary" || hong "Rollout mission-core"
import sys, yaml
ds = {(d["kind"], d["metadata"]["name"]): d for d in yaml.safe_load_all(open(sys.argv[1])) if d}
c = ds[("Rollout", "mission-core")]["spec"]["strategy"]["canary"]
buoc = [list(b)[0] + (str(b.get("setWeight", "")) if "setWeight" in b else "") for b in c["steps"]]
assert buoc == ["setWeight5", "analysis", "setWeight25", "pause", "setWeight50", "pause", "setWeight100"], buoc
assert c["steps"][1]["analysis"]["templates"][0]["templateName"] == "slo-api"
assert c["abortScaleDownDelaySeconds"] == 600 and c["trafficRouting"]["apisix"]["route"]["name"] == "mission-core"
assert c["canaryMetadata"]["labels"]["role"] == "canary" and c["stableMetadata"]["labels"]["role"] == "stable"
assert ("Service", "mission-core-canary") in ds and ("Service", "mission-core-stable") in ds
t = ds[("AnalysisTemplate", "slo-api")]["spec"]["metrics"]
nguong = {m["name"]: (m["successCondition"], m["interval"], m["count"]) for m in t}
assert nguong == {"error-rate": ("result[0] < 0.002", "2m", 15), "p95-read-latency": ("result[0] < 0.3", "2m", 15),
                  "p95-write-latency": ("result[0] < 0.8", "2m", 15)}, nguong
assert all('{{args.service}}' in m["provider"]["prometheus"]["query"] for m in t)
EOF
helm template a "$GOC/deploy/charts/api-gateway" -f "$GOC/deploy/charts/api-gateway/tests/values-ci.yaml" 2>/dev/null \
  | grep -q 'serviceName: mission-core-canary' && ok "ApisixRoute mission-core có backend stable/canary" || hong "ApisixRoute canary"

python3 -m unittest discover -s lich-dong-bang/tests -q > "$W/ut" 2>&1 \
  && ok "kiểm thử đơn vị sinh lịch đóng băng ($(grep -o 'Ran [0-9]* tests' "$W/ut"))" || { hong "sinh lịch"; tail -20 "$W/ut"; }

echo; echo "Tổng: $DAT đạt, $HONG hỏng"
[ "$HONG" = 0 ]
