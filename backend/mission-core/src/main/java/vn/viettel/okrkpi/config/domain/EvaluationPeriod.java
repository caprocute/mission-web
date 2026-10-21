package vn.viettel.okrkpi.config.domain;

import java.time.LocalDate;
import java.util.Objects;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Thực thể miền thuần Java biểu diễn kỳ đánh giá trong {@code MOD-08 (config)} (TL-01 mục 2.1).
 *
 * <p>Tuân thủ quy tắc kiến trúc lục giác: tầng {@code domain} chỉ phụ thuộc {@code java..}, {@code
 * ..domain..} và {@code vn.viettel.okrkpi.shared.kernel..}.
 */
public final class EvaluationPeriod {

  private final PeriodId id;
  private final TenantId tenantId;
  private final String code;
  private final String name;
  private final LocalDate startDate;
  private final LocalDate endDate;
  private final boolean locked;

  public EvaluationPeriod(
      PeriodId id,
      TenantId tenantId,
      String code,
      String name,
      LocalDate startDate,
      LocalDate endDate,
      boolean locked) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
    this.code = Objects.requireNonNull(code, "code must not be null");
    this.name = Objects.requireNonNull(name, "name must not be null");
    this.startDate = Objects.requireNonNull(startDate, "startDate must not be null");
    this.endDate = Objects.requireNonNull(endDate, "endDate must not be null");
    if (endDate.isBefore(startDate)) {
      throw new IllegalArgumentException("endDate must not be before startDate");
    }
    this.locked = locked;
  }

  public PeriodId id() {
    return id;
  }

  public TenantId tenantId() {
    return tenantId;
  }

  public String code() {
    return code;
  }

  public String name() {
    return name;
  }

  public LocalDate startDate() {
    return startDate;
  }

  public LocalDate endDate() {
    return endDate;
  }

  public boolean isLocked() {
    return locked;
  }
}
