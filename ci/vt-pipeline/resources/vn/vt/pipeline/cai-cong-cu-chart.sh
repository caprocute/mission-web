#!/usr/bin/env bash
# Cài công cụ cho các bộ kiểm thử chart (INF-28, vtHelmPipeline): Kyverno CLI (kiem-ba-mo-hinh.sh, kyverno test) và
# promtool (backup-cell, standby-check: promtool check/test rules). Bỏ qua công cụ đã có đúng phiên bản trên PATH;
# tải bản phát hành chính thức, đối chiếu sha256 ghim trong tệp này, giải vào thư mục đích. Gương nội bộ (Harbor,
# Nexus) đặt qua VT_CONG_CU_GUONG thay cho github.com.
# Dùng: cai-cong-cu-chart.sh <thư mục bin>   (rồi thêm thư mục đó vào PATH)
set -euo pipefail
BIN=${1:?cần thư mục bin}
GUONG=${VT_CONG_CU_GUONG:-https://github.com}
mkdir -p "$BIN"
TAM=$(mktemp -d); trap 'rm -rf "${TAM:?}"' EXIT

KYVERNO=1.15.2
KYVERNO_SHA=c90520ba24fb8b8df003ec22d6d2621e4a3d3c7497665fdcf84e9eab4ff1dfe0
PROM=3.5.0
PROM_SHA=e811827af26d822afb09a4f28314f61b618b12cff5369835a67f674d8b46f39a

tai() { # <url> <sha256> <tệp>
  curl -sSLf --retry 3 -o "$3" "$1"
  echo "$2  $3" | sha256sum -c --quiet - || { echo "cai-cong-cu-chart: sai sha256 của $1" >&2; exit 1; }
}

if kyverno version 2>/dev/null | grep -q "Version: $KYVERNO"; then echo "kyverno $KYVERNO đã có"
else
  tai "$GUONG/kyverno/kyverno/releases/download/v$KYVERNO/kyverno-cli_v${KYVERNO}_linux_x86_64.tar.gz" "$KYVERNO_SHA" "$TAM/k.tgz"
  tar -xzf "$TAM/k.tgz" -C "$TAM" kyverno && install -m 0755 "$TAM/kyverno" "$BIN/kyverno"
  echo "đã cài kyverno $KYVERNO vào $BIN"
fi

if promtool --version 2>/dev/null | grep -q "version $PROM "; then echo "promtool $PROM đã có"
else
  tai "$GUONG/prometheus/prometheus/releases/download/v$PROM/prometheus-$PROM.linux-amd64.tar.gz" "$PROM_SHA" "$TAM/p.tgz"
  tar -xzf "$TAM/p.tgz" -C "$TAM" "prometheus-$PROM.linux-amd64/promtool"
  install -m 0755 "$TAM/prometheus-$PROM.linux-amd64/promtool" "$BIN/promtool"
  echo "đã cài promtool $PROM vào $BIN"
fi
