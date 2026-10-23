#!/usr/bin/env bash
# INF-20 · Kiểm tĩnh vùng điều phối toàn cục: helm lint và kubeconform -strict cho chart global-pg, global-dns,
# global-cdn-origin, edge-health; render bốn cụm mgmt-hl, mgmt-sn, global-hl, global-sn bằng khuôn cell-cluster;
# kiểm thử đơn vị dns-sync và edge-health (go test). ApplicationSet của vùng được kiểm ở deploy/argocd/tests,
# thử PowerDNS và dns-sync thật bằng container ở tests/thu-docker.sh. Cần helm, kubeconform, go, python3 + PyYAML.
set -uo pipefail
cd "$(dirname "$0")/../.."
GOC=$(cd ../.. && pwd)
W=$(mktemp -d); trap 'rm -rf "${W:?}"' EXIT
DAT=0; HONG=0
ok()   { echo "  [ĐẠT] $*"; DAT=$((DAT+1)); }
hong() { echo "  [HỎNG] $*"; HONG=$((HONG+1)); }
kc()   { kubeconform -strict -summary -kubernetes-version 1.33.0 -schema-location default \
           -schema-location "$GOC/deploy/argocd/tests/crd-schemas/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json" \
           -schema-location 'https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json' "$@"; }
GIA="--set dnsSync.image=harbor.vt.local/okr/dns-sync@sha256:$(printf '1%.0s' $(seq 64))"

for c in global-pg global-dns global-cdn-origin edge-health; do
  case $c in global-dns) A=$GIA ;; edge-health) A="--set image=harbor.vt.local/okr/dns-sync@sha256:$(printf '1%.0s' $(seq 64))" ;; *) A= ;; esac
  # shellcheck disable=SC2086
  helm lint "$c" $A >/dev/null 2>&1 && ok "helm lint $c" || hong "helm lint $c"
  for s in hl sn; do
    # shellcheck disable=SC2086
    helm template t "$c" --set site=$s $A > "$W/$c-$s.yaml" 2>"$W/loi" || { hong "render $c ($s)"; cat "$W/loi"; continue; }
  done
  cat "$W/$c"-*.yaml > "$W/$c.yaml"
  kc "$W/$c.yaml" > "$W/kc" 2>&1 && ok "kubeconform $c hai TTDL ($(tail -1 "$W/kc"))" || { hong "kubeconform $c"; head -20 "$W/kc"; }
done
# Chart từ chối ảnh không ghim digest (SEC-55).
helm template t global-dns --set dnsSync.image=harbor.vt.local/okr/dns-sync@ > /dev/null 2>&1 \
  || helm template t edge-health > /dev/null 2>&1 && hong "chart nhận ảnh dns-sync chưa ghim digest" || ok "global-dns, edge-health từ chối ảnh chưa ghim digest"

python3 - "$W" <<'EOF' && ok "pg-registry: một primary, bản kia replica cluster, remote_apply, đồng bộ ưu tiên TTDL bên kia; ghi DNS chỉ ở TTDL primary" || hong "bất biến pg-registry/GeoDNS"
import sys, yaml
w = sys.argv[1]
doc = lambda f: [d for d in yaml.safe_load_all(open(f"{w}/{f}.yaml")) if d]
cl = {d["metadata"]["name"]: d["spec"] for f in ("global-pg-hl", "global-pg-sn") for d in doc(f) if d["kind"] == "Cluster"}
assert set(cl) == {"pg-registry-hl", "pg-registry-sn"}
assert "initdb" in cl["pg-registry-hl"]["bootstrap"] and "pg_basebackup" in cl["pg-registry-sn"]["bootstrap"]
for ten, s in cl.items():
    assert s["replica"]["primary"] == "pg-registry-hl" and s["replica"]["self"] == ten
    assert s["postgresql"]["parameters"]["synchronous_commit"] == "remote_apply"
    khac = "sn" if ten.endswith("hl") else "hl"
    assert all(n.startswith(f"pg-registry-{khac}") for n in s["postgresql"]["synchronous"]["standbyNamesPre"])
for site, co in (("hl", True), ("sn", False)):
    ds = doc(f"global-dns-{site}")
    assert any(d["kind"] == "Job" and d["metadata"]["name"] == "pdns-vung" for d in ds) == co
    assert any(d["kind"] == "Deployment" and d["metadata"]["name"] == "dns-sync" for d in ds) == co
    assert any(d["kind"] == "Deployment" and d["metadata"]["name"] == "pdns" for d in ds)
EOF

cd "$GOC/deploy/clusters"
for cum in mgmt-hl mgmt-sn global-hl global-sn; do
  helm template x cell-cluster -f "$cum/values.yaml" -f "$cum/values-mau-ha-tang.yaml" > "$W/$cum.yaml" 2>"$W/loi" \
    || { hong "render cụm $cum"; cat "$W/loi"; continue; }
  kc "$W/$cum.yaml" > "$W/kc" 2>&1 && ok "kubeconform cụm $cum ($(tail -1 "$W/kc"))" || { hong "kubeconform cụm $cum"; head -20 "$W/kc"; }
done

cd "$GOC/services-go/dns-sync"
go vet ./... > "$W/go" 2>&1 && go test -count=1 -cover ./... >> "$W/go" 2>&1 \
  && ok "go vet và go test dns-sync, edge-health ($(grep -o 'coverage: [0-9.]*%' "$W/go" | tr '\n' ' '))" || { hong "go test"; tail -20 "$W/go"; }

echo; echo "Tổng: $DAT đạt, $HONG hỏng"
[ "$HONG" = 0 ]
