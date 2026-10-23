#!/usr/bin/env bash
# Kiểm thử tĩnh sao lưu phần 1 (INF-17): chart backup-cell, phần sao lưu của data-pg và bucket kho sao lưu của rgw-cell.
#   - helm lint, helm template nhiều cấu hình, kubeconform -strict với schema CRD CNPG, plugin Barman Cloud, Velero
#     (deploy/charts/vt-platform/ci/crd-schemas) và kho datree cho ExternalSecret, PrometheusRule, CiliumNetworkPolicy.
#   - shellcheck các script, promtool check rules và promtool test rules (tests/canh-bao-test.yaml).
#   - Khớp tên giữa ba chart: ObjectStore, bucket, Secret khoá, đường dẫn s3://<bucket>/<cụm>.
#   - Các cấu hình chart phải từ chối (ảnh không digest, thiếu cell, Velero sao lưu volume namespace dữ liệu, dọn
#     bucket trước khi hết hạn khoá).
#   - Kiểm thử script với PostgreSQL thật (tests/kiem-thu-script.sh) khi đã đặt PGHOST, PGPORT.
# Cần helm, kubeconform, shellcheck, promtool, python3 + PyYAML. Dùng: deploy/charts/backup-cell/tests/chay-kiem-thu.sh
set -euo pipefail
CHART=$(cd "$(dirname "$0")/.." && pwd)
CHARTS=$(dirname "$CHART")
PG=$CHARTS/vt-platform/ha-tang/data-pg
RGW=$CHARTS/rgw-cell
OUT=$(mktemp -d)
trap 'rm -rf "${OUT:?}"' EXIT
D=sha256:$(printf '%064d' 7)
dat=0; hong=0
ok() { echo "  [ĐẠT] $*"; dat=$((dat + 1)); }
loi() { echo "  [HỎNG] $*"; hong=$((hong + 1)); }
kc() { # <tệp> <mô tả>
  if kubeconform -strict -summary -kubernetes-version 1.33.0 -schema-location default \
       -schema-location "$CHARTS/vt-platform/ci/crd-schemas/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json" \
       -schema-location 'https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json' \
       "$1" > "$OUT/kc.log" 2>&1; then ok "$2: kubeconform -strict ($(tail -1 "$OUT/kc.log"))"
  else loi "$2: kubeconform"; grep -v '^Summary' "$OUT/kc.log" | head; fi
}
tu_choi() { # <mô tả> <chuỗi lỗi mong đợi> <chart> <tham số helm…>
  local mo_ta=$1 cho=$2 c=$3; shift 3
  if helm template t "$c" "$@" > /dev/null 2> "$OUT/e.log"; then loi "$mo_ta: chart không từ chối"
  elif grep -q "$cho" "$OUT/e.log"; then ok "$mo_ta bị từ chối"; else loi "$mo_ta: lỗi khác"; cat "$OUT/e.log"; fi
}

