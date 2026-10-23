package vn.viettel.okrkpi.task.application.tree.port;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Đọc nút và quan hệ cha con trên {@code task.task} ({@code parent_task_id}, {@code node_kind}).
 */
public interface TaskTreeRows {

  /** Một nút của cây. */
  record NodeRow(
      UUID id,
      UUID periodId,
      String kind,
      UUID parentId,
      String status,
      BigDecimal standardScore,
      BigDecimal componentScore) {

    /** Điểm của nút làm việc cha: việc con dùng điểm thành phần, nút gốc dùng điểm chuẩn. */
    public BigDecimal score() {
      return parentId != null && componentScore != null ? componentScore : standardScore;
    }
  }

  Optional<NodeRow> node(TenantId tenant, UUID id);

  /** Đường dẫn từ gốc tới {@code id} (gồm cả {@code id}); rỗng khi không có nút. */
  List<NodeRow> path(TenantId tenant, UUID id);

  /** Việc con trực tiếp của {@code parentId}, mọi trạng thái. */
  List<NodeRow> children(TenantId tenant, UUID parentId);
}
