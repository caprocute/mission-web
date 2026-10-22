package vn.viettel.okrkpi.product.api;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng đọc minh chứng ({@code product :: api}) của {@code MOD-03} mở cho {@code MOD-04
 * (appraisal)}: điều kiện "có minh chứng đã được xác nhận" của đề xuất Hoàn thành xuất sắc nhiệm vụ
 * (CORE-04-04).
 */
public interface EvidenceQueryApi {

  /** Số mã tối đa một lần hỏi. */
  int MAX_IDS = 200;

  /**
   * Tập con của {@code evidenceIds} có phiên bản đã được xác nhận nghiệm thu ({@code DA_XAC_NHAN},
   * chưa bị loại khỏi kỳ, BR-07-05) do chính {@code personId} nộp; mã lạ hoặc của tenant khác bị
   * bỏ.
   *
   * @throws IllegalArgumentException khi hỏi quá {@link #MAX_IDS} mã
   */
  Set<UUID> confirmedEvidence(TenantId tenant, UUID personId, Collection<UUID> evidenceIds);
}