echo "== backup-cell"
CO=(--set cell=g4 --set image.digest="$D")
if helm lint "$CHART" "${CO[@]}" > "$OUT/lint.log" 2>&1; then ok "helm lint"; else loi "helm lint"; cat "$OUT/lint.log"; fi
if shellcheck -x -P "$CHART/files" "$CHART"/files/*.sh "$CHART"/tests/*.sh; then ok "shellcheck script và kiểm thử"; else loi "shellcheck"; fi
helm template t "$CHART" "${CO[@]}" --set pushgateway=http://pushgateway.observability.svc:9091 \
  --set 'khoSaoLuu.egressTo[0].ipBlock.cidr=10.20.0.0/24' > "$OUT/bk.yaml"
kc "$OUT/bk.yaml" "mặc định"
helm template t "$CHART" -f "$CHARTS/../environments/kind/values-kind-backup-cell.yaml" > "$OUT/bk-kind.yaml"
kc "$OUT/bk-kind.yaml" "kind"
helm template t "$CHART" "${CO[@]}" --set restoreCheck.enabled=false --set giuDaiHan.enabled=false \
  --set etcd.enabled=false --set velero.enabled=false --set canhBao.enabled=false > "$OUT/bk-tat.yaml"
if [ ! -s "$OUT/bk-tat.yaml" ] || ! grep -q '^kind:' "$OUT/bk-tat.yaml"; then ok "tắt mọi phần thì không dựng đối tượng nào"; else loi "tắt mọi phần"; fi
if python3 -I - "$OUT/bk.yaml" <<'PY'
import sys, yaml
docs = [d for d in yaml.safe_load_all(open(sys.argv[1])) if d]
k = {(d["kind"], d["metadata"]["name"]): d for d in docs}
rc = k[("CronJob", "restore-check")]["spec"]
assert rc["schedule"] == "0 5 * * *" and rc["timeZone"] == "Asia/Ho_Chi_Minh" and rc["concurrencyPolicy"] == "Forbid"
assert rc["jobTemplate"]["spec"]["backoffLimit"] == 0
env = {e["name"]: e.get("value") for e in rc["jobTemplate"]["spec"]["template"]["spec"]["containers"][0]["env"]}
assert env["CUMS"] == "pg-core:data-pg pg-flowable:data-pg pg-platform:data-pg pg-iam:data-pg pg-ledger:secure-data pg-secure:secure-data", env["CUMS"]
assert env["BUCKET"] == "bk-cell-g4-pg" and env["NS"] == "data-pg-restore" and env["SO_CAI"] == "pg-ledger=ledger"
assert env["LUI_GIAY"] == "3600" and env["IMAGE_PG"].startswith("harbor.vt.local/platform/postgresql:17.6-standard-bookworm@sha256:")
ns = k[("Namespace", "data-pg-restore")]["metadata"]["labels"]
assert ns["vt.io/security-zone"] == "data" and ns["pod-security.kubernetes.io/enforce"] == "restricted"
role = k[("Role", "restore-check")]
assert role["metadata"]["namespace"] == "data-pg-restore"
assert not any("secrets" in r["resources"] for r in role["rules"]), "restore-check không đọc Secret"
for n in ("data-pg", "secure-data"):
    r = next(d for d in docs if d["kind"] == "Role" and d["metadata"]["name"] == "giu-dai-han-doc-backup" and d["metadata"]["namespace"] == n)
    assert r["rules"] == [{"apiGroups": ["postgresql.cnpg.io"], "resources": ["backups"], "verbs": ["get", "list"]}], r
gd = k[("CronJob", "giu-dai-han")]["spec"]
assert gd["schedule"] == "30 4 28-31 * *"
genv = {e["name"]: e.get("value") for e in gd["jobTemplate"]["spec"]["template"]["spec"]["containers"][0]["env"]}
assert genv["CUM_NAM"] == "pg-ledger pg-secure" and genv["GIU_THANG"] == "12" and genv["GIU_NAM_NAM"] == "10"
et = k[("CronJob", "chup-etcd")]
assert et["metadata"]["namespace"] == "kube-system" and et["spec"]["schedule"] == "0 */6 * * *"
p = et["spec"]["jobTemplate"]["spec"]["template"]["spec"]
assert p["hostNetwork"] is True and p["automountServiceAccountToken"] is False
m = {v["name"]: v for v in p["containers"][0]["volumeMounts"]}
assert m["pki"]["readOnly"] is True and p["containers"][0]["securityContext"]["readOnlyRootFilesystem"] is True
eenv = {e["name"]: e.get("value") for e in p["containers"][0]["env"]}
assert eenv["BUCKET"] == "bk-cell-g4-k8s" and eenv["PREFIX"] == "etcd"
es = k[("ExternalSecret", "bk-sn-s3-k8s")]["spec"]["data"]
assert {x["remoteRef"]["key"] for x in es} == {"kv/cell-g4/backup/k8s"}
assert k[("ExternalSecret", "etcd-age-recipient")]["spec"]["data"][0]["remoteRef"] == {"key": "kv/platform/backup/etcd", "property": "age_recipient"}
assert {x["remoteRef"]["key"] for x in k[("ExternalSecret", "bk-sn-s3-pg")]["spec"]["data"]} == {"kv/cell-g4/backup/pg"}
vel = k[("ExternalSecret", "bk-sn-s3-velero")]["spec"]["target"]["template"]["data"]["cloud"]
assert "aws_access_key_id={{ .ACCESS_KEY_ID }}" in vel
bsl = k[("BackupStorageLocation", "bk-sn")]["spec"]
assert bsl["objectStorage"] == {"bucket": "bk-cell-g4-k8s", "prefix": "velero", "caCertRef": {"name": "vt-noi-bo-ca", "key": "ca.crt"}}, bsl
s1 = k[("Schedule", "cell-resources-6h")]["spec"]
assert s1["schedule"] == "15 */6 * * *" and s1["template"]["snapshotVolumes"] is False and s1["template"]["ttl"] == "720h0m0s"
assert s1["template"]["excludedNamespaces"] == ["kube-system", "velero"] and "pods" in s1["template"]["excludedResources"]
s2 = k[("Schedule", "stateful-volumes-daily")]["spec"]["template"]
assert s2["includedNamespaces"] == ["ingress", "platform-harbor", "observability"] and s2["snapshotMoveData"] is True
assert s2["storageLocation"] == "bk-sn" and s2["ttl"] == "336h0m0s"
pr = k[("PrometheusRule", "backup-cell-g4")]["spec"]["groups"]
ten = sorted({r["alert"] for g in pr for r in g["rules"]})
assert ten == ["BackupMissing", "EtcdSnapshotMissing", "RestoreCheckFailed", "RestoreCheckStale"], ten
np = k[("NetworkPolicy", "ra-kho-sao-luu")]["spec"]
assert np["egress"][0]["to"] == [{"ipBlock": {"cidr": "10.20.0.0/24"}}] and np["egress"][0]["ports"] == [{"port": 443, "protocol": "TCP"}]
PY
then ok "nội dung: lịch, luân phiên 6 cụm, quyền tối thiểu, etcd chỉ đọc PKI, khoá OpenBao theo cell, Velero theo TL-04, 4 cảnh báo"
else loi "nội dung backup-cell"; fi
helm template t "$CHART" "${CO[@]}" --show-only templates/canh-bao.yaml \
  | python3 -I -c 'import sys,yaml; d=yaml.safe_load(sys.stdin); yaml.safe_dump({"groups": d["spec"]["groups"]}, open(sys.argv[1], "w"), allow_unicode=True)' "$OUT/canh-bao.rules.yaml"
