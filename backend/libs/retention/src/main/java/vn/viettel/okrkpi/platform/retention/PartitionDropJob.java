package vn.viettel.okrkpi.platform.retention;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Xoá phân vùng cho lớp {@code VAN_HANH}, {@code KY_THUAT} (TL-03 "Thời hạn lưu giữ"; DB-RET-03):
 * các bảng này phân vùng theo thời gian bằng pg_partman (NEN-032). Job đặt {@code retention} của
 * pg_partman không dài hơn tổng thời hạn giữ lâu nhất của các tenant trên CSDL rồi gọi {@code
 * fn_run_partition_maintenance()} của schema để gỡ phân vùng hết hạn. Bảng có cột {@code
 * legal_hold} không bao giờ bị xoá theo cách này (báo trong kết quả). Kết nối bằng {@code
 * mig_<schema>}.
 */
public final class PartitionDropJob {

  private static final Logger log = LoggerFactory.getLogger(PartitionDropJob.class);

  public record TableResult(
      String table, RetentionClass retentionClass, String retention, int dropped) {}

  public record Result(List<TableResult> tables, List<String> refused) {}

  private final DataSource owner;
  private final String schema;
  private final RetentionRules rules;
  private final TenantSource tenants;
  private final ClassMap classes;

  public PartitionDropJob(
      DataSource owner,
      String schema,
      RetentionRules rules,
      TenantSource tenants,
      ClassMap classes) {
    this.owner = owner;
    this.schema = ParquetCodec.ident(schema);
    this.rules = rules;
    this.tenants = tenants;
    this.classes = classes;
  }

  public Result run() throws SQLException {
    List<UUID> tenantList = tenants.tenants();
    List<TableResult> out = new ArrayList<>();
    List<String> refused = new ArrayList<>();
    try (Connection c = owner.getConnection()) {
      Map<String, Integer> before = new LinkedHashMap<>();
      Map<String, RetentionClass.Rule> applied = new LinkedHashMap<>();
      Map<String, String> retentions = new LinkedHashMap<>();
      for (String table : partmanTables(c)) {
        RetentionClass rc = classes.of(schema, table);
        if (rc == null || rc.archived()) {
          continue;
        }
        if (hasLegalHold(c, table)) {
          refused.add(schema + "." + table);
          log.warn("Không xoá phân vùng {}.{} vì bảng có cột legal_hold", schema, table);
          continue;
        }
        RetentionClass.Rule rule = rules.longest(rc, tenantList);
        // Tổng thời hạn là mức tối đa: thời hạn pg_partman ngắn hơn đã có (outbox 3 ngày,
        // idempotency 2 ngày của NEN-032) được giữ nguyên.
        long days = RetentionClass.days(rule.total());
        Long current = currentDays(c, table);
        String retention = (current == null || current > days ? days : current) + " days";
        try (PreparedStatement ps =
            c.prepareStatement(
                "UPDATE partman.part_config SET retention = ?, retention_keep_table = false WHERE"
                    + " parent_table = ?")) {
          ps.setString(1, retention);
          ps.setString(2, schema + "." + table);
          ps.executeUpdate();
        }
        retentions.put(table, retention);
        before.put(table, children(c, table));
        applied.put(table, rule);
      }
      if (!applied.isEmpty()) {
        try (Statement st = c.createStatement()) {
          st.execute("SELECT " + ParquetCodec.quote(schema) + ".fn_run_partition_maintenance()");
        }
      }
      for (Map.Entry<String, RetentionClass.Rule> e : applied.entrySet()) {
        int dropped = Math.max(0, before.get(e.getKey()) - children(c, e.getKey()));
        RetentionClass rc = e.getValue().retentionClass();
        out.add(new TableResult(e.getKey(), rc, retentions.get(e.getKey()), dropped));
      }
    }
    return new Result(out, refused);
  }

  private List<String> partmanTables(Connection c) throws SQLException {
    List<String> out = new ArrayList<>();
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT split_part(parent_table, '.', 2) FROM partman.part_config WHERE"
                + " split_part(parent_table, '.', 1) = ? ORDER BY parent_table")) {
      ps.setString(1, schema);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(rs.getString(1));
        }
      }
    }
    return out;
  }

  private Long currentDays(Connection c, String table) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT ceil(extract(epoch FROM retention::interval) / 86400)::bigint FROM"
                + " partman.part_config WHERE parent_table = ? AND retention IS NOT NULL")) {
      ps.setString(1, schema + "." + table);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getLong(1) : null;
      }
    }
  }

  private boolean hasLegalHold(Connection c, String table) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = ? AND"
                + " table_name = ? AND column_name = 'legal_hold')")) {
      ps.setString(1, schema);
      ps.setString(2, table);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getBoolean(1);
      }
    }
  }

  private int children(Connection c, String table) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT count(*) FROM pg_inherits i JOIN pg_class p ON p.oid = i.inhparent JOIN"
                + " pg_namespace n ON n.oid = p.relnamespace WHERE n.nspname = ? AND p.relname = ?")) {
      ps.setString(1, schema);
      ps.setString(2, table);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }
}
