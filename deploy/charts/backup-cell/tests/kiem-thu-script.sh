#!/usr/bin/env bash
# Kiểm thử script của backup-cell (INF-17, INF-18) với kubectl, aws, curl, etcdctl, etcdutl giả lập, rclone, git và
# PostgreSQL thật:
#   - restore-check.sh: chọn cụm theo luân phiên, dựng ObjectStore chỉ đọc và cụm tạm đúng mốc PITR, truy vấn kiểm
#     (kiem-tra.sql, kiem-so-cai.sql) chạy thật trên PostgreSQL; dữ liệu mồ côi khoá ngoại, mất dòng, chuỗi băm đứt,
#     Job khôi phục hỏng đều cho backup_restore_check_success 0 và mã thoát khác 0; cụm tạm luôn bị xoá.
#   - giu-dai-han.sh: chỉ chạy ngày cuối tháng; chép đúng bản nền và đoạn WAL, hạn giữ 12 tháng hoặc 10 năm cuối năm.
#   - chup-etcd.sh: mã hoá bằng age thật, giải mã lại khớp snapshot, tệp rõ bị xoá, recipient sai thì dừng.
#   - INF-18: sao-luu-minh-chung.sh (rclone thật, remote cục bộ), kiem-mau-minh-chung.sh (RLS và ro_evidence thật, phát
#     hiện bản sao bị sửa, bị mất), sao-luu-clickhouse.sh, khoi-phuc-thu-clickhouse.sh, khoi-phuc-thu-opensearch.sh
#     (HTTP giả), sao-luu-git.sh (git thật, kho file://), kiem-keo-harbor.sh (registry giả, digest thật).
# Cần: bash, jq, age, rclone, git, psql và một cụm PostgreSQL trống được phép tạo CSDL (PGHOST, PGPORT, PGUSER siêu người dùng).
# Dùng: PGHOST=127.0.0.1 PGPORT=54330 PGUSER=postgres deploy/charts/backup-cell/tests/kiem-thu-script.sh
set -euo pipefail
GOC=$(cd "$(dirname "$0")/.." && pwd)
W=$(mktemp -d)
trap 'rm -rf "${W:?}"' EXIT
dat=0; hong=0
ok()  { echo "  [ĐẠT] $*"; dat=$((dat + 1)); }
loi() { echo "  [HỎNG] $*"; hong=$((hong + 1)); }
kiem() { local mo_ta=$1; shift; if "$@"; then ok "$mo_ta"; else loi "$mo_ta"; fi; }
: "${PGHOST:?cần PGHOST}" "${PGPORT:?cần PGPORT}"
export PGUSER=${PGUSER:-postgres}

mkdir -p "$W/bin" "$W/cfg" "$W/kho"
cp "$GOC"/files/* "$W/cfg/"
export CFG=$W/cfg PATH="$W/bin:$PATH" GL=$W

# kubectl giả: ghi lệnh vào $GL/kubectl.log; apply ghi manifest vào $GL/apply.yaml; exec psql chạy psql thật trên cụm
# kiểm thử; get backups đọc $GL/backups.json; trạng thái cụm tạm theo GIA_LAP_SAN_SANG, Job khôi phục theo GIA_LAP_JOB_HONG.
cat > "$W/bin/kubectl" <<'EOF'
#!/usr/bin/env bash
echo "kubectl $*" >> "$GL/kubectl.log"
case " $* " in
  *" apply -f - "*) cat >> "$GL/apply.yaml"; echo "---" >> "$GL/apply.yaml" ;;
  *" exec "*)
    while [ "$1" != -- ]; do shift; done; shift; shift   # bỏ tới "-- psql"
    exec psql "$@" ;;
  *" get cluster "*) [ "${GIA_LAP_SAN_SANG:-True}" = True ] && echo True ;;
  *" get jobs "*) [ "${GIA_LAP_JOB_HONG:-0}" = 1 ] && echo 1; true ;;
  *" get backups.postgresql.cnpg.io "*) cat "$GL/backups.json" ;;
  *" get pods -A -o json "*) cat "$GIA_LAP_PODS" ;;
  *" delete "*) ;;
  *) echo "kubectl giả: lệnh lạ $*" >&2; exit 2 ;;
esac
EOF
# aws giả: kho là danh sách khoá trong $GL/kho.txt; s3 cp từ s3:// ghi vào $GL/chep.log, từ tệp cục bộ thì chép tệp
# vào $GL/kho/; put-object-retention ghi $GL/han.log.
cat > "$W/bin/aws" <<'EOF'
#!/usr/bin/env bash
echo "aws $*" >> "$GL/aws.log"
while [ $# -gt 0 ] && [ "$1" != s3 ] && [ "$1" != s3api ]; do shift; done
lenh=$1 con=$2; shift 2
if [ "$lenh" = s3api ] && [ "$con" = list-objects-v2 ]; then
  tien_to="" phan_cach=""
  while [ $# -gt 0 ]; do case $1 in --prefix) tien_to=$2; shift ;; --delimiter) phan_cach=$2; shift ;; esac; shift; done
  grep -F -- "$tien_to" "$GL/kho.txt" | awk -v p="$tien_to" -v d="$phan_cach" 'index($0, p) == 1 { r = substr($0, length(p) + 1); if (d == "" || index(r, d) == 0) print }' \
    | jq -R . | jq -s '{Contents: map({Key: .})}'
elif [ "$lenh" = s3 ] && [ "$con" = cp ]; then
  # Bỏ tham số tuỳ chọn (--only-show-errors). s3:// sang s3:// ghi chep.log; s3:// về tệp cục bộ đọc từ $GL/s3/.
  while [ "${1#--}" != "$1" ]; do shift; done
  case $1 in
    s3://*) case $2 in s3://*) echo "$1 $2" >> "$GL/chep.log" ;; *) [ -f "$GL/s3/${1#s3://}" ] && cp "$GL/s3/${1#s3://}" "$2" ;; esac ;;
    *) cp "$1" "$GL/kho/$(basename "$2")"; echo "$2" >> "$GL/day.log" ;;
  esac
elif [ "$lenh" = s3api ] && [ "$con" = put-object-retention ]; then
  while [ $# -gt 0 ]; do case $1 in --key) k=$2; shift ;; --retention) r=$2; shift ;; esac; shift; done
  echo "$k $r" >> "$GL/han.log"
else echo "aws giả: lệnh lạ" >&2; exit 2; fi
EOF
# curl giả: URL Pushgateway ghi chỉ số vào $GL/chi-so-<nhóm>; URL khác chuyển cho $GL/curl-gia.sh (ClickHouse,
# OpenSearch, Harbor giả của từng ca kiểm thử INF-18).
cat > "$W/bin/curl" <<'EOF'
#!/usr/bin/env bash
for a in "$@"; do u=$a; done
case $u in
  */metrics/*) cat > "$GL/chi-so-$(echo "${u#*/metrics/}" | tr '/' '_')" ;;
  *) exec bash "$GL/curl-gia.sh" "$@" ;;
