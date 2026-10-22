package vn.viettel.okrkpi.config.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.config.application.port.HolidayCalendarRepository;
import vn.viettel.okrkpi.config.domain.HolidayDay;
import vn.viettel.okrkpi.config.domain.HolidayDayKind;
import vn.viettel.okrkpi.config.domain.HolidayScope;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * {@link HolidayCalendarRepository} trên JDBC ({@code config.holiday_calendar}), chạy trong giao
 * dịch của {@code configTransactionManager} nên RLS theo {@code app.tenant_id}; câu lệnh vẫn lọc
 * thêm {@code tenant_id}.
 */
public class JdbcHolidayCalendarRepository implements HolidayCalendarRepository {

  private static final String COLUMNS = "id, calendar_date, day_kind, name, scope, row_version";

  private final JdbcClient jdbc;

  public JdbcHolidayCalendarRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public void insert(TenantId tenantId, HolidayDay day, UUID actorId) {
    try {
      jdbc.sql(
              """
              INSERT INTO config.holiday_calendar
                (id, tenant_id, created_by, updated_by, row_version, calendar_date, day_kind,
                 name, scope)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
              """)
          .param(day.id())
          .param(tenantId.value())
          .param(actorId)
          .param(actorId)
          .param(day.rowVersion())
          .param(day.date())
          .param(day.kind().name())
          .param(day.name())
          .param(day.scope().name())
          .update();
    } catch (DataIntegrityViolationException e) {
      throw translate(e);
    }
  }

  @Override
  public boolean update(TenantId tenantId, HolidayDay day, int expectedVersion, UUID actorId) {
    try {
      return jdbc.sql(
                  """
                  UPDATE config.holiday_calendar
                     SET calendar_date = ?, day_kind = ?, name = ?, updated_at = now(),
                         updated_by = ?, row_version = row_version + 1
                   WHERE tenant_id = ? AND id = ? AND row_version = ?
                  """)
              .param(day.date())
              .param(day.kind().name())
              .param(day.name())
              .param(actorId)
              .param(tenantId.value())
              .param(day.id())
              .param(expectedVersion)
              .update()
          == 1;
    } catch (DataIntegrityViolationException e) {
      throw translate(e);
    }
  }

  @Override
  public boolean delete(TenantId tenantId, UUID id, int expectedVersion) {
    return jdbc.sql(
                "DELETE FROM config.holiday_calendar WHERE tenant_id = ? AND id = ? AND row_version = ?")
            .param(tenantId.value())
            .param(id)
            .param(expectedVersion)
            .update()
        == 1;
  }

  @Override
  public Optional<HolidayDay> findById(TenantId tenantId, UUID id) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM config.holiday_calendar WHERE tenant_id = ? AND id = ?")
        .param(tenantId.value())
        .param(id)
        .query(JdbcHolidayCalendarRepository::map)
        .optional();
  }

  @Override
  public List<HolidayDay> all(TenantId tenantId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM config.holiday_calendar WHERE tenant_id = ? ORDER BY calendar_date")
        .param(tenantId.value())
        .query(JdbcHolidayCalendarRepository::map)
        .list();
  }

  private static HolidayDay map(ResultSet rs, int row) throws SQLException {
    return new HolidayDay(
        rs.getObject("id", UUID.class),
        rs.getObject("calendar_date", java.time.LocalDate.class),
        HolidayDayKind.valueOf(rs.getString("day_kind")),
        rs.getString("name"),
        HolidayScope.valueOf(rs.getString("scope")),
        rs.getInt("row_version"));
  }

  private static RuntimeException translate(DataIntegrityViolationException e) {
    Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
    if (root instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
      return new DateExistsException(e);
    }
    return e;
  }
}
