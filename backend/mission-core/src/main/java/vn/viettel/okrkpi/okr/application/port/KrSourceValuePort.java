package vn.viettel.okrkpi.okr.application.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Số liệu nguồn của KR qua integration-gateway (SVC-13, CORE-01-10, QT-03 bước 3). Hiện thực gọi ra
 * ngoài, nên luôn được gọi ngoài giao dịch CSDL và tự chịu thời hạn 3 giây cùng ngắt mạch.
 */
public interface KrSourceValuePort {

  /**
   * @param dataSource nguồn số liệu khai trên KR ({@code okr.key_result.data_source})
   * @throws Unavailable khi nguồn lỗi, quá hạn, mạch đang mở hoặc trả dữ liệu không dùng được
   */
  SourceValue fetch(TenantId tenant, UUID keyResultId, String dataSource);

  /** Giá trị gợi ý và thời điểm lấy. */
  record SourceValue(BigDecimal value, Instant fetchedAt, String dataSource) {}

  /** Nguồn không trả được số liệu (MSG-03-03). */
  final class Unavailable extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public Unavailable(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