cp "$CHART/tests/canh-bao-test.yaml" "$OUT/"
if promtool check rules "$OUT/canh-bao.rules.yaml" > "$OUT/pc.log" 2>&1; then ok "promtool check rules ($(grep -o '[0-9]* rules' "$OUT/pc.log"))"; else loi "promtool check rules"; cat "$OUT/pc.log"; fi
if promtool test rules "$OUT/canh-bao-test.yaml" > "$OUT/pt.log" 2>&1; then ok "promtool test rules: BackupMissing, RestoreCheckStale, RestoreCheckFailed, EtcdSnapshotMissing"
else loi "promtool test rules"; cat "$OUT/pt.log"; fi
tu_choi "ảnh công cụ không digest" "SEC-55" "$CHART" --set cell=g4
tu_choi "thiếu mã cell" "cần cell" "$CHART" --set image.digest="$D"
tu_choi "Velero sao lưu volume namespace dữ liệu" "namespace dữ liệu data-pg" "$CHART" "${CO[@]}" \
  --set 'velero.schedules.stateful-volumes-daily.template.includedNamespaces={ingress,data-pg}'
tu_choi "Velero sao lưu volume mọi namespace" "không được chọn mọi namespace" "$CHART" "${CO[@]}" \
  --set 'velero.schedules.cell-resources-6h.template.snapshotVolumes=true'
tu_choi "ảnh PostgreSQL cụm tạm không digest" "SEC-55" "$CHART" "${CO[@]}" --set restoreCheck.postgresImage.digest=

echo "== data-pg: Barman Cloud và ScheduledBackup"
if helm lint "$PG" --set global.cell=g4 > "$OUT/lint.log" 2>&1; then ok "helm lint data-pg"; else loi "helm lint data-pg"; cat "$OUT/lint.log"; fi
for ho_so in L compact; do
  helm template t "$PG" --set global.cell=g4 --set global.sizing=$ho_so --set 'backup.egressTo[0].ipBlock.cidr=10.20.0.0/24' > "$OUT/pg-$ho_so.yaml"
  kc "$OUT/pg-$ho_so.yaml" "data-pg $ho_so"
