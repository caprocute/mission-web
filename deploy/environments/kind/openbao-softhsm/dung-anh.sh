#!/usr/bin/env bash
# Dựng ảnh vt-kind/openbao-softhsm:2.7.1. Ảnh UBI không với tới kho RPM của Red Hat qua proxy, nên tải RPM của
# Rocky Linux 10 (cùng ABI RHEL 10) và kiểm sha256 trước khi cài. Dùng: openbao-softhsm/dung-anh.sh
set -euo pipefail
cd "$(dirname "$0")"
B=https://dl.rockylinux.org/pub/rocky/10
mkdir -p rpm
while read -r sum url; do
  f=rpm/$(basename "$url")
  [ -f "$f" ] || curl -sSfo "$f" "$B/$url"
  echo "$sum  $f" | sha256sum -c --quiet
done <<'DS'
7cce539650538cf0a071a46b664dbc9832eea6cd01e8305f82837cf7e83fbf51 AppStream/x86_64/os/Packages/s/softhsm-2.6.1-16.el10_0.x86_64.rpm
f163cb6c96c94be2a01e6cecec01aa6f9d0fdb9d2f9c24a2bdd7d28621e443ef BaseOS/x86_64/os/Packages/o/opensc-0.26.1-1.el10.x86_64.rpm
2d3a53d137722835021efcf58fc9ece95ab1753a96a3563354f976db218dd3a2 BaseOS/x86_64/os/Packages/o/opensc-libs-0.26.1-1.el10.x86_64.rpm
DS
docker build -q -t vt-kind/openbao-softhsm:2.7.1 . >/dev/null
rm -rf rpm
echo "đã dựng vt-kind/openbao-softhsm:2.7.1"
