package vn.viettel.okrkpi.appraisal.application.transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.application.transfer.port.RatingHistory;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Kết quả xếp loại các kỳ của cán bộ được điều động liên cell (DV-13-10): chỉ mức đã chốt (hồ sơ
 * {@code DA_KY_SO} hoặc {@code DA_KHOA}) và xếp loại năm đã có mức. Kỳ được mô tả bằng mã, tên và
 * khoảng ngày vì mã kỳ của tenant nguồn không có nghĩa ở tenant đích.
 */
public class RatingTransferService {

  /** Mô tả kỳ mang theo. */
  public record Period(String code, String name, LocalDate startDate, LocalDate endDate) {}

  /** Mức của một kỳ. */
  public record PeriodResult(Period period, String flow, String rating, Instant decidedAt) {}

  /** Xếp loại năm. */
  public record AnnualResult(
      Period period,
      String flow,
      List<String> quarterRatings,
      BigDecimal annualScore,
      String rating) {}

  /** Phần "kết quả xếp loại các kỳ" của gói. */
  public record Ratings(
      int version, UUID personId, List<PeriodResult> periods, List<AnnualResult> annual) {}

  private final RatingHistory history;
  private final PeriodApi periods;

  public RatingTransferService(RatingHistory history, PeriodApi periods) {
    this.history = Objects.requireNonNull(history, "history");
    this.periods = periods;
  }

  /** Rỗng khi cán bộ chưa có kết quả đã chốt nào. */
  public Optional<Ratings> ratings(TenantId tenant, UUID personId) {
    Objects.requireNonNull(personId, "personId");
    List<PeriodResult> out = new ArrayList<>();
    for (RatingHistory.PeriodRating r : history.periodRatings(tenant, personId)) {
      out.add(new PeriodResult(period(tenant, r.periodId()), r.flow(), r.rating(), r.decidedAt()));
    }
    List<AnnualResult> annual = new ArrayList<>();
    for (RatingHistory.AnnualRating a : history.annualRatings(tenant, personId)) {
      annual.add(
          new AnnualResult(
              period(tenant, a.periodId()),
              a.flow(),
              a.quarterRatings(),
              a.annualScore(),
              a.rating()));
    }
    if (out.isEmpty() && annual.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new Ratings(1, personId, List.copyOf(out), List.copyOf(annual)));
  }

  private Period period(TenantId tenant, UUID periodId) {
    Optional<PeriodSnapshot> p =
        periods == null ? Optional.empty() : periods.findById(tenant, new PeriodId(periodId));
    return p.map(s -> new Period(s.code(), s.name(), s.startDate(), s.endDate()))
        .orElse(new Period(periodId.toString(), "", null, null));
  }
}
