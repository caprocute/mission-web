package vn.viettel.okrkpi.oversight.application.port;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.oversight.application.port.ReopenRequestRepository.Adjustment;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenScope;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Tính thử ảnh hưởng của yêu cầu mở lại bằng scoring-service (SVC-07) với đúng phiên bản Policy
 * Pack đã dùng khi khoá kỳ (BR-13-06). Thao tác chỉ đọc: không ghi kết quả, ảnh chụp hay sự kiện.
 * Hiện thực gọi API nội bộ do DV-07-08 cung cấp; khi cell chưa có, lớp REST trả 503.
 */
public interface ReopenImpactSimulator {

  Impact simulate(TenantId tenant, Request request);

  /**
   * Phạm vi cần tính: hồ sơ ({@code HO_SO}), đơn vị ({@code DON_VI}) hoặc cả kỳ. {@code
   * authorization} là header của người gọi để dịch vụ tính điểm kiểm quyền theo người dùng, có thể
   * {@code null}; không in ra {@link #toString()}.
   */
  record Request(
      UUID periodId,
      ReopenScope scope,
      List<UUID> scopeIds,
      List<UUID> profileIds,
      List<Adjustment> adjustments,
      String authorization) {

    public Request {
      scopeIds = List.copyOf(scopeIds);
      profileIds = List.copyOf(profileIds);
      adjustments = List.copyOf(adjustments);
    }

    public Request(
        UUID periodId,
        ReopenScope scope,
        List<UUID> scopeIds,
        List<UUID> profileIds,
        List<Adjustment> adjustments) {
      this(periodId, scope, scopeIds, profileIds, adjustments, null);
    }

    @Override
    public String toString() {
      return "Request[periodId=%s, scope=%s, profiles=%d, adjustments=%d]"
          .formatted(periodId, scope, profileIds.size(), adjustments.size());
    }
  }

  record Impact(String policyVersion, List<Row> rows, Integer formsToRegenerate) {
    public Impact {
      rows = List.copyOf(rows);
    }
  }

  record Row(UUID profileId, String metric, String before, String after) {}
}
