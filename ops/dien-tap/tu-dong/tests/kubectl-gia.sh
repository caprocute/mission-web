#!/usr/bin/env bash
# kubectl giả cho kiểm thử dr-chuyen-vung.sh: ghi mọi lời gọi vào $GIA/goi.log, giữ trạng thái "đang là bản sao" của
# từng cụm ở $GIA/<context>/<cụm>. Context có tệp $GIA/<context>/mat thì coi như không tới được.
set -euo pipefail
echo "$*" >> "$GIA/goi.log"
ctx=$2; shift 2
[ -f "$GIA/$ctx/mat" ] && exit 1
case "$*" in
  "get --raw /readyz") exit 0 ;;
  *"get cluster"*currentPrimary*) c=$(sed -E 's/.*get cluster ([^ ]+).*/\1/' <<<"$*"); echo "$c-1" ;;
  *"pg_is_in_recovery"*) p=$(sed -E 's/.*exec ([^ ]+)-1 .*/\1/' <<<"$*"); cat "$GIA/$ctx/$p" ;;
  *"pg_stat_replication"*) echo 0 ;;
  *"patch cluster"*) c=$(sed -E 's/.*patch cluster ([^ ]+).*/\1/' <<<"$*"); dich=$(sed -E 's/.*"primary":"([^"]+)".*/\1/' <<<"$*")
                    [ "$c" = "$dich" ] && echo f > "$GIA/$ctx/$c" || echo t > "$GIA/$ctx/$c" ;;
  *) exit 0 ;;
esac