esac
EOF
cat > "$W/bin/etcdctl" <<'EOF'
#!/usr/bin/env bash
echo "etcdctl $*" >> "$GL/etcd.log"
for a in "$@"; do f=$a; done
head -c 4096 /dev/urandom > "$f"; cp "$f" "$GL/snapshot-goc.db"
EOF
cat > "$W/bin/etcdutl" <<'EOF'
#!/usr/bin/env bash
echo "etcdutl $*" >> "$GL/etcd.log"; echo "| HASH | REVISION |"
EOF
chmod +x "$W"/bin/*
moi() { rm -f "$GL"/*.log "$GL"/apply.yaml "$GL"/chi-so-*; }
mkdir -p "$GL/s3"

echo "== Dữ liệu kiểm thử trên PostgreSQL $PGHOST:$PGPORT"
for db in bkt_td bkt_ledger; do psql -X -q -d postgres -c "DROP DATABASE IF EXISTS $db" -c "CREATE DATABASE $db"; done
psql -X -q -v ON_ERROR_STOP=1 -d bkt_td <<'SQL'
CREATE SCHEMA okr;
CREATE TABLE okr.objective (tenant_id uuid, id bigint, PRIMARY KEY (tenant_id, id));
CREATE TABLE okr.key_result (tenant_id uuid, id bigint PRIMARY KEY, objective_id bigint,
  FOREIGN KEY (tenant_id, objective_id) REFERENCES okr.objective (tenant_id, id));
INSERT INTO okr.objective SELECT '00000000-0000-0000-0000-00000000000a', g FROM generate_series(1, 3000) g;
INSERT INTO okr.key_result SELECT '00000000-0000-0000-0000-00000000000a', g, g FROM generate_series(1, 2000) g;
INSERT INTO okr.key_result VALUES (NULL, 9001, 5), ('00000000-0000-0000-0000-00000000000a', 9002, NULL);
ALTER TABLE okr.key_result SET (autovacuum_enabled = false);
ANALYZE;
SQL
psql -X -q -v ON_ERROR_STOP=1 -d bkt_ledger <<'SQL'
CREATE SCHEMA ledger;
CREATE TABLE ledger.ledger_entry (tenant_id uuid, chain_partition smallint, seq_no bigint, prev_hash bytea, entry_hash bytea,
  PRIMARY KEY (tenant_id, chain_partition, seq_no));
CREATE TABLE ledger.chain_head (tenant_id uuid, chain_partition smallint, last_seq_no bigint, last_hash bytea,
  PRIMARY KEY (tenant_id, chain_partition));
INSERT INTO ledger.ledger_entry
SELECT '00000000-0000-0000-0000-00000000000a', p, s,
       CASE WHEN s = 1 THEN '\x00'::bytea ELSE sha256(convert_to(p || ':' || (s - 1), 'UTF8')) END,
       sha256(convert_to(p || ':' || s, 'UTF8'))
FROM generate_series(0, 3) p, generate_series(1, 50) s;
INSERT INTO ledger.chain_head SELECT '00000000-0000-0000-0000-00000000000a', p, 50, sha256(convert_to(p || ':50', 'UTF8'))
FROM generate_series(0, 3) p;
SQL

export NS=data-pg-restore BUCKET=bk-cell-g4-pg PLUGIN=barman-cloud.cloudnative-pg.io CRED_SECRET=bk-sn-s3-pg
export CA_SECRET=vt-noi-bo-ca CA_KEY=ca.crt IMAGE_PG=anh/pg:17@sha256:0 STORAGE_CLASS=nvme-local CHO_GIAY=60 NGHI=0
export KIEM_FK=true NGUONG_DONG=1000 TI_LE=0.5 LECH_NGAY=0 LUI_GIAY=3600 S3_ENDPOINT=https://s3-bk.sn.vt.local
export S3_REGION=sn-bk PUSHGATEWAY=http://pushgateway:9091 CELL=g4
export RESOURCES='{"requests":{"cpu":"1"}}' NODE_SELECTOR='{}' TOLERATIONS='[]'
export KICH_THUOC="pg-core=7Ti pg-ledger=2Ti" SO_CAI="pg-ledger=bkt_ledger"
# 2026-10-23 là ngày 20749 kể từ 1970-01-01: 20749 mod 2 = 1 → cụm thứ hai; 20749 mod 3 = 1.
export NGAY=2026-10-23 BAY_GIO=1792706400   # 2026-10-23T05:00:00+07:00

echo "== restore-check.sh"
moi
if CUMS="pg-core:data-pg pg-ledger:secure-data" bash "$CFG/restore-check.sh" > "$W/rc.out" 2>&1; then
  ok "khôi phục thử pg-ledger (luân phiên ngày 20749 mod 2 = 1) đạt"
else loi "khôi phục thử đạt"; cat "$W/rc.out"; fi
kiem "ObjectStore chỉ đọc trỏ s3://bk-cell-g4-pg/pg-ledger, cụm tạm rc-pg-ledger-20261023 từ serverName pg-ledger" \
  python3 -I - "$GL/apply.yaml" <<'PY'
import sys, yaml
d = [x for x in yaml.safe_load_all(open(sys.argv[1])) if x]
os_, cl = d
assert os_["kind"] == "ObjectStore" and os_["metadata"]["name"] == "rc-pg-ledger", os_
assert os_["spec"]["configuration"]["destinationPath"] == "s3://bk-cell-g4-pg/pg-ledger"
assert os_["spec"]["configuration"]["endpointCA"] == {"name": "vt-noi-bo-ca", "key": "ca.crt"}
assert "retentionPolicy" not in os_["spec"]
assert cl["kind"] == "Cluster" and cl["metadata"]["name"] == "rc-pg-ledger-20261023", cl
s = cl["spec"]
assert "plugins" not in s, "cụm tạm không được lưu trữ WAL"
assert s["bootstrap"]["recovery"]["recoveryTarget"]["targetTime"] == "2026-10-22 21:00:00+00", s["bootstrap"]
ex = s["externalClusters"][0]["plugin"]
assert ex["parameters"] == {"barmanObjectName": "rc-pg-ledger", "serverName": "pg-ledger"}, ex
assert s["storage"]["size"] == "2Ti" and s["instances"] == 1 and s["resources"] == {"requests": {"cpu": "1"}}
PY
kiem "chỉ số đạt: success 1, có last_success" grep -q '^backup_restore_check_success 1$' "$GL/chi-so-job_restore-check_cell_g4_cluster_pg-ledger"
kiem "  và last_success_timestamp" grep -q '^backup_restore_check_last_success_timestamp_seconds 1792706400$' "$GL/chi-so-job_restore-check_cell_g4_cluster_pg-ledger"
kiem "cụm tạm và ObjectStore bị xoá" grep -q "delete cluster rc-pg-ledger-20261023" "$GL/kubectl.log"
kiem "kiểm sổ cái chạy trên bkt_ledger" grep -q "sổ cái bkt_ledger: đạt" "$W/rc.out"
# INF-28: ở TTDL không khởi tạo replica cluster, bản sao lưu nằm dưới serverName <cụm>-<ttdl>.
moi
CUMS="pg-core:data-pg pg-ledger:secure-data" HAU_TO_SERVER=-sn bash "$CFG/restore-check.sh" > "$W/rc-sn.out" 2>&1 || cat "$W/rc-sn.out"
kiem "TTDL sn: cụm tạm khôi phục từ serverName pg-ledger-sn" grep -q "serverName: pg-ledger-sn$" "$GL/apply.yaml"

ca_hong() { # <mô tả> <SQL làm hỏng> <SQL sửa lại> <CSDL> <thông điệp mong đợi>
  psql -X -q -v ON_ERROR_STOP=1 -d "$4" -c "$2"
  moi
  if CUMS="pg-core:data-pg pg-ledger:secure-data" bash "$CFG/restore-check.sh" > "$W/rc.out" 2>&1; then loi "$1: không bị phát hiện"
  elif grep -q '^backup_restore_check_success 0$' "$GL"/chi-so-* && ! grep -q last_success "$GL"/chi-so-* \
       && grep -q "delete cluster rc-pg-ledger-20261023" "$GL/kubectl.log" && grep -q "$5" "$W/rc.out"; then ok "$1: success 0, không cập nhật last_success, cụm tạm vẫn bị xoá"
  else loi "$1: kết quả sai"; cat "$W/rc.out"; fi
  psql -X -q -v ON_ERROR_STOP=1 -d "$4" -c "$3"
}
ca_hong "dòng mồ côi khoá ngoại" \
  "SET session_replication_role = replica; INSERT INTO okr.key_result VALUES ('00000000-0000-0000-0000-00000000000a', 9100, 99999)" \
  "DELETE FROM okr.key_result WHERE id = 9100" bkt_td "có dòng không trỏ tới okr.objective"
ca_hong "mất dòng so thống kê (còn 500 trên khoảng 2000 dòng)" \
  "DELETE FROM okr.key_result WHERE id BETWEEN 501 AND 2000" \
  "INSERT INTO okr.key_result SELECT '00000000-0000-0000-0000-00000000000a', g, g FROM generate_series(501, 2000) g" bkt_td "bảng okr.key_result còn 502 dòng"
ca_hong "chuỗi băm sổ cái đứt" \
  "UPDATE ledger.ledger_entry SET prev_hash = '\\x01' WHERE chain_partition = 2 AND seq_no = 20" \
  "UPDATE ledger.ledger_entry SET prev_hash = sha256(convert_to('2:19', 'UTF8')) WHERE chain_partition = 2 AND seq_no = 20" bkt_ledger "1 bản ghi không nối liền"
ca_hong "đầu chuỗi không khớp" \
  "UPDATE ledger.chain_head SET last_seq_no = 49 WHERE chain_partition = 1" \
  "UPDATE ledger.chain_head SET last_seq_no = 50 WHERE chain_partition = 1" bkt_ledger "1 đầu chuỗi không khớp"
moi
if GIA_LAP_SAN_SANG=False GIA_LAP_JOB_HONG=1 CUMS="pg-core:data-pg pg-ledger:secure-data" bash "$CFG/restore-check.sh" > "$W/rc.out" 2>&1; then
  loi "Job khôi phục hỏng: không bị phát hiện"
else kiem "Job khôi phục hỏng (bản nền hỏng): dừng sớm, success 0" grep -q '^backup_restore_check_success 0$' "$GL/chi-so-job_restore-check_cell_g4_cluster_pg-ledger"; fi
moi
if GIA_LAP_SAN_SANG=False CHO_GIAY=0 CUMS="pg-core:data-pg" bash "$CFG/restore-check.sh" > "$W/rc.out" 2>&1; then loi "quá hạn chờ: không bị phát hiện"
else kiem "quá hạn chờ sẵn sàng: hỏng, cụm tạm rc-pg-core-20261023 bị xoá" grep -q "delete cluster rc-pg-core-20261023" "$GL/kubectl.log"; fi
moi
CUMS="pg-core:data-pg pg-iam:data-pg pg-ledger:secure-data" LECH_NGAY=1 SO_CAI="" bash "$CFG/restore-check.sh" > "$W/rc.out" 2>&1 || true
kiem "luân phiên với lệch ngày: (20749 + 1) mod 3 = 2 → pg-ledger" grep -q "Khôi phục thử pg-ledger" "$W/rc.out"

echo "== giu-dai-han.sh"
export CUM_NAM="pg-ledger pg-secure" GIU_THANG=12 GIU_NAM_NAM=10
cat > "$GL/kho.txt" <<'EOF'
pg-core/pg-core/base/20261030T183000/backup.info
pg-core/pg-core/base/20261030T183000/data.tar
pg-core/pg-core/base/20261031T183000/backup.info
pg-core/pg-core/base/20261031T183000/data.tar
pg-core/pg-core/wals/00000002.history
pg-core/pg-core/wals/0000000200000041/000000020000004100000010.zst
pg-core/pg-core/wals/0000000200000041/000000020000004100000011.zst
pg-core/pg-core/wals/0000000200000041/000000020000004100000011.00000028.backup.zst
pg-core/pg-core/wals/0000000200000041/0000000200000041000000FF.zst
pg-core/pg-core/wals/0000000200000042/000000020000004200000000.zst
pg-core/pg-core/wals/0000000200000042/000000020000004200000001.zst
pg-ledger/pg-ledger/base/20261231T183000/data.tar
pg-ledger/pg-ledger/wals/0000000100000003/000000010000000300000007.zst
EOF
bk() { # <cụm> <stoppedAt> <backupId> <beginWal> <endWal>
  jq -n --arg c "$1" --arg t "$2" --arg id "$3" --arg b "$4" --arg e "$5" \
    '{metadata: {labels: {"cnpg.io/cluster": $c}}, spec: {method: "plugin"}, status: {phase: "completed", stoppedAt: $t, backupId: $id, beginWal: $b, endWal: $e}}'
}
moi
NGAY=2026-10-30 CUMS="pg-core:data-pg" bash "$CFG/giu-dai-han.sh" > "$W/gd.out" 2>&1
kiem "ngày 30/10 không phải cuối tháng: không chép gì" test ! -s "$GL/chep.log"
# Bản nền 31/10 01:30 giờ Việt Nam (18:30 UTC ngày 30/10, có phần lẻ giây), bản cũ hơn một ngày bị bỏ qua.
jq -s '{items: .}' <(bk pg-core 2026-10-29T18:35:00Z 20261030T183000 000000020000004100000001 000000020000004100000002) \
  <(bk pg-core 2026-10-30T18:41:07.123Z 20261031T183000 000000020000004100000011 000000020000004200000000) > "$GL/backups.json"
moi
if NGAY=2026-10-31 BAY_GIO=1793395800 CUMS="pg-core:data-pg" bash "$CFG/giu-dai-han.sh" > "$W/gd.out" 2>&1; then ok "31/10: chạy đạt"
else loi "31/10: chạy hỏng"; cat "$W/gd.out"; fi
kiem "chép đúng bản nền 20261031T183000, .history và WAL 41/11..42/00 (gồm .backup), bỏ WAL ngoài đoạn" \
  python3 -I - "$GL/chep.log" "$GL/han.log" <<'PY'
import sys
chep = [l.split() for l in open(sys.argv[1])]
nguon = sorted(s.split("/", 3)[3] for s, _ in chep)
assert nguon == sorted([
    "pg-core/pg-core/base/20261031T183000/backup.info", "pg-core/pg-core/base/20261031T183000/data.tar",
    "pg-core/pg-core/wals/00000002.history",
    "pg-core/pg-core/wals/0000000200000041/000000020000004100000011.zst",
    "pg-core/pg-core/wals/0000000200000041/000000020000004100000011.00000028.backup.zst",
    "pg-core/pg-core/wals/0000000200000041/0000000200000041000000FF.zst",
    "pg-core/pg-core/wals/0000000200000042/000000020000004200000000.zst"]), nguon
for s, d in chep:
    assert d == "s3://bk-cell-g4-pg/dai-han/thang/pg-core/" + s.split("/", 5)[5], (s, d)
han = [l.split() for l in open(sys.argv[2])]
assert len(han) == len(chep) and all(r == "Mode=COMPLIANCE,RetainUntilDate=2027-10-31T00:00:00Z" for _, r in han), han
PY
kiem "chỉ số giữ dài hạn đạt" grep -q '^backup_long_term_copy_success 1$' "$GL/chi-so-job_giu-dai-han_cell_g4"
bk pg-ledger 2026-12-30T18:40:00Z 20261231T183000 000000010000000300000007 000000010000000300000007 | jq -s '{items: .}' > "$GL/backups.json"
moi
NGAY=2026-12-31 BAY_GIO=1798666200 CUMS="pg-ledger:secure-data" bash "$CFG/giu-dai-han.sh" > "$W/gd.out" 2>&1 || cat "$W/gd.out"
kiem "31/12 pg-ledger: sang dai-han/nam/, giữ 10 năm tới 2036-12-31" \
  bash -c "grep -q 's3://bk-cell-g4-pg/dai-han/nam/pg-ledger/base/20261231T183000/data.tar' '$GL/chep.log' && ! grep -qv 'RetainUntilDate=2036-12-31T00:00:00Z' '$GL/han.log'"
moi
if NGAY=2026-12-31 BAY_GIO=1798666200 CUMS="pg-ledger:secure-data pg-core:data-pg" bash "$CFG/giu-dai-han.sh" > "$W/gd.out" 2>&1; then
  loi "thiếu bản nền pg-core: không báo lỗi"
else kiem "thiếu bản nền trong 24 giờ: mã thoát khác 0, chỉ số 0, cụm khác vẫn được chép" \
  bash -c "grep -q '^backup_long_term_copy_success 0$' '$GL/chi-so-job_giu-dai-han_cell_g4' && grep -q dai-han/nam/pg-ledger '$GL/chep.log'"; fi

echo "== chup-etcd.sh"
age-keygen -o "$W/khoa.txt" 2> /dev/null
age-keygen -y "$W/khoa.txt" > "$W/recipient"
mkdir -p "$W/tam"
moi
if NODE_NAME=master-1 TAM=$W/tam RECIPIENT_FILE=$W/recipient ETCD_ENDPOINTS=https://127.0.0.1:2379 PKI=/pki BUCKET=bk-cell-g4-k8s PREFIX=etcd \
     bash "$CFG/chup-etcd.sh" > "$W/etcd.out" 2>&1; then ok "chụp etcd đạt"; else loi "chụp etcd"; cat "$W/etcd.out"; fi
kiem "đẩy lên s3://bk-cell-g4-k8s/etcd/master-1-20261022T2200Z.db.age" grep -qx "s3://bk-cell-g4-k8s/etcd/master-1-20261022T2200Z.db.age" "$GL/day.log"
kiem "bản mã hoá giải mã bằng khoá OpenBao khớp snapshot" bash -c "age -d -i '$W/khoa.txt' '$GL/kho/master-1-20261022T2200Z.db.age' | cmp -s - '$GL/snapshot-goc.db'"
kiem "không còn tệp rõ hay bản mã hoá trong thư mục tạm" bash -c "[ -z \"\$(ls -A '$W/tam')\" ]"
kiem "etcdutl snapshot status được gọi" grep -q "etcdutl snapshot status" "$GL/etcd.log"
kiem "chỉ số etcd đạt" grep -q '^backup_etcd_snapshot_success 1$' "$GL/chi-so-job_etcd-snapshot_cell_g4_node_master-1"
echo "khong-phai-age" > "$W/sai"
moi
if NODE_NAME=master-1 TAM=$W/tam RECIPIENT_FILE=$W/sai ETCD_ENDPOINTS=x PKI=/pki BUCKET=b PREFIX=etcd bash "$CFG/chup-etcd.sh" > "$W/etcd.out" 2>&1; then
  loi "recipient sai: không dừng"
else kiem "recipient sai: dừng trước khi chụp, chỉ số 0" bash -c "[ ! -s '$GL/etcd.log' ] && grep -q '^backup_etcd_snapshot_success 0$' '$GL/chi-so-job_etcd-snapshot_cell_g4_node_master-1'"; fi

# ======================================================================== INF-18
echo "== sao-luu-minh-chung.sh (rclone thật, remote cục bộ)"
# Nguồn: hai tệp minh chứng khoá theo băm của tenant A; đích là thư mục $GL/s3 (aws giả đọc lại khi kiểm mẫu).
TA=11111111-1111-4111-8111-111111111111 TB=22222222-2222-4222-8222-222222222222
mkdir -p "$W/nguon"
dat_tep() { # <bucket> <tenant> <nội dung> → in khoá và băm
  local bam; bam=$(printf '%s' "$3" | sha256sum | cut -d' ' -f1)
  mkdir -p "$W/nguon/$1/t/$2/sha256/${bam:0:2}"; printf '%s' "$3" > "$W/nguon/$1/t/$2/sha256/${bam:0:2}/$bam"
  echo "t/$2/sha256/${bam:0:2}/$bam $bam ${#3}"
}
read -r K1 B1 C1 < <(dat_tep ev-cell-g4 "$TA" "bien-ban-hop-chi-bo-thang-9")
read -r K2 B2 C2 < <(dat_tep ev-cell-g4 "$TA" "bao-cao-ket-qua-quy-3")
read -r K3 B3 C3 < <(dat_tep ev-cell-g4 "$TB" "minh-chung-tenant-b")
read -r K4 _ _ < <(dat_tep ds-cell-g4 "$TA" "bieu-mau-da-ky")
export RCLONE_CONFIG_NGUON_TYPE=alias RCLONE_CONFIG_NGUON_REMOTE=$W/nguon RCLONE_CONFIG_DICH_TYPE=alias RCLONE_CONFIG_DICH_REMOTE=$GL/s3
MC=(env BUCKETS="ev-cell-g4:t ds-cell-g4:t" BUCKET_DICH=bk-cell-g4-ev RCLONE_THAM_SO="--transfers 4")
moi
if "${MC[@]}" bash "$CFG/sao-luu-minh-chung.sh" > "$W/mc.out" 2>&1; then ok "sao lưu ngày lần đầu đạt"; else loi "sao lưu ngày"; cat "$W/mc.out"; fi
kiem "bản sao giữ nguyên khoá theo băm dưới hien-tai/<bucket>/" cmp -s "$W/nguon/ev-cell-g4/$K1" "$GL/s3/bk-cell-g4-ev/hien-tai/ev-cell-g4/$K1"
kiem "biểu mẫu ký cũng được sao lưu" test -f "$GL/s3/bk-cell-g4-ev/hien-tai/ds-cell-g4/$K4"
kiem "chỉ số đồng bộ đạt cho cả hai bucket" bash -c "grep -q '^backup_evidence_sync_success{bucket=\"ev-cell-g4\"} 1$' '$GL/chi-so-job_sao-luu-minh-chung_cell_g4' && grep -q '^backup_evidence_sync_success{bucket=\"ds-cell-g4\"} 1$' '$GL/chi-so-job_sao-luu-minh-chung_cell_g4'"
# Nguồn bị sửa nội dung (đổi kích thước) dưới cùng khoá: --immutable không ghi đè bản sao, job lỗi.
cp "$W/nguon/ev-cell-g4/$K2" "$W/k2.goc"; printf 'da-bi-sua' >> "$W/nguon/ev-cell-g4/$K2"
moi
if "${MC[@]}" bash "$CFG/sao-luu-minh-chung.sh" > "$W/mc.out" 2>&1; then loi "nguồn bị sửa: job không báo lỗi"
else kiem "nguồn bị sửa: job lỗi, bản sao giữ nội dung cũ, chỉ số 0" bash -c "cmp -s '$W/k2.goc' '$GL/s3/bk-cell-g4-ev/hien-tai/ev-cell-g4/$K2' && grep -q '^backup_evidence_sync_success{bucket=\"ev-cell-g4\"} 0$' '$GL/chi-so-job_sao-luu-minh-chung_cell_g4'"; fi
cp "$W/k2.goc" "$W/nguon/ev-cell-g4/$K2"
# Tệp mất ở nguồn: bản sao dời sang thay-the/<ngày>/ thay vì mất (trên kho thật khoá không có quyền xoá nên bước dời
# lỗi và bản sao ở hien-tai giữ nguyên).
mv "$W/nguon/ev-cell-g4/$K3" "$W/k3.goc"
moi
"${MC[@]}" bash "$CFG/sao-luu-minh-chung.sh" > "$W/mc.out" 2>&1 || true
kiem "tệp mất ở nguồn được giữ ở thay-the/2026-10-23/" test -f "$GL/s3/bk-cell-g4-ev/thay-the/2026-10-23/ev-cell-g4/$K3"
mkdir -p "$(dirname "$GL/s3/bk-cell-g4-ev/hien-tai/ev-cell-g4/$K3")"; cp "$W/k3.goc" "$GL/s3/bk-cell-g4-ev/hien-tai/ev-cell-g4/$K3"
mv "$W/k3.goc" "$W/nguon/ev-cell-g4/$K3"
unset RCLONE_CONFIG_NGUON_TYPE RCLONE_CONFIG_NGUON_REMOTE RCLONE_CONFIG_DICH_TYPE RCLONE_CONFIG_DICH_REMOTE

echo "== kiem-mau-minh-chung.sh (PostgreSQL thật, RLS, ro_evidence)"
psql -X -q -d postgres -c "DROP DATABASE IF EXISTS bkt_ev" -c "CREATE DATABASE bkt_ev"
psql -X -q -d postgres -c "DO \$\$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'ro_evidence') THEN CREATE ROLE ro_evidence LOGIN NOBYPASSRLS; END IF; END \$\$"
psql -X -q -v ON_ERROR_STOP=1 -d bkt_ev -v ta="$TA" -v tb="$TB" -v k1="$K1" -v k2="$K2" -v k3="$K3" -v b1="$B1" -v b2="$B2" -v b3="$B3" \
  -v c1="$C1" -v c2="$C2" -v c3="$C3" <<'SQL'
CREATE SCHEMA evidence;
CREATE TABLE evidence.object_version (id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
  sha256 bytea NOT NULL, size_bytes bigint NOT NULL, bucket varchar(63) NOT NULL, object_key varchar(512) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now());
ALTER TABLE evidence.object_version ENABLE ROW LEVEL SECURITY;
CREATE POLICY p_tenant ON evidence.object_version USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
GRANT USAGE ON SCHEMA evidence TO ro_evidence;
GRANT SELECT ON evidence.object_version TO ro_evidence;
INSERT INTO evidence.object_version (tenant_id, sha256, size_bytes, bucket, object_key, created_at) VALUES
  (:'ta', decode(:'b1', 'hex'), :c1, 'ev-cell-g4', :'k1', '2026-10-20'),
  (:'ta', decode(:'b2', 'hex'), :c2, 'ev-cell-g4', :'k2', '2026-10-21'),
  (:'tb', decode(:'b3', 'hex'), :c3, 'ev-cell-g4', :'k3', '2026-10-21'),
  -- tạo trong ngày kiểm: chưa qua lần sao lưu đêm, không được chọn
  (:'ta', decode(:'b1', 'hex'), :c1, 'ev-cell-g4', 't/chua-sao-luu', '2026-10-23 03:00');
SQL
KM=(env TENANTS="$TA,$TB" SO_MAU=10 BUCKET_DICH=bk-cell-g4-ev TAM="$W/tam" PGDATABASE=bkt_ev PGUSER=ro_evidence)
moi
if "${KM[@]}" bash "$CFG/kiem-mau-minh-chung.sh" > "$W/km.out" 2>&1; then ok "kiểm mẫu bản sao còn nguyên: đạt"; else loi "kiểm mẫu bản sao còn nguyên"; cat "$W/km.out"; fi
kiem "  kiểm đúng 3 đối tượng của hai tenant (RLS theo app.tenant_id, bỏ đối tượng tạo trong ngày)" grep -q '^backup_evidence_sample_checked 3$' "$GL/chi-so-job_kiem-mau-minh-chung_cell_g4"
kiem "  có last_success" grep -q '^backup_evidence_sample_last_success_timestamp_seconds 1792706400$' "$GL/chi-so-job_kiem-mau-minh-chung_cell_g4"
# Sửa có chủ đích một bản sao (cùng kích thước), xoá một bản sao khác.
printf 'X' | dd of="$GL/s3/bk-cell-g4-ev/hien-tai/ev-cell-g4/$K1" bs=1 seek=0 conv=notrunc status=none
rm "$GL/s3/bk-cell-g4-ev/hien-tai/ev-cell-g4/$K3"
moi
if "${KM[@]}" bash "$CFG/kiem-mau-minh-chung.sh" > "$W/km.out" 2>&1; then loi "bản sao bị sửa: job không báo lỗi"
else kiem "bản sao bị sửa và bị mất: job lỗi, lệch 1, thiếu 1, không có last_success" bash -c "grep -q '^backup_evidence_sample_mismatch 1$' '$GL/chi-so-job_kiem-mau-minh-chung_cell_g4' && grep -q '^backup_evidence_sample_missing 1$' '$GL/chi-so-job_kiem-mau-minh-chung_cell_g4' && ! grep -q last_success '$GL/chi-so-job_kiem-mau-minh-chung_cell_g4' && grep -q 'LỆCH: ev-cell-g4/$K1' '$W/km.out'"; fi
moi
if env TENANTS="$TA" SO_MAU=1 BUCKET_DICH=bk-cell-g4-ev TAM="$W/tam" PGDATABASE=bkt_ev PGUSER=ro_evidence bash "$CFG/kiem-mau-minh-chung.sh" > "$W/km.out" 2>&1 \
   || grep -q '^backup_evidence_sample_checked 1$' "$GL/chi-so-job_kiem-mau-minh-chung_cell_g4"; then ok "SO_MAU=1 với một tenant: kiểm đúng 1 đối tượng"
else loi "SO_MAU=1"; cat "$W/km.out"; fi
psql -X -q -d postgres -c "DROP DATABASE IF EXISTS bkt_ev"

echo "== sao-luu-clickhouse.sh, khoi-phuc-thu-clickhouse.sh (HTTP giả)"
echo "mat-khau" > "$W/mk"
export CH_URL=https://ch.gia:8443 CH_USER=sao_luu CH_PASSWORD_FILE=$W/mk POLL_GIAY=0
# ClickHouse giả: ghi câu lệnh vào ch.log; BACKUP/RESTORE trả mã tác vụ; trạng thái theo GIA_LAP_CH_TT; số dòng theo
# $GL/ch-<csdl>.tsv.
cat > "$GL/curl-gia.sh" <<'X'
q=$(cat); echo "$q" >> "$GL/ch.log"
case $q in
  BACKUP*) printf 'b-1\tCREATING_BACKUP\n' ;;
  RESTORE*) printf 'r-1\tRESTORING\n' ;;
  "SELECT status"*) printf '%s\t\n' "${GIA_LAP_CH_TT:-BACKUP_CREATED}" ;;
  "SELECT name, total_rows"*) db=$(sed -n "s/.*database = '\([a-z_]*\)'.*/\1/p" <<<"$q"); cat "$GL/ch-$db.tsv" ;;
