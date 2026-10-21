#!/usr/bin/env bash
# =============================================================================
# NEN-020 · Baseline mục 6 (EVT-01..EVT-26) · TL-01 "Luồng sự kiện bất đồng bộ".
#
# Tạo 26 topic nghiệp vụ EVT-01..EVT-26, các topic kỹ thuật hệ thống và mẫu
# topic thử lại / hàng đợi lỗi (<topic>.retry.<consumer>, <topic>.dlq.<consumer>)
# trên Kafka 3.9 KRaft (mặc định 3 partition, replication-factor 1 ở dev).
# Các topic đối chiếu danh mục (org.*) bật cleanup.policy=compact,delete.
# =============================================================================
set -euo pipefail

CONTAINER="${KAFKA_CONTAINER:-mission-dev-kafka}"
BOOTSTRAP="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}"
PARTITIONS="${KAFKA_PARTITIONS:-3}"
CREATE_RETRY_DLQ="${CREATE_RETRY_DLQ:-true}"

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

# EVT-01..EVT-26 (00-co-so-thiet-ke-chung.md mục 6)
BUSINESS_TOPICS=(
  # Tổ chức — Cán bộ (MOD-01) — compact,delete
  "org.unit.changed.v1"
  "org.user-assignment.changed.v1"
  # OKR — Mục tiêu (MOD-03)
  "okr.objective.published.v1"
  "okr.kr.progress-updated.v1"
  "okr.alignment.changed.v1"
  "okr.alert.triggered.v1"
  # Giao việc — Nhiệm vụ (MOD-04)
  "task.assigned.v1"
  "task.status-changed.v1"
  "task.progress-updated.v1"
  "task.accepted.v1"
  "task.overdue.v1"
  # Sản phẩm — Nghiệm thu (MOD-05)
  "product.submitted.v1"
  "product.accepted.v1"
  "product.rejected.v1"
  # Minh chứng (SVC-09)
  "evidence.uploaded.v1"
  "evidence.verified.v1"
  # Chấm điểm — Xếp loại (SVC-08)
  "scoring.kpi-recalculated.v1"
  "scoring.snapshot.finalized.v1"
  "scoring.period.locked.v1"
  # Đánh giá — Xếp loại (MOD-06)
  "appraisal.round.opened.v1"
  "appraisal.submitted.v1"
  "appraisal.council-approved.v1"
  "appraisal.result.notified.v1"
  "appraisal.appeal.filed.v1"
  # Hồ sơ — Ký số (MOD-07 / SVC-11)
  "dossier.generated.v1"
  "dossier.signed.v1"
)

# Topic kỹ thuật hệ thống (NEN-020 mục 3, TL-01 chương 4 & 5)
TECH_TOPICS=(
  "sys.ledger.append.v1"
  "sys.audit.v1"
  "sys.evidence.object.stored.v1"
  "sys.tenant.provisioned.v1"
  "iam.auth.event.v1"
  "registry.tenant.changed.v1"
  "org.bootstrap.v1"
  "period.drain.marker"
  # Tương thích các luồng phụ trợ TL-01
  "sys.Registry.tenant-changed.v1"
  "sys.policy-pack.published.v1"
  "workflow.task.created.v1"
  "workflow.task.completed.v1"
  "evidence.object.scanned.v1"
  "dossier.form.requested.v1"
)

# Danh sách cặp <topic>:<consumer> tiêu biểu cần tạo sẵn .retry.<consumer> và .dlq.<consumer>
# Có thể truyền thêm qua biến môi trường EXTRA_CONSUMER_BINDINGS="topicA:svcB topicC:svcD"
DEFAULT_CONSUMER_BINDINGS=(
  "org.unit.changed.v1:mission-core"
  "org.user-assignment.changed.v1:mission-core"
  "org.bootstrap.v1:mission-core"
  "task.accepted.v1:scoring-service"
  "product.accepted.v1:scoring-service"
  "okr.kr.progress-updated.v1:scoring-service"
  "evidence.verified.v1:scoring-service"
  "scoring.kpi-recalculated.v1:mission-core"
  "scoring.snapshot.finalized.v1:mission-core"
  "sys.ledger.append.v1:audit-ledger-service"
  "sys.evidence.object.stored.v1:evidence-service"
  "registry.tenant.changed.v1:mission-core"
)

create_topic() {
  local t="$1"
  local partitions="${2:-${PARTITIONS}}"
  local extra_args=()
  if [[ "${t}" == org.* ]]; then
    extra_args+=(--config "cleanup.policy=compact,delete")
  fi
  echo "  + ${t}"
  kafka_topics_cmd --create --if-not-exists \
    --topic "${t}" \
    --partitions "${partitions}" \
    --replication-factor 1 \
    "${extra_args[@]}" >/dev/null
}

create_consumer_retry_dlq() {
  local base_topic="$1"
  local consumer="$2"
  create_topic "${base_topic}.retry.${consumer}" 1
  create_topic "${base_topic}.dlq.${consumer}" 1
}

echo "==> [NEN-020] Tạo ${#BUSINESS_TOPICS[@]} topic nghiệp vụ (EVT-01..EVT-26) và ${#TECH_TOPICS[@]} topic kỹ thuật..."
for t in "${BUSINESS_TOPICS[@]}" "${TECH_TOPICS[@]}"; do
  create_topic "${t}" "${PARTITIONS}"
done

if [[ "${CREATE_RETRY_DLQ}" == "true" ]]; then
  echo "==> [NEN-020] Tạo topic .retry.<consumer> và .dlq.<consumer> cho các consumer tiêu chuẩn..."
  for binding in "${DEFAULT_CONSUMER_BINDINGS[@]}"; do
    base_topic="${binding%%:*}"
    consumer="${binding##*:}"
    create_consumer_retry_dlq "${base_topic}" "${consumer}"
  done

  if [[ -n "${EXTRA_CONSUMER_BINDINGS:-}" ]]; then
    for binding in ${EXTRA_CONSUMER_BINDINGS}; do
      base_topic="${binding%%:*}"
      consumer="${binding##*:}"
      create_consumer_retry_dlq "${base_topic}" "${consumer}"
    done
  fi
fi

echo "==> Hoàn tất. Tổng số topic hiện có:"
kafka_topics_cmd --list | wc -l
