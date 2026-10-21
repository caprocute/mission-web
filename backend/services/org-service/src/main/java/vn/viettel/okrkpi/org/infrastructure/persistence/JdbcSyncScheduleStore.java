package vn.viettel.okrkpi.org.infrastructure.persistence;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.port.SyncScheduleStore;

/** DV-04-07 · {@code org.sync_schedule}; phiên bản bộ lịch là {@code max(set_version)}, 0 khi chưa có lịch. */
@Repository
public class JdbcSyncScheduleStore implements SyncScheduleStore {

  private final JdbcClient jdbc;

  public JdbcSyncScheduleStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public ScheduleSet load(boolean forUpdate) {
    List<Object[]> rows = jdbc.sql("SELECT connector, enabled, cron, set_version FROM org.sync_schedule "
            + "WHERE tenant_id = :tenant ORDER BY connector" + (forUpdate ? " FOR UPDATE" : ""))
        .param("tenant", Sql.tenant())
        .query((rs, n) -> new Object[] {new Schedule(rs.getString("connector"), rs.getBoolean("enabled"),
            rs.getString("cron")), rs.getInt("set_version")})
        .list();
    int version = rows.stream().mapToInt(r -> (Integer) r[1]).max().orElse(0);
    return new ScheduleSet(version, rows.stream().map(r -> (Schedule) r[0]).toList());
  }

  @Override
  public void replace(List<Schedule> items, int newVersion, Actor actor) {
    // svc_org không có quyền DELETE (TL-03 K1): nguồn vắng mặt trong bộ lịch mới được tắt, không xoá.
    jdbc.sql("UPDATE org.sync_schedule SET enabled = false, set_version = :v, updated_by = :actor, "
            + "updated_at = now() WHERE tenant_id = :tenant")
        .param("tenant", Sql.tenant()).param("v", newVersion).param("actor", actor.actorId()).update();
    for (Schedule s : items) {
      jdbc.sql("INSERT INTO org.sync_schedule (tenant_id, connector, enabled, cron, set_version, created_by, "
              + "updated_by) VALUES (:tenant, :c, :e, :cron, :v, :actor, :actor) "
              + "ON CONFLICT (tenant_id, connector) DO UPDATE SET enabled = EXCLUDED.enabled, cron = EXCLUDED.cron, "
              + "set_version = EXCLUDED.set_version, updated_by = EXCLUDED.updated_by, updated_at = now()")
          .param("tenant", Sql.tenant()).param("c", s.connector()).param("e", s.enabled()).param("cron", s.cron())
          .param("v", newVersion).param("actor", actor.actorId())
          .update();
    }
  }
}
