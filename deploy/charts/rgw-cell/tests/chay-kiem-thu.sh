#!/usr/bin/env bash
# Kiểm thử tĩnh chart rgw-cell (INF-12): helm lint, render, kubeconform -strict, shellcheck hai script, các ràng buộc
# chart phải từ chối (quyền xoá trên bucket khoá, hết hạn trên bucket khoá, khoá không bật versioning, ảnh không digest),
# và nội dung cấu hình bucket dựng ra (hạn giữ theo TL-03, chính sách theo tiền tố tenant).
# Cần helm, kubeconform, shellcheck, python3 + PyYAML. Dùng: deploy/charts/rgw-cell/tests/chay-kiem-thu.sh
set -euo pipefail
CHART=$(cd "$(dirname "$0")/.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "${OUT:?}"' EXIT
dat=0; hong=0
ok() { echo "  [ĐẠT] $*"; dat=$((dat + 1)); }
loi() { echo "  [HỎNG] $*"; hong=$((hong + 1)); }
tu_choi() { # <mô tả> <chuỗi lỗi mong đợi> <tham số helm…>
  local mo_ta=$1 cho=$2; shift 2
  if helm template t "$CHART" --set cell=g4 "$@" > /dev/null 2> "$OUT/e.log"; then loi "$mo_ta: chart không từ chối"
  elif grep -q "$cho" "$OUT/e.log"; then ok "$mo_ta bị từ chối"; else loi "$mo_ta: lỗi khác"; cat "$OUT/e.log"; fi
}
if helm lint "$CHART" --set cell=g4 > "$OUT/lint.log" 2>&1; then ok "helm lint"; else loi "helm lint"; cat "$OUT/lint.log"; fi
if shellcheck "$CHART/scripts/khoi-tao-multisite.sh" "$CHART/files/khoi-tao-bucket.sh"; then ok "shellcheck hai script"; else loi "shellcheck"; fi
for gt in "global.objectStore.inCluster=false" "global.objectStore.inCluster=true"; do
  helm template t "$CHART" --set cell=g4 --set "$gt" > "$OUT/r.yaml"
  if kubeconform -strict -summary -kubernetes-version 1.33.0 -schema-location default \
       -schema-location 'https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json' \
       "$OUT/r.yaml" > "$OUT/kc.log" 2>&1; then ok "$gt: kubeconform -strict ($(tail -1 "$OUT/kc.log"))"
  else loi "kubeconform $gt"; grep -v '^Summary' "$OUT/kc.log" | head; fi
done
if python3 - "$OUT/r.yaml" <<'PY'
import json, sys, yaml
docs = [d for d in yaml.safe_load_all(open(sys.argv[1])) if d]
cm = next(d for d in docs if d["kind"] == "ConfigMap")["data"]
ds = {l.split()[0]: l.split()[1:] for l in cm["danh-sach"].splitlines()}
assert ds["evidence"] == ["ev-cell-g4", "true", "true"], ds
assert ds["uploads-tmp"] == ["tmp-cell-g4", "false", "false"], ds
han = {k: json.loads(cm[f"{k}.khoa.json"])["Rule"]["DefaultRetention"] for k in ("evidence", "dossier", "ledger-archive", "transfer")}
assert han["evidence"] == {"Mode": "COMPLIANCE", "Years": 10}
assert han["dossier"] == han["ledger-archive"] == {"Mode": "COMPLIANCE", "Years": 20}
assert han["transfer"] == {"Mode": "GOVERNANCE", "Years": 1}
assert "templates.khoa.json" not in cm and "uploads-tmp.khoa.json" not in cm
for k, v in cm.items():
    if k.endswith(".chinh-sach.json"):
        for st in json.loads(v)["Statement"]:
            assert not any(a in st["Action"] for a in ("s3:PutBucketPolicy", "s3:PutBucketObjectLockConfiguration", "s3:DeleteObjectVersion", "s3:BypassGovernanceRetention")), st
            if "s3:DeleteObject" in st["Action"]:
                assert k == "uploads-tmp.chinh-sach.json", k
ev = json.loads(cm["evidence.chinh-sach.json"])["Statement"]
# Câu ghi nhiều phần (s3:PutObject trên ARN bucket, RGW 19 kiểm CreateMultipartUpload ở đó) không cần tiền tố:
# UploadPart và CompleteMultipartUpload ngoài tiền tố vẫn bị từ chối (kiểm trên kind, INF-17).
assert all(s["Resource"][0].endswith("/t/*") or s["Action"] == ["s3:PutObject"] or s["Condition"]["StringLike"]["s3:prefix"] == ["t/*"] for s in ev)
vd = json.loads(cm["evidence.vong-doi.json"])["Rules"]
assert any(r.get("Transitions") == [{"Days": 30, "StorageClass": "HDD_EC"}] for r in vd)
assert json.loads(cm["uploads-tmp.vong-doi.json"])["Rules"][1]["Expiration"] == {"Days": 1}
PY
then ok "cấu hình bucket: hạn giữ theo TL-03, chính sách không cấp xoá ngoài uploads-tmp, vòng đời"; else loi "cấu hình bucket"; fi
tu_choi "quyền xoá trên bucket có Object Lock" "xoa-tam" --set 'nguoiDung.evidence-service.quyen.evidence={doc,xoa-tam}'
tu_choi "hết hạn trên bucket có Object Lock" "hetHanSauNgay" --set buckets.dossier.vongDoi.hetHanSauNgay=30
tu_choi "Object Lock không bật versioning" "versioning" --set buckets.evidence.versioning=false
tu_choi "ảnh không ghim digest" "SEC-55" --set job.image.digest=latest
tu_choi "quyền trên bucket không khai báo" "không có trong buckets" --set 'nguoiDung.evidence-service.quyen.backup={doc}'
tu_choi "thiếu mã cell" "cần cell" --set cell=
echo; echo "Tổng: $dat đạt, $hong hỏng"
[ "$hong" -eq 0 ]
