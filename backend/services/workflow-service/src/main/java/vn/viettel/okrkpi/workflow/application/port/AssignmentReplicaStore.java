package vn.viettel.okrkpi.workflow.application.port;

import java.util.UUID;
import vn.viettel.okrkpi.workflow.domain.replica.AssignmentReplica;

/**
 * Cổng ghi bản sao phân công {@code org_replica.assignment_v} của workflow-service (TL-03 schema
 * org_replica). Chạy trong giao dịch của consumer inbox, đã đặt ngữ cảnh tenant.
 */
public interface AssignmentReplicaStore {

  /**
   * Upsert theo khoá {@code (tenant_id, id, valid_from)}, chỉ ghi đè khi {@code source_version} mới
   * lớn hơn bản đang có (ECST, wiki trang 18).
   *
   * @return {@code true} khi đã chèn hoặc ghi đè, {@code false} khi bản đang có mới hơn hoặc bằng
   */
  boolean upsert(UUID tenantId, AssignmentReplica assignment, UUID eventId);
}
