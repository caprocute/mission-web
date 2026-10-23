package vn.viettel.okrkpi.integ.application.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Cổng bảng {@code integ.ktxh_value}: chỉ tiêu KT-XH nhận qua LGSP (DV-13-06, BR-16-05). */
public interface KtxhValues {

  /** Một giá trị theo kỳ và địa bàn; {@code publishedAt} rỗng là chưa công bố. */
  record Value(
      String indicatorCode,
      String periodCode,
      String areaCode,
      BigDecimal value,
      String unit,
      Instant publishedAt,
      Instant sourceUpdatedAt) {}

  /**
   * Ghi đè giá trị cùng kỳ và địa bàn, trừ khi bản đang lưu mới hơn theo {@code sourceUpdatedAt};
   * trả true khi đã ghi.
   */
  boolean upsert(TenantId tenant, UUID id, UUID connectorId, Value value, Instant at);

  /** Giá trị đã công bố mới nhất của chỉ tiêu (kỳ, rồi thời điểm công bố gần nhất). */
  Optional<Value> latestPublished(TenantId tenant, UUID connectorId, String indicatorCode);
}
