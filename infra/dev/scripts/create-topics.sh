#!/bin/bash
# Tạo topic sự kiện EVT-01…EVT-26 (baseline mục 7) cho Kafka dev; idempotent.
set -euo pipefail
BS=kafka:9092
TOPICS="okr.objective.approved.v1 okr.keyresult.checkedin.v1 task.plan.approved.v1 task.task.assigned.v1
task.task.progressed.v1 task.task.statuschanged.v1 product.evidence.submitted.v1 product.evidence.accepted.v1
config.period.locked.v1 scoring.score.computed.v1 appraisal.profile.stepcompleted.v1 appraisal.rating.decided.v1
policy.pack.published.v1 org.unit.changed.v1 org.assignment.changed.v1 org.person.changed.v1
dossier.form.generated.v1 esign.signature.completed.v1 ledger.seal.created.v1 analytics.anomaly.detected.v1
notify.message.requested.v1 oversight.period.reopened.v1 config.uipack.published.v1 integ.document.received.v1
oversight.complaint.filed.v1 config.period.unlocked.v1"
for t in $TOPICS; do
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server $BS --create --if-not-exists --topic "$t" --partitions 6 --replication-factor 1 >/dev/null
done
/opt/kafka/bin/kafka-topics.sh --bootstrap-server $BS --list | grep -c '\.v1$' | xargs echo "Số topic sự kiện:"
