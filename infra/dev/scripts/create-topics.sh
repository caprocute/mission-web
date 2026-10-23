#!/usr/bin/env bash
# =============================================================================
# NEN-020 · Baseline mục 6 (EVT-01..EVT-26) · TL-01 "Luồng sự kiện bất đồng bộ".
#
# Tạo đủ topic cho Kafka dev theo danh mục của production: các topic EVT-01..EVT-26,
# topic kỹ thuật và cặp <topic>.retry.<consumer>, <topic>.dlq.<consumer> của từng bên
# tiêu thụ. Danh sách đọc từ kafka/topics.txt, sinh bằng scripts/sinh-danh-sach-topic.py
# từ deploy/charts/vt-platform/ha-tang/kafka/files/danh-muc.yaml (cùng nguồn với
# KafkaTopic của Strimzi), nên dev và cụm thật không lệch tên topic.
# Dev: mọi topic KAFKA_PARTITIONS phân vùng (mặc định 3), replication-factor 1.
# Chạy lại được nhiều lần (--if-not-exists). Compose chạy tự động qua dịch vụ kafka-init.
# =============================================================================
set -euo pipefail

CONTAINER="${KAFKA_CONTAINER:-mission-dev-kafka}"
BOOTSTRAP="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}"
PARTITIONS="${KAFKA_PARTITIONS:-3}"
TOPICS_FILE="${TOPICS_FILE:-$(cd "$(dirname "$0")/.." && pwd)/kafka/topics.txt}"

# Chạy lệnh kafka-topics.sh trong container hoặc trực tiếp qua cổng hầm SSH
kafka_topics_cmd() {
  if [[ -n "${KAFKA_LOCAL_EXEC:-}" ]] || [[ -f "/.dockerenv" ]]; then
    /opt/kafka/bin/kafka-topics.sh --bootstrap-server "${BOOTSTRAP}" "$@"
  elif command -v docker >/dev/null 2>&1 && docker inspect -f '{{.State.Running}}' "${CONTAINER}" 2>/dev/null | grep -q true; then
    docker exec "${CONTAINER}" /opt/kafka/bin/kafka-topics.sh --bootstrap-server "${BOOTSTRAP}" "$@"
  elif command -v kafka-topics.sh >/dev/null 2>&1; then
    kafka-topics.sh --bootstrap-server "${KAFKA_EXTERNAL_BOOTSTRAP:-127.0.0.1:${KAFKA_EXTERNAL_PORT:-35094}}" "$@"
  else
    echo "LỖI: Không tìm thấy container ${CONTAINER} đang chạy hoặc kafka-topics.sh trên máy trạm." >&2
    exit 1
  fi
}

[[ -r "${TOPICS_FILE}" ]] || { echo "LỖI: không đọc được ${TOPICS_FILE}" >&2; exit 1; }

# Lấy danh sách hiện có một lần, chỉ gọi --create cho topic còn thiếu (mỗi lần gọi khởi động một JVM).
existing="$(kafka_topics_cmd --list)"
tao=0; co_san=0
while read -r topic policy; do
  [[ -z "${topic}" || "${topic}" == \#* ]] && continue
  if grep -qxF "${topic}" <<<"${existing}"; then
    co_san=$((co_san + 1)); continue
  fi
  extra_args=()
  [[ "${policy}" != "-" ]] && extra_args+=(--config "cleanup.policy=${policy}")
  echo "  + ${topic}"
  kafka_topics_cmd --create --if-not-exists --topic "${topic}" --partitions "${PARTITIONS}" \
    --replication-factor 1 "${extra_args[@]}" >/dev/null
  tao=$((tao + 1))
done < "${TOPICS_FILE}"

echo "==> Hoàn tất: tạo mới ${tao}, đã có ${co_san}. Tổng số topic hiện có: $(kafka_topics_cmd --list | wc -l)"