esac
X
printf '%s\n' "full-2026-10-11/.backup" "full-2026-10-18/data/x" "full-2026-10-18/.backup" "incr-2026-10-19/.backup" "full-2026-10-25/data/y" > "$GL/kho.txt"
CHB=(env BUCKET=bk-cell-g4-ch)
moi
if "${CHB[@]}" NGAY=2026-10-21 bash "$CFG/sao-luu-clickhouse.sh" > "$W/ch.out" 2>&1; then ok "thứ tư: sao lưu gia tăng đạt"; else loi "sao lưu gia tăng"; cat "$W/ch.out"; fi
kiem "  BACKUP incr-2026-10-21 nền full-2026-10-18, ON CLUSTER, async, zstd" grep -qF "BACKUP DATABASE analytics ON CLUSTER 'analytics' TO S3(bk_sn, 'incr-2026-10-21') SETTINGS async = 1, compression_method = 'zstd', base_backup = S3(bk_sn, 'full-2026-10-18') FORMAT TSV" "$GL/ch.log"
kiem "  theo dõi system.backups theo mã tác vụ" grep -qF "SELECT status, error FROM system.backups WHERE id = 'b-1'" "$GL/ch.log"
kiem "  chỉ số gia tăng đạt" grep -q '^backup_clickhouse_success{loai="incr"} 1$' "$GL/chi-so-job_sao-luu-clickhouse_cell_g4"
moi
"${CHB[@]}" NGAY=2026-10-25 bash "$CFG/sao-luu-clickhouse.sh" > "$W/ch.out" 2>&1 || true
kiem "chủ nhật: bản đầy đủ full-2026-10-25 không nền" bash -c "grep -qF \"TO S3(bk_sn, 'full-2026-10-25') SETTINGS async = 1, compression_method = 'zstd' FORMAT TSV\" '$GL/ch.log'"
moi
"${CHB[@]}" NGAY=2026-10-26 bash "$CFG/sao-luu-clickhouse.sh" > "$W/ch.out" 2>&1 || true
kiem "bản đầy đủ chủ nhật chưa xong (.backup chưa có) và bản cũ quá 6 ngày: làm bản đầy đủ" grep -qF "TO S3(bk_sn, 'full-2026-10-26')" "$GL/ch.log"
moi
if GIA_LAP_CH_TT=BACKUP_FAILED "${CHB[@]}" NGAY=2026-10-21 bash "$CFG/sao-luu-clickhouse.sh" > "$W/ch.out" 2>&1; then loi "BACKUP_FAILED: job không báo lỗi"
else kiem "BACKUP_FAILED: job lỗi, chỉ số 0" grep -q '^backup_clickhouse_success{loai="incr"} 0$' "$GL/chi-so-job_sao-luu-clickhouse_cell_g4"; fi
printf 'su_kien\t50000\nnho\t10\n' > "$GL/ch-analytics.tsv"; printf 'su_kien\t48000\nnho\t0\n' > "$GL/ch-analytics_khoi_phuc_thu.tsv"
moi
if GIA_LAP_CH_TT=RESTORED "${CHB[@]}" bash "$CFG/khoi-phuc-thu-clickhouse.sh" > "$W/ch.out" 2>&1; then ok "khôi phục thử ClickHouse đạt"; else loi "khôi phục thử ClickHouse"; cat "$W/ch.out"; fi
kiem "  RESTORE bản mới nhất incr-2026-10-19 vào analytics_khoi_phuc_thu" grep -qF "RESTORE DATABASE analytics AS analytics_khoi_phuc_thu ON CLUSTER 'analytics' FROM S3(bk_sn, 'incr-2026-10-19')" "$GL/ch.log"
kiem "  CSDL tạm bị xoá sau cùng" bash -c "tail -1 '$GL/ch.log' | grep -qF 'DROP DATABASE IF EXISTS analytics_khoi_phuc_thu'"
printf 'su_kien\t1000\n' > "$GL/ch-analytics_khoi_phuc_thu.tsv"
moi
if GIA_LAP_CH_TT=RESTORED "${CHB[@]}" bash "$CFG/khoi-phuc-thu-clickhouse.sh" > "$W/ch.out" 2>&1; then loi "thiếu dòng sau khôi phục: không báo lỗi"
else kiem "thiếu dòng và thiếu bảng sau khôi phục: lỗi, chỉ số 0, vẫn xoá CSDL tạm" bash -c "grep -q '^backup_clickhouse_restore_check_success 0$' '$GL/chi-so-job_khoi-phuc-thu-clickhouse_cell_g4' && grep -q 'thiếu bảng nho' '$W/ch.out' && tail -1 '$GL/ch.log' | grep -q DROP"; fi

