#!/usr/bin/env bash
# Dựng ảnh Kafka Connect + Debezium PostgreSQL (INF-10). Tải gói plugin từ Maven Central (hoặc kho Nexus nội bộ qua
# DEBEZIUM_URL), kiểm SHA-512 ghim dưới đây rồi dựng bằng docker hoặc podman. CI (INF-05) ký và đẩy ảnh lên Harbor,
# rồi thay digest trong values.yaml (connect.image.digest).
# Dùng: dung-anh.sh <tên ảnh:thẻ> [KAFKA_IMAGE]
set -euo pipefail
DBZ=3.7.0.Final
SHA512=dc5334e087c7405f5b423e77373c60a89a3d7538ad38016f3959c431ad7078361ab95300a7e926c50621e7aa8375b7f4f3c5ecec4fe04ac484804d325009b6c0
URL=${DEBEZIUM_URL:-https://repo1.maven.org/maven2/io/debezium/debezium-connector-postgres/$DBZ/debezium-connector-postgres-$DBZ-plugin.tar.gz}
ANH=${1:?tên ảnh}
DIR=$(cd "$(dirname "$0")" && pwd)
W=$(mktemp -d)
trap 'rm -rf "${W:?}"' EXIT
if [ -n "${DEBEZIUM_TGZ:-}" ]; then cp "$DEBEZIUM_TGZ" "$W/dbz.tgz"; else curl -fsSL --retry 4 -o "$W/dbz.tgz" "$URL"; fi
echo "$SHA512  $W/dbz.tgz" | sha512sum -c -
tar -xzf "$W/dbz.tgz" -C "$W"
cp "$DIR/Containerfile" "$W/"
CMD=$(command -v docker || command -v podman)
"$CMD" build -f "$W/Containerfile" ${2:+--build-arg KAFKA_IMAGE="$2"} -t "$ANH" "$W"