done
helm template t "$PG" -f "$CHARTS/../environments/kind/values-kind-data-pg.yaml" --set global.cell=kind > "$OUT/pg-kind.yaml"
kc "$OUT/pg-kind.yaml" "data-pg kind"
if python3 -I - "$OUT/pg-L.yaml" "$OUT/pg-compact.yaml" "$OUT/bk.yaml" <<'PY'
import sys, yaml
def doc(f): return [d for d in yaml.safe_load_all(open(f)) if d]
bk = doc(sys.argv[3])
rc = next(d for d in bk if d["kind"] == "CronJob" and d["metadata"]["name"] == "restore-check")
env = {e["name"]: e.get("value") for e in rc["spec"]["jobTemplate"]["spec"]["template"]["spec"]["containers"][0]["env"]}
cums_bk = dict(x.split(":") for x in env["CUMS"].split())
for f, mong in ((sys.argv[1], 6), (sys.argv[2], 2)):
    d = doc(f)
    cl = {x["metadata"]["name"]: x for x in d if x["kind"] == "Cluster"}
    os_ = {x["metadata"]["name"]: x for x in d if x["kind"] == "ObjectStore"}
    sb = {x["metadata"]["name"]: x for x in d if x["kind"] == "ScheduledBackup"}
    assert len(cl) == len(os_) == len(sb) == mong, (f, len(cl), len(os_), len(sb))
    for ten, c in cl.items():
        ns = c["metadata"]["namespace"]
        assert cums_bk[ten] == ns, (ten, ns)   # restore-check và giu-dai-han tìm Backup đúng namespace
        p = c["spec"]["plugins"]
        assert p == [{"name": "barman-cloud.cloudnative-pg.io", "isWALArchiver": True,
                      "parameters": {"barmanObjectName": f"bk-sn-{ten}", "serverName": ten}}], p
        assert c["spec"]["postgresql"]["parameters"]["archive_timeout"] == "60s"
        o = os_[f"bk-sn-{ten}"]
        assert o["metadata"]["namespace"] == ns
        s = o["spec"]
        assert s["retentionPolicy"] == "35d"
        assert s["configuration"]["destinationPath"] == f"s3://{env['BUCKET']}/{ten}"
        assert s["configuration"]["wal"] == {"compression": "zstd", "encryption": "AES256", "maxParallel": 8}
        assert s["configuration"]["data"]["encryption"] == "AES256"
        assert s["configuration"]["s3Credentials"]["accessKeyId"]["name"] == env["CRED_SECRET"]
        b = sb[f"{ten}-daily"]["spec"]
        assert b["schedule"] == "0 30 18 * * *" and b["method"] == "plugin" and b["target"] == "prefer-standby"
        assert b["pluginConfiguration"] == {"name": "barman-cloud.cloudnative-pg.io"} and b["cluster"] == {"name": ten}
    es = [x for x in d if x["kind"] == "ExternalSecret" and x["metadata"]["name"] == "bk-sn-s3-pg"]
    assert {x["metadata"]["namespace"] for x in es} == {c["metadata"]["namespace"] for c in cl.values()}
    assert all(r["remoteRef"]["key"] == "kv/cell-g4/backup/pg" for x in es for r in x["spec"]["data"])
    np = [x for x in d if x["kind"] == "NetworkPolicy" and x["metadata"]["name"] == "pg-sao-luu"]
    assert np and all(x["spec"]["egress"][0]["to"] == [{"ipBlock": {"cidr": "10.20.0.0/24"}}] for x in np)
PY
then ok "ObjectStore, ScheduledBackup, plugin WAL, khoá ESO đủ cho 6 cụm (L) và 2 cụm (compact), khớp tên với backup-cell"
else loi "nội dung data-pg"; fi
tu_choi "data-pg sao lưu thiếu global.cell" "global.cell" "$PG"
if helm template t "$PG" --set backup.enabled=false | grep -q 'kind: ObjectStore'; then loi "tắt sao lưu vẫn dựng ObjectStore"
else ok "backup.enabled=false: không ObjectStore, không cần global.cell"; fi