echo "== khoi-phuc-thu-opensearch.sh (HTTP giả)"
export OS_URL=https://os.gia:9200 OS_USER=sao-luu OS_PASSWORD_FILE=$W/mk
cat > "$GL/curl-gia.sh" <<'X'
m=GET body=""
while [ $# -gt 1 ]; do case $1 in -X) m=$2; shift ;; --data-binary) body=$2; shift ;; esac; shift; done
p=${1#"$OS_URL"}; echo "$m $p $body" >> "$GL/os.log"
case "$m $p" in
  "GET /_snapshot/bk-sn/_all") echo '{"snapshots":[{"snapshot":"daily-to-sn-cu","state":"SUCCESS","start_time_in_millis":1},{"snapshot":"daily-to-sn-moi","state":"SUCCESS","start_time_in_millis":5},{"snapshot":"daily-to-sn-hong","state":"FAILED","start_time_in_millis":9}]}' ;;
  "GET /_snapshot/bk-sn/daily-to-sn-moi") echo '{"snapshots":[{"indices":["okr-2026","okr-2025","org-don-vi","mission-2026"]}]}' ;;
  "POST /_snapshot/bk-sn/daily-to-sn-moi/_restore"*) echo "{\"snapshot\":{\"shards\":{\"failed\":${GIA_LAP_OS_HONG:-0}}}}" ;;
  "GET /_cat/count/"*) i=${p#/_cat/count/}; i=${i%%\?*}; echo "[{\"count\":\"$(cat "$GL/os-$i" 2>/dev/null || echo 0)\"}]" ;;
  DELETE*) echo '{"acknowledged":true}' ;;
  *) exit 22 ;;
