package vn.viettel.okrkpi.appraisal.application.transfer.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Kết quả xếp loại đã chốt của một cán bộ qua các kỳ ({@code appraisal_profile}, {@code
 * annual_rating}).
 */
public interface RatingHistory {

  /** Mức đã quyết định của một kỳ (hồ sơ đã ký số hoặc đã khoá). */
  record PeriodRating(UUID periodId, String flow, String rating, Instant decidedAt) {}

  /** Xếp loại năm. */
  record AnnualRating(
      UUID periodId,
      String flow,
      List<String> quarterRatings,
      BigDecimal annualScore,
      String rating) {}

  List<PeriodRating> periodRatings(TenantId tenant, UUID personId);

  List<AnnualRating> annualRatings(TenantId tenant, UUID personId);
}
