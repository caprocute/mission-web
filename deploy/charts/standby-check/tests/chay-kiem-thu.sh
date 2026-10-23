#!/usr/bin/env bash
# INF-28 · Kiểm tĩnh bản sao ấm: helm lint và kubeconform -strict chart standby-check; với mỗi cell có TTDL chờ, render
# vt-platform ở hai TTDL theo đúng chuỗi values của ApplicationSet (values-saas, hồ sơ, values của cell, values-<site>,
# global.site và siteRole), kubeconform, rồi kiểm bất biến: CNPG distributed topology đúng chiều, TTDL chờ dựng bằng
# pg_basebackup, không CR Database hay Job cấp quyền ở TTDL chờ, MirrorMaker 2 chỉ ở TTDL chờ và Debezium chỉ ở
# primary, lớp ứng dụng ở TTDL chờ tối đa 1 replica, AI tắt; render cụm cell-g4-sn; kiểm thử đơn vị kiem_tra.py.
# Cần helm, kubeconform, python3 + PyYAML.
set -uo pipefail
cd "$(dirname "$0")/.."
GOC=$(cd ../../.. && pwd)
W=$(mktemp -d); trap 'rm -rf "${W:?}"' EXIT
DAT=0; HONG=0
ok()   { echo "  [ĐẠT] $*"; DAT=$((DAT+1)); }
hong() { echo "  [HỎNG] $*"; HONG=$((HONG+1)); }
kc()   { kubeconform -strict -summary -kubernetes-version 1.33.0 -schema-location default \
           -schema-location "$GOC/deploy/charts/vt-platform/ci/crd-schemas/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json" \
           -schema-location "$GOC/deploy/argocd/tests/crd-schemas/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json" \
           -schema-location 'https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json' "$@"; }

helm lint . --set cell=g4 >/dev/null 2>&1 && ok "helm lint standby-check" || hong "helm lint standby-check"
helm template t . --set cell=g4 > "$W/sc.yaml" 2>"$W/loi" && kc "$W/sc.yaml" > "$W/kc" 2>&1 \
  && ok "kubeconform standby-check ($(tail -1 "$W/kc"))" || { hong "standby-check"; cat "$W/loi" "$W/kc" | head; }
if command -v kube-linter > /dev/null; then
  kube-linter lint --config "$GOC/deploy/charts/vt-platform/ci/kube-linter.yaml" "$W/sc.yaml" > "$W/kl" 2>&1 \
    && ok "kube-linter standby-check" || { hong "kube-linter standby-check"; tail -5 "$W/kl"; }
fi
helm template t . > /dev/null 2>&1 && hong "standby-check nhận cell rỗng" || ok "standby-check từ chối cell rỗng"
python3 -m unittest discover -s tests -q > "$W/ut" 2>&1 && ok "kiểm thử đơn vị kiem_tra.py ($(grep -o 'Ran [0-9]* tests' "$W/ut"))" || { hong "kiem_tra.py"; tail "$W/ut"; }

