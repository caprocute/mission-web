package vn.viettel.okrkpi.workflow.application.port;

import java.util.UUID;
import vn.viettel.okrkpi.workflow.domain.replica.PersonReplica;

/**
 * Cổng ghi bản sao nhân sự {@code org_replica.person_v} của workflow-service (TL-03 schema
 * org_replica). Chạy trong giao dịch của consumer inbox, đã đặt ngữ cảnh tenant.
 */
public interface PersonReplicaStore {

  /**
   * Upsert theo khoá {@code (tenant_id, id)}, chỉ ghi đè khi {@code source_version} mới lớn hơn.
   *
   * @return {@code true} khi đã chèn hoặc ghi đè, {@code false} khi bản đang có mới hơn hoặc bằng
   */
  boolean upsert(UUID tenantId, PersonReplica person, UUID eventId);
}
