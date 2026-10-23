package vn.viettel.okrkpi.replica.infrastructure.reconcile;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.viettel.okrkpi.shared.inbox.InboxListener;
import vn.viettel.okrkpi.shared.inbox.PermanentInboxException;
import vn.vtokr.org.v1.OrgBootstrap;

/**
 * Consumer thứ hai của {@code org.bootstrap.v1} (nhóm riêng {@code org-replica.reconcile}, inbox
 * {@code org_replica}): chỉ nhận thông điệp {@code KET_THUC} có băm bucket và ghi lần đối soát R1
 * chờ so; thông điệp thực thể do {@code OrgReplicaListeners} áp vào bản sao.
 */
public class OrgReplicaReconcileListener {

  static final String CONSUMER = "org-replica.reconcile";

  private final OrgReplicaReconciler reconciler;

  public OrgReplicaReconcileListener(OrgReplicaReconciler reconciler) {
    this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
  }

  @InboxListener(topic = EventTopics.ORG_BOOTSTRAP_V1, consumer = CONSUMER)
  public void onBootstrap(InboxEnvelope<OrgBootstrap> e) {
    OrgBootstrap m = e.payload();
    if (!OrgReplicaReconciler.END_MARKER.equals(m.getMarker())) {
      return;
    }
    if (m.getBucketHashes() == null || m.getBucketHashes().isEmpty()) {
      throw new PermanentInboxException("KET_THUC của org.bootstrap.v1 thiếu bucketHashes");
    }
    Map<String, Long> counts = new LinkedHashMap<>();
    if (m.getEntityCounts() != null) {
      m.getEntityCounts().forEach((k, v) -> counts.put(k.toString(), v));
    }
    Map<String, String> hashes = new LinkedHashMap<>();
    m.getBucketHashes().forEach((k, v) -> hashes.put(k.toString(), v.toString()));
    try {
      reconciler.expect(
          e.tenantId().value(),
          m.getBatchId(),
          m.getSeq() - 1,
          counts,
          hashes,
          e.occurredAt() == null ? Instant.now() : e.occurredAt());
    } catch (IllegalArgumentException bad) {
      throw new PermanentInboxException("KET_THUC sai hợp đồng: " + bad.getMessage());
    }
  }
}
