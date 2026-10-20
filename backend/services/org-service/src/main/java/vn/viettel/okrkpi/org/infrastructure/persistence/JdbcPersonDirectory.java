package vn.viettel.okrkpi.org.infrastructure.persistence;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.viettel.okrkpi.org.application.port.PersonDirectory;

/** Họ tên (C1) theo phiên bản mới nhất trên trục hiệu lực, hiểu biết hiện tại; không đọc cột C2. */
@Repository
public class JdbcPersonDirectory implements PersonDirectory {

  private final JdbcClient jdbc;

  public JdbcPersonDirectory(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Map<UUID, String> currentNames(Collection<UUID> personIds) {
    Map<UUID, String> out = new HashMap<>();
    if (personIds.isEmpty()) {
      return out;
    }
    jdbc.sql("SELECT DISTINCT ON (id) id, full_name FROM org.person WHERE tenant_id = :tenant "
            + "AND id = ANY(CAST(:ids AS uuid[])) AND recorded_to = 'infinity' ORDER BY id, valid_from DESC")
        .param("tenant", Sql.tenant()).param("ids", Sql.uuidArray(personIds))
        .query((rs, n) -> out.put(Sql.uuid(rs, "id"), rs.getString("full_name"))).list();
    return out;
  }
}