echo "== rgw-cell: bucket kho sao lưu"
KS=(-f "$RGW/values-kho-sao-luu.yaml" --set cell=g4)
if helm lint "$RGW" "${KS[@]}" > "$OUT/lint.log" 2>&1; then ok "helm lint rgw-cell kho sao lưu"; else loi "helm lint rgw-cell"; cat "$OUT/lint.log"; fi
helm template t "$RGW" "${KS[@]}" > "$OUT/rgw.yaml"
kc "$OUT/rgw.yaml" "rgw-cell kho sao lưu"
if python3 -I - "$OUT/rgw.yaml" <<'PY'
import json, sys, yaml
docs = [d for d in yaml.safe_load_all(open(sys.argv[1])) if d]
cm = next(d for d in docs if d["kind"] == "ConfigMap")["data"]
ds = {l.split()[0]: l.split()[1:] for l in cm["danh-sach"].splitlines()}
assert ds == {"pg": ["bk-cell-g4-pg", "true", "true"], "ch": ["bk-cell-g4-ch", "true", "true"], "k8s": ["bk-cell-g4-k8s", "true", "true"]}, ds
han = {k: json.loads(cm[f"{k}.khoa.json"])["Rule"]["DefaultRetention"] for k in ds}
assert han == {"pg": {"Mode": "COMPLIANCE", "Days": 35}, "ch": {"Mode": "COMPLIANCE", "Days": 35}, "k8s": {"Mode": "COMPLIANCE", "Days": 30}}, han
for k in ds:
    for st in json.loads(cm[f"{k}.chinh-sach.json"])["Statement"]:
        assert st["Principal"] == {"AWS": [f"arn:aws:iam:::user/bk-cell-g4-{k}"]}, st
        assert not any(a.startswith("s3:Delete") or a in ("s3:PutBucketObjectLockConfiguration", "s3:PutBucketPolicy",
                       "s3:PutLifecycleConfiguration", "s3:BypassGovernanceRetention") for a in st["Action"]), st
vd = {r["ID"]: r for r in json.loads(cm["pg.vong-doi.json"])["Rules"]}
assert vd["don-0"]["Filter"] == {"Prefix": "pg-core/"} and vd["don-0"]["Expiration"] == {"Days": 36}
assert vd["don-0"]["NoncurrentVersionExpiration"] == {"NoncurrentDays": 1}
lau = {r["Filter"]["Prefix"]: r["Expiration"]["Days"] for r in vd.values() if r["ID"].startswith("don-") and "Days" in r.get("Expiration", {})}
assert lau["dai-han/thang/"] == 367 and lau["dai-han/nam/"] == 3654 and not any(p == "" for p in lau), lau
assert vd["don-marker"]["Expiration"] == {"ExpiredObjectDeleteMarker": True}
assert not [d for d in docs if d["kind"] == "PrometheusRule"]
es = [d for d in docs if d["kind"] == "ExternalSecret"]
assert [e["metadata"]["name"] for e in es] == ["rgw-quan-tri"], "chỉ khoá quản trị kho sao lưu"
assert es[0]["spec"]["data"][0]["remoteRef"]["key"] == "kv/ceph-sn-bk/cell-g4/kho-quan-tri-cell-g4"
PY
then ok "3 bucket bk-cell-g4-{pg,ch,k8s} COMPLIANCE 35/35/30 ngày, khoá của cell không xoá, dọn theo tiền tố dưới tài khoản quản trị"
else loi "nội dung bucket kho sao lưu"; fi
tu_choi "dọn bucket trước khi hết hạn khoá" "không dài hơn hạn khoá" "$RGW" "${KS[@]}" --set 'buckets.ch.vongDoi.don[0].sauNgay=30'
if "$RGW/tests/chay-kiem-thu.sh" > "$OUT/rgw-cu.log" 2>&1; then ok "kiểm thử cũ của rgw-cell vẫn đạt ($(tail -1 "$OUT/rgw-cu.log"))"
else loi "kiểm thử cũ của rgw-cell"; tail -20 "$OUT/rgw-cu.log"; fi

if [ -n "${PGHOST:-}" ] && [ -n "${PGPORT:-}" ]; then
  echo "== Script với PostgreSQL thật ($PGHOST:$PGPORT)"
  if "$CHART/tests/kiem-thu-script.sh" > "$OUT/sc.log" 2>&1; then ok "kiem-thu-script.sh ($(tail -1 "$OUT/sc.log"))"
  else loi "kiem-thu-script.sh"; grep -v 'ĐẠT' "$OUT/sc.log"; fi
else echo "  (bỏ qua kiem-thu-script.sh: chưa đặt PGHOST, PGPORT)"; fi

echo; echo "Tổng: $dat đạt, $hong hỏng"
[ "$hong" -eq 0 ]
