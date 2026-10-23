#!/usr/bin/env bash
# Kiểm thử ba script của backup-cell (INF-17) với kubectl, aws, curl, etcdctl, etcdutl giả lập và PostgreSQL thật:
#   - restore-check.sh: chọn cụm theo luân phiên, dựng ObjectStore chỉ đọc và cụm tạm đúng mốc PITR, truy vấn kiểm
#     (kiem-tra.sql, kiem-so-cai.sql) chạy thật trên PostgreSQL; dữ liệu mồ côi khoá ngoại, mất dòng, chuỗi băm đứt,
#     Job khôi phục hỏng đều cho backup_restore_check_success 0 và mã thoát khác 0; cụm tạm luôn bị xoá.
#   - giu-dai-han.sh: chỉ chạy ngày cuối tháng; chép đúng bản nền và đoạn WAL, hạn giữ 12 tháng hoặc 10 năm cuối năm.
#   - chup-etcd.sh: mã hoá bằng age thật, giải mã lại khớp snapshot, tệp rõ bị xoá, recipient sai thì dừng.
# Cần: bash, jq, age, psql và một cụm PostgreSQL trống được phép tạo CSDL (PGHOST, PGPORT, PGUSER siêu người dùng).
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
  case $1 in s3://*) echo "$1 $2" >> "$GL/chep.log" ;; *) cp "$1" "$GL/kho/$(basename "$2")"; echo "$2" >> "$GL/day.log" ;; esac
elif [ "$lenh" = s3api ] && [ "$con" = put-object-retention ]; then
  while [ $# -gt 0 ]; do case $1 in --key) k=$2; shift ;; --retention) r=$2; shift ;; esac; shift; done
  echo "$k $r" >> "$GL/han.log"
else echo "aws giả: lệnh lạ" >&2; exit 2; fi
EOF
cat > "$W/bin/curl" <<'EOF'
#!/usr/bin/env bash
for a in "$@"; do u=$a; done
cat > "$GL/chi-so-$(echo "${u#*/metrics/}" | tr '/' '_')"
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

for db in bkt_td bkt_ledger; do psql -X -q -d postgres -c "DROP DATABASE IF EXISTS $db"; done
echo; echo "Tổng: $dat đạt, $hong hỏng"
[ "$hong" -eq 0 ]
