package vn.viettel.okrkpi.scoring.application.port;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.scoring.domain.CoreScoringInput;

/**
 * Đọc đầu vào tính điểm của kỳ theo trang con trỏ qua API nội bộ của lõi ({@code
 * listAppraisalScoringInputs}, kèm {@code listAppraisalWorkItems} khi hồ sơ có hơn 200 việc lá);
 * không đọc CSDL lõi (TL-01 "SVC-07 scoring-service").
 */
public interface ScoringInputs {

  /**
   * Cỡ trang: TL-01 nêu lô 500 hồ sơ, nhưng {@code limit} của hợp đồng tối đa 200 (baseline mục
   * 13).
   */
  int PAGE_SIZE = 200;

  /** Một trang đầu vào; {@code nextCursor} trống khi đã hết. */
  record Page(List<CoreScoringInput> items, String nextCursor) {
    public Page {
      items = List.copyOf(items);
    }
  }

  /**
   * @param cursor con trỏ trang, trống cho trang đầu
   * @throws RuntimeException lỗi gọi lõi; bên gọi thử lại theo lô
   */
  Page page(UUID tenantId, UUID periodId, String cursor, int limit);
}
