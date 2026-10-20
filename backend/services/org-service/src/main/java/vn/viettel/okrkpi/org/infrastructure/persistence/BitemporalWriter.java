package vn.viettel.okrkpi.org.infrastructure.persistence;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.domain.VersionConflictException;

/**
 * Thành phần ghi phiên bản dùng chung cho bảng hai trục: đóng phiên bản bằng {@code recorded_to = now()} (cột duy
 * nhất mà {@code svc_org} được sửa cùng {@code updated_at}, {@code updated_by}); phiên bản mới chèn với
 * {@code recorded_from} mặc định {@code now()} nên hai khoảng nối liền, không hở, không chồng (TL-03 chương 7).
 */
@Component
public class BitemporalWriter {

  /** Chỉ các bảng hai trục của schema org; tên bảng không bao giờ lấy từ đầu vào. */
  static final Set<String> TABLES = Set.of("org_unit", "org_unit_relation", "person", "position", "assignment",
      "reporting_line");

  private final JdbcClient jdbc;

  public BitemporalWriter(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void close(String table, List<UUID> versionIds, Actor actor) {
    requireTable(table);
    for (UUID versionId : versionIds) {
      int updated = jdbc.sql("UPDATE org." + table
              + " SET recorded_to = now(), updated_at = now(), updated_by = ? "
              + "WHERE tenant_id = ? AND version_id = ? AND recorded_to = 'infinity'")
          .params(actor.actorId(), Sql.tenant(), versionId)
          .update();
      if (updated != 1) {
        // Phiên bản đã bị giao dịch khác đóng: ghi đồng thời.
        throw new VersionConflictException(null, null);
      }
    }
  }

  /** {@code row_version} kế tiếp cho từng thực thể ({@code max + 1}, 0 nếu chưa có). */
  public Map<UUID, Integer> nextVersions(String table, Collection<UUID> ids) {
    requireTable(table);
    Map<UUID, Integer> next = new HashMap<>();
    for (UUID id : ids) {
      next.put(id, 0);
    }
    if (ids.isEmpty()) {
      return next;
    }
    jdbc.sql("SELECT id, max(row_version) AS v FROM org." + table
            + " WHERE tenant_id = ? AND id = ANY(CAST(? AS uuid[])) GROUP BY id")
        .params(Sql.tenant(), Sql.uuidArray(ids))
        .query((rs, n) -> next.put(rs.getObject("id", UUID.class), rs.getInt("v") + 1))
        .list();
    return next;
  }

  private static void requireTable(String table) {
    if (!TABLES.contains(table)) {
      throw new IllegalArgumentException(table);
    }
  }
}
