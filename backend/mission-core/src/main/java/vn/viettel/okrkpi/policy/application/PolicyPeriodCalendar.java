package vn.viettel.okrkpi.policy.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import vn.viettel.okrkpi.config.api.PeriodCalendarPort;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Hiện thực {@link PeriodCalendarPort} của MOD-08 bằng tham số {@code plan_approval_deadline_rule}
 * của gói đang hiệu lực cho kỳ sắp tạo (CORE-05-03, TL-02a "Lịch nghiệp vụ của một quý"). Quy tắc
 * duy nhất các gói khai hiện nay là {@link #DEFAULT_RULE}, trùng lịch mặc định MOD-08 tự tính, nên
 * cổng trả rỗng để MOD-08 dùng lịch mặc định (kể cả lịch nghỉ của tenant về sau); quy tắc lạ được
 * ghi nhật ký và cũng dùng lịch mặc định. Kỳ hai luồng tra gói luồng Nhà nước trước (suy luận).
 */
public class PolicyPeriodCalendar implements PeriodCalendarPort {

  static final String DEFAULT_RULE = "NGAY_LAM_VIEC_CUOI_TUAN_LICH_THU_2";
  static final String RULE_KEY = "plan_approval_deadline_rule";

  private static final Logger LOG = LoggerFactory.getLogger(PolicyPeriodCalendar.class);

  private final ObjectProvider<PolicyResolution> resolutions;
  private final ObjectProvider<DefaultPolicyQueryService> queries;

  /** Nhận bean qua {@link ObjectProvider} vì MOD-08 dựng cổng khi chính nó đang được dựng. */
  public PolicyPeriodCalendar(
      ObjectProvider<PolicyResolution> resolutions,
      ObjectProvider<DefaultPolicyQueryService> queries) {
    this.resolutions = Objects.requireNonNull(resolutions, "resolutions");
    this.queries = Objects.requireNonNull(queries, "queries");
  }

  @Override
  public Optional<PeriodCalendar> calendarFor(
      TenantId tenantId, String kind, String flow, LocalDate startDate, LocalDate endDate) {
    List<String> flows = "CA_HAI".equals(flow) ? List.of("NHA_NUOC", "DANG") : List.of(flow);
    for (String f : flows) {
      Optional<Object> rule =
          resolutions
              .getObject()
              .effectiveFor(tenantId, f, startDate)
              .flatMap(v -> queries.getObject().parameter(tenantId, v.versionId(), RULE_KEY));
      if (rule.isPresent()) {
        if (!DEFAULT_RULE.equals(rule.get())) {
          LOG.warn(
              "Quy tắc hạn duyệt kế hoạch {} chưa được hỗ trợ, dùng lịch mặc định", rule.get());
        }
        return Optional.empty();
      }
    }
    return Optional.empty();
  }
}