esac
X
for i in okr-2026 okr-2025; do echo 5000 > "$GL/os-$i"; echo 5000 > "$GL/os-khoi-phuc-thu-$i"; done
# 2026-10-23: ngày 20749, tuần 2964, 2964 mod 4 = 0 → nhóm thứ nhất; LECH_TUAN=1 → okr-*.
moi
if LECH_TUAN=1 bash "$CFG/khoi-phuc-thu-opensearch.sh" > "$W/os.out" 2>&1; then ok "khôi phục thử OpenSearch đạt"; else loi "khôi phục thử OpenSearch"; cat "$W/os.out"; fi
# shellcheck disable=SC2016  # $1 là chuỗi thay thế của OpenSearch, không phải biến shell
kiem "  chọn snapshot SUCCESS mới nhất, chỉ nhóm okr-* theo tuần, đổi tên khoi-phuc-thu-, 0 bản sao" grep -qF 'POST /_snapshot/bk-sn/daily-to-sn-moi/_restore?wait_for_completion=true {"indices":"okr-2025,okr-2026","rename_pattern":"(.+)","rename_replacement":"khoi-phuc-thu-$1","include_global_state":false,"include_aliases":false,"index_settings":{"index.number_of_replicas":0}}' "$GL/os.log"
kiem "  xoá chỉ mục tạm sau cùng" bash -c "tail -1 '$GL/os.log' | grep -qF 'DELETE /khoi-phuc-thu-okr-2025,khoi-phuc-thu-okr-2026?ignore_unavailable=true'"
kiem "  chỉ số đạt" grep -q '^backup_opensearch_restore_check_success 1$' "$GL/chi-so-job_khoi-phuc-thu-opensearch_cell_g4"
echo 100 > "$GL/os-khoi-phuc-thu-okr-2026"
moi
if LECH_TUAN=1 bash "$CFG/khoi-phuc-thu-opensearch.sh" > "$W/os.out" 2>&1; then loi "thiếu tài liệu sau khôi phục: không báo lỗi"
else kiem "thiếu tài liệu sau khôi phục: lỗi, chỉ số 0, vẫn xoá chỉ mục tạm" bash -c "grep -q '^backup_opensearch_restore_check_success 0$' '$GL/chi-so-job_khoi-phuc-thu-opensearch_cell_g4' && tail -1 '$GL/os.log' | grep -q '^DELETE'"; fi
moi
if GIA_LAP_OS_HONG=1 LECH_TUAN=1 bash "$CFG/khoi-phuc-thu-opensearch.sh" > "$W/os.out" 2>&1; then loi "shard khôi phục hỏng: không báo lỗi"
else ok "shard khôi phục hỏng: lỗi"; fi

