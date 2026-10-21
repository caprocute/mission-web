#!/usr/bin/env bash
# Kiểm thử cấu hình Alertmanager (INF-15): amtool check-config, bảng định tuyến theo nhãn, và ca ức chế chạy
# trên một Alertmanager thật nghe ở localhost (OutboxBacklog không gửi khi CNPGClusterNotHealthy đang kích hoạt).
# Cần amtool, alertmanager (bản 0.28) và python3 trong PATH. Dùng: alertmanager/tests/chay-kiem-thu.sh
set -euo pipefail
cd "$(dirname "$0")/.."
dat=0; truot=0
ok()  { echo "  [ĐẠT] $*"; dat=$((dat+1)); }
hong(){ echo "  [HỎNG] $*"; truot=$((truot+1)); }
TMP=$(mktemp -d); AMPID=""
trap '[ -n "$AMPID" ] && kill "$AMPID" 2>/dev/null; rm -rf "$TMP"' EXIT

echo "== amtool check-config"
amtool check-config alertmanager.yaml >/dev/null && ok "cấu hình hợp lệ" || hong "cấu hình lỗi"

echo "== Định tuyến"
tuyen() { # $1 người nhận mong đợi, còn lại là nhãn
  local mong=$1; shift
  local thuc; thuc=$(amtool config routes test --config.file=alertmanager.yaml "$@" 2>&1 | tail -1)
  [ "$thuc" = "$mong" ] && ok "$* → $thuc" || hong "$* → $thuc (mong đợi $mong)"
}
tuyen goi-truc alertname=SLOAvailabilityFastBurn severity=critical cell=g4
tuyen nhan-tin-sre alertname=SLOAvailabilitySlowBurn severity=warning doi= cell=g4
tuyen nhan-tin-sre alertname=FluentBitDropping severity=warning doi=sre cell=g4
tuyen goi-truc,dba,nhan-tin-sre alertname=OutboxBacklog severity=critical doi=sre-dba cell=g4
tuyen dba alertname=PgBouncerWaitingClients severity=warning doi=dba cell=g4
tuyen goi-truc,soc alertname=LedgerChainBroken severity=critical doi=soc cell=g4
tuyen soc alertname=KyvernoImageRejected severity=warning doi=soc cell=g4
tuyen goi-truc,quan-tri-khoa alertname=OpenBaoSealed severity=critical doi=quan-tri-khoa cell=g4
tuyen goi-truc,chi-huy-su-co alertname=SaturationLevel2 severity=critical doi=ic cell=g4
tuyen chu-service alertname=DLQNotEmpty severity=warning doi=chu-service cell=g4
tuyen nen-tang alertname=NG03CpuPgCore severity=capacity doi=nen-tang ng=NG-03 cell=g4
tuyen chu-san-pham alertname=ErrorBudgetHalfSpent severity=info doi=chu-san-pham cell=g4

echo "== Ức chế trên Alertmanager chạy thật"
mkdir -p "$TMP/webhooks" "$TMP/data"
for r in goi-truc nhan-tin-sre dba soc quan-tri-khoa chi-huy-su-co chu-service nen-tang chu-san-pham; do
  echo "http://127.0.0.1:1/$r" > "$TMP/webhooks/$r"
done
sed "s#/etc/alertmanager/webhooks#$TMP/webhooks#" alertmanager.yaml > "$TMP/am.yaml"
alertmanager --config.file="$TMP/am.yaml" --storage.path="$TMP/data" --web.listen-address=127.0.0.1:19093 \
  --cluster.listen-address= > "$TMP/am.log" 2>&1 &
AMPID=$!
for _ in $(seq 30); do curl -sf http://127.0.0.1:19093/-/ready >/dev/null && break; sleep 0.5; done
gui() { # $1 tên cảnh báo, còn lại là nhãn dạng k=v
  local ten=$1; shift
  amtool alert add --alertmanager.url=http://127.0.0.1:19093 "alertname=$ten" "$@" >/dev/null
}
trang_thai() { # in "trạng thái;bị ức chế bởi" của cảnh báo có alertname $1 và cell $2
  curl -s "http://127.0.0.1:19093/api/v2/alerts?filter=alertname%3D%22$1%22&filter=cell%3D%22$2%22" |
    python3 -c 'import json,sys; a=json.load(sys.stdin); print(";".join([a[0]["status"]["state"], ",".join(a[0]["status"]["inhibitedBy"])]) if a else "khong-co")'
}
gui CNPGClusterNotHealthy severity=critical doi=dba cell=g4 cluster=pg-core
gui OutboxBacklog severity=critical doi=sre-dba cell=g4 service=task
gui OutboxBacklog severity=critical doi=sre-dba cell=g5 service=task
gui SLOAvailabilityFastBurn severity=critical slo=availability cell=g4
gui SLOAvailabilitySlowBurn severity=warning slo=availability cell=g4
gui CNPGReplicaClusterLag severity=critical doi=dba cell=g4 cluster=pg-core
gui CNPGReplicaClusterLag severity=warning doi=dba cell=g4 cluster=pg-core
sleep 2
s=$(trang_thai OutboxBacklog g4); [[ "$s" == suppressed\;* ]] && ok "OutboxBacklog cell g4 bị ức chế khi CNPGClusterNotHealthy ($s)" || hong "OutboxBacklog g4: $s"
s=$(trang_thai OutboxBacklog g5); [[ "$s" == active\;* ]] && ok "OutboxBacklog cell g5 (CNPG bình thường) vẫn gửi ($s)" || hong "OutboxBacklog g5: $s"
s=$(trang_thai SLOAvailabilitySlowBurn g4); [[ "$s" == suppressed\;* ]] && ok "SLOAvailabilitySlowBurn bị ức chế khi đã có FastBurn" || hong "SlowBurn: $s"
n=$(curl -s "http://127.0.0.1:19093/api/v2/alerts?filter=alertname%3D%22CNPGReplicaClusterLag%22&silenced=false&inhibited=false" | python3 -c 'import json,sys; print(",".join(a["labels"]["severity"] for a in json.load(sys.stdin)))')
[ "$n" = critical ] && ok "CNPGReplicaClusterLag chỉ gửi mức critical khi hai mức cùng kích hoạt" || hong "CNPGReplicaClusterLag gửi: $n"

echo
echo "Tổng: $dat đạt, $truot hỏng"
[ "$truot" -eq 0 ]
