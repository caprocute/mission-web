package vn.viettel.okrkpi.workflow.application.port;

import java.util.UUID;
import vn.viettel.okrkpi.workflow.domain.replica.OrgTree;
import vn.viettel.okrkpi.workflow.domain.replica.OrgUnitReplica;

/**
 * Cổng ghi bản sao đơn vị {@code org_replica.org_unit_v} của workflow-service (TL-03 schema
 * org_replica). Chạy trong giao dịch của consumer inbox, đã đặt ngữ cảnh tenant.
 */
public interface OrgUnitReplicaStore {

  /**
   * Upsert theo khoá {@code (tenant_id, id, valid_from)}, chỉ ghi đè khi {@code source_version} mới
   * lớn hơn bản đang có (ECST, wiki trang 18).
   *
   * @return {@code true} khi đã chèn hoặc ghi đè, {@code false} khi bản đang có mới hơn hoặc bằng
   */
  boolean upsert(UUID tenantId, OrgUnitReplica unit, UUID eventId);

  /**
   * Đi từ {@code from} lên theo cấp trên của các phiên bản mở trên cây {@code tree} có gặp {@code
   * target} không ({@code from} bằng {@code target} cũng tính là gặp).
   */
  boolean reachesUpward(UUID tenantId, OrgTree tree, UUID from, UUID target);
}