echo "== sao-luu-git.sh (git thật, kho file://)"
G=$W/git; mkdir -p "$G/nguon/okr" "$G/guong/okr"
git init -q --bare "$G/nguon/okr/gitops.git"; git init -q --bare "$G/guong/okr/gitops.git"
git clone -q "$G/nguon/okr/gitops.git" "$G/lam" 2>/dev/null
git -C "$G/lam" -c user.name=t -c user.email=t@t commit -q --allow-empty -m "khởi tạo"
git -C "$G/lam" tag v1; git -C "$G/lam" push -q origin HEAD:refs/heads/main v1
GT=(env GIT_NGUON="file://$G/nguon" GIT_GUONG="file://$G/guong" KHO=okr/gitops BUCKET=bk-cell-g4-git TAM="$W/tam")
refs_cua() { git -C "$1" for-each-ref --format='%(objectname) %(refname)' | sort; }
moi
if "${GT[@]}" CHE_DO=guong bash "$CFG/sao-luu-git.sh" > "$W/git.out" 2>&1; then ok "đẩy gương đạt"; else loi "đẩy gương"; cat "$W/git.out"; fi
kiem "  gương có đủ nhánh và thẻ như kho gốc" bash -c "[ \"\$(git -C '$G/guong/okr/gitops.git' for-each-ref --format='%(objectname) %(refname)' | sort)\" = \"\$(git -C '$G/nguon/okr/gitops.git' for-each-ref --format='%(objectname) %(refname)' | sort)\" ]"
moi
if "${GT[@]}" CHE_DO=bundle NGAY=2026-10-25 bash "$CFG/sao-luu-git.sh" > "$W/git.out" 2>&1; then ok "chủ nhật: bundle và clone thử đạt"; else loi "bundle chủ nhật"; cat "$W/git.out"; fi
kiem "  đẩy s3://bk-cell-g4-git/bundle/okr/gitops/2026-10-25.bundle" grep -qx "s3://bk-cell-g4-git/bundle/okr/gitops/2026-10-25.bundle" "$GL/day.log"
kiem "  bundle đẩy lên là bundle hợp lệ" git -C "$G/nguon/okr/gitops.git" bundle verify -q "$GL/kho/2026-10-25.bundle"
kiem "  có chỉ số clone thử" grep -q '^backup_git_clone_check_last_success_timestamp_seconds{kho="okr/gitops"}' "$GL/chi-so-job_sao-luu-git-bundle_cell_g4"
kiem "  thư mục tạm sạch" bash -c "[ -z \"\$(ls -A '$W/tam')\" ]"
git -C "$G/lam" -c user.name=t -c user.email=t@t commit -q --allow-empty -m "mới"; git -C "$G/lam" push -q origin HEAD:refs/heads/main
moi
if "${GT[@]}" CHE_DO=bundle NGAY=2026-10-25 GIT_GUONG="file://$G/guong" bash "$CFG/sao-luu-git.sh" > "$W/git.out" 2>&1; then loi "gương cũ hơn kho gốc: clone thử không báo lỗi"
else kiem "gương tụt sau kho gốc: clone thử chủ nhật báo lỗi" grep -q "clone thử từ gương okr/gitops không khớp" "$W/git.out"; fi
moi
if "${GT[@]}" CHE_DO=guong GIT_NGUON="file://$G/khong-co" bash "$CFG/sao-luu-git.sh" > "$W/git.out" 2>&1; then loi "kho nguồn không tồn tại: không báo lỗi"
else kiem "kho nguồn không clone được: lỗi, chỉ số 0" grep -q '^backup_git_success{kho="okr/gitops",che_do="guong"} 0$' "$GL/chi-so-job_sao-luu-git-guong_cell_g4"; fi