cd "$GOC/deploy/charts/vt-platform"
[ -d charts ] && [ -n "$(ls charts)" ] || helm dependency build . > /dev/null 2>&1
SO=0
for f in "$GOC"/deploy/cells/*/cell.yaml; do
  cell=$(basename "$(dirname "$f")")
  read -r hs pr sb < <(python3 -c "import yaml,sys;c=yaml.safe_load(open(sys.argv[1]))['cell'];print(c['sizing'],c['primarySite'],c['standbySite'])" "$f")
  [ "$pr" = "$sb" ] && continue
  SO=$((SO+1))
  for site in "$pr" "$sb"; do
    role=$([ "$site" = "$pr" ] && echo primary || echo standby)
    helm template "$cell" . -f values-saas.yaml -f "sizing/values-sizing-${hs,,}.yaml" -f ci/values-ci.yaml \
      $( [ -f "$GOC/deploy/cells/$cell/values.yaml" ] && echo "-f $GOC/deploy/cells/$cell/values.yaml" ) \
      -f "$GOC/deploy/cells/$cell/values-$site.yaml" --set "global.cell=$cell" --set "global.site=$site" \
      --set "global.siteRole=$role" --set "global.sizing=$hs" > "$W/$cell-$site.yaml" 2>"$W/loi" \
      || { hong "render $cell-$site"; tail -2 "$W/loi"; continue; }
  done
  python3 - "$W" "$cell" "$pr" "$sb" <<'EOF' && ok "$cell: replica $pr→$sb đúng chiều, chờ ấm tối thiểu, MirrorMaker 2 ở $sb" || hong "$cell: bất biến bản sao ấm"
import sys, yaml
w, cell, pr, sb = sys.argv[1:]
doc = lambda s: [d for d in yaml.safe_load_all(open(f"{w}/{cell}-{s}.yaml")) if d]
P, S = doc(pr), doc(sb)
cl = lambda ds: {d["metadata"]["name"]: d["spec"] for d in ds if d["kind"] == "Cluster" and d["apiVersion"].startswith("postgresql.cnpg.io")}
cp, cs = cl(P), cl(S)
assert cp and set(cp) == set(cs), (sorted(cp), sorted(cs))
for ten in cp:
    for site, s in ((pr, cp[ten]), (sb, cs[ten])):
        assert s["replica"] == {"self": f"{ten}-{site}", "primary": f"{ten}-{pr}", "source": f"{ten}-{sb if site == pr else pr}"}, (ten, site, s["replica"])
        assert sorted(e["name"] for e in s["externalClusters"]) == sorted([f"{ten}-{pr}", f"{ten}-{sb}"])
        assert all(e["connectionParameters"]["sslmode"] == "verify-full" for e in s["externalClusters"])
    assert "initdb" in cp[ten]["bootstrap"] and cs[ten]["bootstrap"] == {"pg_basebackup": {"source": f"{ten}-{pr}"}}
    assert cp[ten]["storage"] == cs[ten]["storage"] and cp[ten]["resources"] == cs[ten]["resources"], f"{ten}: lớp dữ liệu phải đủ kích thước"
assert not any(d["kind"] == "Database" for d in S) and any(d["kind"] == "Database" for d in P)
assert not any(d["kind"] == "Job" and d["metadata"]["name"].startswith("pg-") and "quyen" in d["metadata"]["name"] for d in S)
k = lambda ds, kind: [d["metadata"]["name"] for d in ds if d["kind"] == kind]
assert k(S, "KafkaMirrorMaker2") == [f"mm2-{pr}-to-{sb}"] and not k(P, "KafkaMirrorMaker2")
assert "mm2-nguon" in k(P, "KafkaUser") and "mm2" in k(S, "KafkaUser")
assert k(P, "KafkaConnect") and not k(S, "KafkaConnect"), "Debezium chỉ ở TTDL primary"
z = [n for d in S if d["kind"] == "KafkaNodePool" for n in [d]]
lt = [l for d in S if d["kind"] == "Kafka" for l in d["spec"]["kafka"]["listeners"]]
assert any(l["name"] == "lienttdl" for l in lt)
# Lớp ứng dụng ở TTDL chờ: min của HPA, KEDA và replicas của Deployment/Rollout ứng dụng tối đa 1; AI tắt.
du_lieu = {"data-pg", "secure-data", "data-kafka", "data-search", "observability", "openbao"}
for d in S:
    ns = d["metadata"].get("namespace", "")
    if ns in du_lieu or d["metadata"]["name"] in ("apisix-ingress-controller", "apisix-etcd", "apicurio-registry"):
        continue
    sp = d.get("spec") or {}
    if d["kind"] == "HorizontalPodAutoscaler":
        assert sp["minReplicas"] <= 1, d["metadata"]["name"]
    if d["kind"] == "ScaledObject":
        assert sp.get("minReplicaCount", 0) <= 1, d["metadata"]["name"]
    if d["kind"] in ("Deployment", "Rollout") and sp.get("replicas") is not None:
        assert sp["replicas"] <= 1, (d["kind"], d["metadata"]["name"], sp["replicas"])
assert not [d for d in S if d["kind"] in ("Deployment", "ScaledObject") and d["metadata"]["name"] in ("ai-gateway", "vllm")]
EOF
  for site in "$pr" "$sb"; do
    [ "$cell" = g4 ] || [ "$cell" = nb ] || continue   # kubeconform đầy đủ ở hai hồ sơ đại diện (M, compact)
    kc "$W/$cell-$site.yaml" > "$W/kc" 2>&1 && ok "kubeconform $cell-$site ($(tail -1 "$W/kc"))" || { hong "kubeconform $cell-$site"; grep -v '^Summary' "$W/kc" | head; }
  done
done
[ "$SO" -ge 9 ] && ok "$SO cell có TTDL chờ đều có values hai TTDL" || hong "chỉ $SO cell có TTDL chờ"

cd "$GOC/deploy/clusters"
helm template x cell-cluster -f cell-g4-sn/values.yaml -f cell-g4-sn/values-mau-ha-tang.yaml > "$W/cum.yaml" 2>"$W/loi" \
  && kc "$W/cum.yaml" > "$W/kc" 2>&1 && ok "kubeconform cụm cell-g4-sn ($(tail -1 "$W/kc"))" || { hong "cụm cell-g4-sn"; cat "$W/loi"; head "$W/kc"; }
python3 - "$W/cum.yaml" "$GOC/deploy/clusters" <<'EOF' && ok "cell-g4-sn: ba vùng sn-a..c, pool dữ liệu bằng cell-g4-hl, pool ứng dụng tối thiểu" || hong "cell-g4-sn"
import sys, yaml
ds = [d for d in yaml.safe_load_all(open(sys.argv[1])) if d]
fd = {d["spec"]["template"]["spec"].get("failureDomain") for d in ds if d["kind"] == "MachineDeployment"}
assert fd == {"sn-a", "sn-b", "sn-c"}, fd
hl = yaml.safe_load(open(f"{sys.argv[2]}/cell-g4-hl/values.yaml"))["pools"]
sn = yaml.safe_load(open(f"{sys.argv[2]}/cell-g4-sn/values.yaml"))["pools"]
for p in hl:
    if p.startswith("data-") or p == "security-zone":
        assert hl[p] == sn[p], p
assert sn["app-general"]["autoscale"]["min"] == 1 and sn["app-general"]["autoscale"]["max"] == hl["app-general"]["autoscale"]["max"]
EOF

echo; echo "Tổng: $DAT đạt, $HONG hỏng"
[ "$HONG" = 0 ]
