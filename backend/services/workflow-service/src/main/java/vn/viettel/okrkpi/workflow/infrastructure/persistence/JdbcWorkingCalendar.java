package vn.viettel.okrkpi.workflow.infrastructure.persistence;

import java.sql.Date;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.viettel.okrkpi.workflow.application.port.WorkingCalendar;

/**
 * {@link WorkingCalendar} trên bảng bổ sung {@code flowable.wf_holiday_calendar} (bản sao {@code
 * config.holiday_calendar}): {@code LAM_BU} là ngày làm việc, {@code NGHI_LE}, {@code NGHI_BU} là
 * ngày nghỉ.
 */
@Repository
public class JdbcWorkingCalendar implements WorkingCalendar {

  private static final String MAKE_UP_DAY = "LAM_BU";

  private final JdbcTemplate jdbc;

  public JdbcWorkingCalendar(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Map<LocalDate, Boolean> overrides(UUID tenantId, LocalDate from, LocalDate to) {
    TenantBinding.bind(jdbc, tenantId);
    Map<LocalDate, Boolean> days = new HashMap<>();
    jdbc.query(
        "SELECT calendar_date, day_kind FROM flowable.wf_holiday_calendar"
            + " WHERE tenant_id = ? AND calendar_date BETWEEN ? AND ?",
        rs -> {
          days.put(
              rs.getDate("calendar_date").toLocalDate(),
              MAKE_UP_DAY.equals(rs.getString("day_kind")));
        },
        tenantId,
        Date.valueOf(from),
        Date.valueOf(to));
    return days;
  }
}