echo "== kiem-keo-harbor.sh (registry giả, digest thật)"
mkdir -p "$GL/harbor"
m1='{"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json","layers":[]}'
m2='{"schemaVersion":2,"mediaType":"application/vnd.oci.image.index.v1+json","manifests":[]}'
d1=sha256:$(printf '%s' "$m1" | sha256sum | cut -d' ' -f1) d2=sha256:$(printf '%s' "$m2" | sha256sum | cut -d' ' -f1)
d3=sha256:$(printf '%064d' 3)
printf '%s' "$m1" > "$GL/harbor/$d1"; printf '%s' "$m2" > "$GL/harbor/$d2"
cat > "$W/pods.json" <<J
{"items":[
 {"status":{"containerStatuses":[{"imageID":"harbor.vt.local/okr/task-service@$d1"},{"imageID":"docker-pullable://harbor.vt.local/platform/backup-tools@$d2"}]}},
 {"status":{"initContainerStatuses":[{"imageID":"harbor.vt.local/okr/task-service@$d1"}],"containerStatuses":[{"imageID":"harbor.vt.local/dockerhub/library/nginx@$d3"}]}}
]}
J
export GIA_LAP_PODS=$W/pods.json
cat > "$GL/curl-gia.sh" <<'X'
for a in "$@"; do u=$a; done
o=""; while [ $# -gt 0 ]; do [ "$1" = -o ] && o=$2; shift; done
echo "$u" >> "$GL/harbor.log"
dg=${u##*/manifests/}
[ -f "$GL/harbor/$dg" ] || exit 22
cp "$GL/harbor/$dg" "$o"
X
HB=(env REGISTRY_GOC=harbor.vt.local HARBOR_SN=https://harbor-sn.vt.local "HARBOR_USER=robot\$pull-cell-g4-sn" "HARBOR_PASSWORD_FILE=$W/mk")
moi
if "${HB[@]}" bash "$CFG/kiem-keo-harbor.sh" > "$W/hb.out" 2>&1; then ok "kéo thử harbor-sn đạt"; else loi "kéo thử harbor-sn"; cat "$W/hb.out"; fi
kiem "  hai digest riêng biệt của project được nhân bản, bỏ ảnh proxy-cache dockerhub" bash -c "[ \$(wc -l < '$GL/harbor.log') = 2 ] && ! grep -q dockerhub '$GL/harbor.log' && grep -qx 'https://harbor-sn.vt.local/v2/okr/task-service/manifests/$d1' '$GL/harbor.log'"
kiem "  chỉ số đạt" grep -q '^backup_harbor_pull_check_checked 2$' "$GL/chi-so-job_kiem-keo-harbor_cell_g4"
rm "$GL/harbor/$d2"; printf 'sai' > "$GL/harbor/$d1"
moi
if "${HB[@]}" bash "$CFG/kiem-keo-harbor.sh" > "$W/hb.out" 2>&1; then loi "ảnh thiếu, sai digest: không báo lỗi"
else kiem "ảnh thiếu ở harbor-sn và nội dung sai digest: lỗi, failed 2" grep -q '^backup_harbor_pull_check_failed 2$' "$GL/chi-so-job_kiem-keo-harbor_cell_g4"; fi

for db in bkt_td bkt_ledger; do psql -X -q -d postgres -c "DROP DATABASE IF EXISTS $db"; done
echo; echo "Tổng: $dat đạt, $hong hỏng"
[ "$hong" -eq 0 ]
