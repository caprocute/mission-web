package vn.viettel.okrkpi.platform.retention;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Job lưu trữ của một schema (TL-03 "Cơ chế lưu trữ", DB-RET-02). Với mỗi {@code period_bucket} mà
 * mọi bảng cùng lớp đã quá thời hạn nóng:
 *
 * <ol>
 *   <li>tách phân vùng khỏi bảng cha ({@code DETACH PARTITION}) và tắt {@code FORCE ROW LEVEL
 *       SECURITY} trên phân vùng đã tách để chủ sở hữu đọc được mọi tenant (phân vùng đã thu hồi
 *       quyền của {@code svc_}, nên không vai trò ứng dụng nào đọc được);
 *   <li>xuất từng tenant sang Parquet, ghi bucket Object Lock COMPLIANCE với hạn bằng tổng thời hạn
 *       giữ lâu nhất của các tenant, bật giữ pháp lý cho tệp của tenant có dòng {@code legal_hold};
 *   <li>so tổng số dòng đã xuất với số dòng của phân vùng, ghi tệp kê khai, ghi sự kiện kiểm toán
 *       từng tenant, rồi mới xoá phân vùng.
 * </ol>
 *
 * Lỗi ở bất kỳ bước nào trước khi xoá thì gắn lại phân vùng ({@code ATTACH}) và bật lại {@code
 * FORCE}; phân vùng đã tách mà job dừng đột ngột được nhận ra ở lần chạy sau và xử lý tiếp. Kết nối
 * bằng {@code mig_<schema>} (chủ sở hữu, có quyền DDL). Không chạy cho lớp {@code SO_CAI}: sổ cái
 * do {@code svc_ledger_archiver} của SVC-09 lưu trữ với hai người phê duyệt.
 */
public final class ArchiveJob {

  private static final Logger log = LoggerFactory.getLogger(ArchiveJob.class);
  private static final Pattern BOUND = Pattern.compile("FOR VALUES IN \\((\\d+)\\)");
  private static final Pattern CHILD = Pattern.compile("(.+)_p(\\d{5})");

  /** Kết quả của một nhóm {@code (period_bucket, lớp)}. */
  public record Group(
      int periodBucket, RetentionClass retentionClass, List<String> tables, String manifestKey) {}

  public record Result(List<Group> archived, List<String> skipped) {}

  private final DataSource owner;
  private final String schema;
  private final RetentionRules rules;
  private final TenantSource tenants;
  private final ClassMap classes;
  private final ArchiveStore store;
  private final String prefix;
  private final RetentionAudit audit;
  private final ObjectMapper json;
  private final Path workDir;
  private final Clock clock;

  public ArchiveJob(
      DataSource owner,
      String schema,
      RetentionRules rules,
      TenantSource tenants,
      ClassMap classes,
      ArchiveStore store,
      String prefix,
      RetentionAudit audit,
      ObjectMapper json,
      Path workDir,
      Clock clock) {
    this.owner = owner;
    this.schema = ParquetCodec.ident(schema);
    this.rules = rules;
    this.tenants = tenants;
    this.classes = classes;
    this.store = store;
    this.prefix = prefix;
    this.audit = audit;
    this.json = json;
    this.workDir = workDir;
    this.clock = clock;
  }

  public Result run() throws SQLException, IOException {
    LocalDate today = LocalDate.now(clock);
    List<UUID> tenantList = tenants.tenants();
    List<String> skipped = new ArrayList<>();
    // (bucket, lớp) -> bảng cha -> phân vùng (đang gắn hoặc đã tách dở)
    Map<String, Map<String, Partition>> groups = new TreeMap<>();
    Map<RetentionClass, RetentionClass.Rule> ruleCache = new LinkedHashMap<>();
    try (Connection c = owner.getConnection()) {
      for (Partition p : partitions(c)) {
        RetentionClass rc = classes.of(schema, p.parent());
        if (rc == null || !rc.archived() || rc == RetentionClass.SO_CAI) {
          skipped.add(
              p.parent() + "_p" + p.bucket() + (rc == null ? " (chưa xếp lớp)" : " (" + rc + ")"));
          continue;
        }
        RetentionClass.Rule rule = ruleCache.computeIfAbsent(rc, k -> rules.longest(k, tenantList));
        if (!PeriodBucket.end(p.bucket()).plus(rule.hot()).isAfter(today)) {
          groups
              .computeIfAbsent(p.bucket() + "|" + rc.name(), k -> new TreeMap<>())
              .put(p.parent(), p);
        }
      }
    }
    List<Group> done = new ArrayList<>();
    for (Map.Entry<String, Map<String, Partition>> g : groups.entrySet()) {
      String[] k = g.getKey().split("\\|");
      int bucket = Integer.parseInt(k[0]);
      RetentionClass rc = RetentionClass.valueOf(k[1]);
      done.add(archive(bucket, rc, ruleCache.get(rc), new ArrayList<>(g.getValue().values())));
    }
    return new Result(done, skipped);
  }

  private Group archive(
      int bucket, RetentionClass rc, RetentionClass.Rule rule, List<Partition> parts)
      throws SQLException, IOException {
    LocalDate retainUntil = PeriodBucket.end(bucket).plus(rule.total());
    try (Connection c = owner.getConnection()) {
      c.setAutoCommit(false);
      for (Partition p : parts) {
        if (p.attached()) {
          exec(c, "ALTER TABLE " + q(p.parent()) + " DETACH PARTITION " + q(p.child()));
        }
        exec(c, "ALTER TABLE " + q(p.child()) + " NO FORCE ROW LEVEL SECURITY");
      }
      c.commit();
      try {
        List<Manifest.TableEntry> entries = new ArrayList<>();
        Map<UUID, Long> rowsByTenant = new TreeMap<>();
        for (Partition p : parts) {
          entries.add(export(c, bucket, p, retainUntil, rowsByTenant));
        }
        Manifest m =
            new Manifest(schema, bucket, rc, clock.instant(), retainUntil, List.copyOf(entries));
        String key = Manifest.key(prefix, schema, bucket, rc);
        Path mf = Files.createTempFile(workDir, "ke-khai-", ".json");
        try {
          Files.writeString(mf, json.writeValueAsString(m), StandardCharsets.UTF_8);
          store.putLocked(key, mf, retainUntil, false);
        } finally {
          Files.deleteIfExists(mf);
        }
        for (Map.Entry<UUID, Long> t : rowsByTenant.entrySet()) {
          Map<String, String> attrs = new LinkedHashMap<>();
          attrs.put("schema", schema);
          attrs.put("period_bucket", Integer.toString(bucket));
          attrs.put("lop_luu_giu", rc.name());
          attrs.put("so_dong", Long.toString(t.getValue()));
          attrs.put("han_giu", retainUntil.toString());
          audit.record("RETENTION_ARCHIVE", t.getKey(), null, "retention.archive", key, attrs);
        }
        for (Partition p : parts) {
          exec(c, "DROP TABLE " + q(p.child()));
        }
        c.commit();
        log.info(
            "Đã lưu trữ {}.{} kỳ {} lớp {}: {} tenant, hạn giữ {}",
            schema,
            parts.stream().map(Partition::parent).toList(),
            bucket,
            rc,
            rowsByTenant.size(),
            retainUntil);
        return new Group(bucket, rc, parts.stream().map(Partition::parent).toList(), key);
      } catch (SQLException | IOException | RuntimeException e) {
        c.rollback();
        reattach(c, bucket, parts);
        throw e;
      }
    }
  }

  private Manifest.TableEntry export(
      Connection c, int bucket, Partition p, LocalDate retainUntil, Map<UUID, Long> rowsByTenant)
      throws SQLException, IOException {
    List<ParquetCodec.Column> cols = ParquetCodec.columns(c, schema, p.child());
    boolean hasHold = cols.stream().anyMatch(x -> x.name().equals("legal_hold"));
    Map<UUID, Boolean> tenantHold = new TreeMap<>();
    try (Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT tenant_id, "
                    + (hasHold ? "bool_or(legal_hold)" : "false")
                    + " FROM "
                    + q(p.child())
                    + " GROUP BY tenant_id")) {
      while (rs.next()) {
        tenantHold.put(rs.getObject(1, UUID.class), rs.getBoolean(2));
      }
    }
    long total;
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM " + q(p.child()))) {
      rs.next();
      total = rs.getLong(1);
    }
    ParquetCodec codec = new ParquetCodec(workDir);
    List<Manifest.FileEntry> files = new ArrayList<>();
    long exported = 0;
    for (Map.Entry<UUID, Boolean> t : tenantHold.entrySet()) {
      Path out = workDir.resolve(p.child() + "-" + t.getKey() + ".parquet");
      try {
        long n = codec.export(c, schema, p.child(), t.getKey(), cols, out);
        c.commit();
        String key = Manifest.fileKey(prefix, schema, bucket, p.parent(), t.getKey());
        String sha = store.putLocked(key, out, retainUntil, t.getValue());
        files.add(new Manifest.FileEntry(t.getKey(), key, n, sha, t.getValue()));
        rowsByTenant.merge(t.getKey(), n, Long::sum);
        exported += n;
      } finally {
        Files.deleteIfExists(out);
      }
    }
    if (exported != total) {
      throw new IllegalStateException(
          "Số dòng đã xuất " + exported + " khác số dòng phân vùng " + total + " của " + p.child());
    }
    return new Manifest.TableEntry(p.parent(), total, cols, List.copyOf(files));
  }

  private void reattach(Connection c, int bucket, List<Partition> parts) {
    for (Partition p : parts) {
      try {
        exec(c, "ALTER TABLE " + q(p.child()) + " FORCE ROW LEVEL SECURITY");
        exec(
            c,
            "ALTER TABLE "
                + q(p.parent())
                + " ATTACH PARTITION "
                + q(p.child())
                + " FOR VALUES IN ("
                + bucket
                + ")");
        c.commit();
      } catch (SQLException e) {
        log.error("Không gắn lại được phân vùng {}.{}, cần xử lý tay", schema, p.child(), e);
        try {
          c.rollback();
        } catch (SQLException ignored) {
          // giữ lỗi gốc
        }
      }
    }
  }

  /** Phân vùng LIST theo {@code period_bucket} đang gắn và phân vùng đã tách dở ở lần trước. */
  private List<Partition> partitions(Connection c) throws SQLException {
    List<String> parents = new ArrayList<>();
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT p.relname FROM pg_partitioned_table pt JOIN pg_class p ON p.oid = pt.partrelid"
                + " JOIN pg_namespace n ON n.oid = p.relnamespace JOIN pg_attribute a ON"
                + " a.attrelid = pt.partrelid AND a.attnum = pt.partattrs[0] WHERE n.nspname = ?"
                + " AND pt.partstrat = 'l' AND pt.partnatts = 1 AND a.attname = 'period_bucket'"
                + " AND NOT p.relispartition ORDER BY p.relname")) {
      ps.setString(1, schema);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          parents.add(rs.getString(1));
        }
      }
    }
    List<Partition> out = new ArrayList<>();
    List<String> roots = new ArrayList<>();
    for (String parent : parents) {
      Matcher m = CHILD.matcher(parent);
      if (m.matches() && parents.contains(m.group(1))) {
        // Phân vùng đã tách dở ở lần chạy trước (bảng con có phân vùng băm cũng là bảng phân vùng).
        out.add(new Partition(m.group(1), parent, Integer.parseInt(m.group(2)), false));
      } else {
        roots.add(parent);
      }
    }
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT ch.relname, pg_get_expr(ch.relpartbound, ch.oid) FROM pg_inherits i JOIN"
                + " pg_class ch ON ch.oid = i.inhrelid JOIN pg_class p ON p.oid = i.inhparent JOIN"
                + " pg_namespace n ON n.oid = p.relnamespace WHERE n.nspname = ? AND p.relname = ?"
                + " ORDER BY ch.relname")) {
      for (String root : roots) {
        ps.setString(1, schema);
        ps.setString(2, root);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            Matcher b = BOUND.matcher(Objects.requireNonNullElse(rs.getString(2), ""));
            if (b.matches()) {
              out.add(new Partition(root, rs.getString(1), Integer.parseInt(b.group(1)), true));
            }
          }
        }
      }
    }
    // Phân vùng đã tách không có phân vùng băm thì là bảng thường.
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE"
                + " n.nspname = ? AND c.relkind = 'r' AND NOT c.relispartition")) {
      ps.setString(1, schema);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          Matcher m = CHILD.matcher(rs.getString(1));
          if (m.matches() && roots.contains(m.group(1))) {
            out.add(
                new Partition(m.group(1), rs.getString(1), Integer.parseInt(m.group(2)), false));
          }
        }
      }
    }
    return out;
  }

  private record Partition(String parent, String child, int bucket, boolean attached) {}

  private String q(String table) {
    return ParquetCodec.quote(schema) + "." + ParquetCodec.quote(table);
  }

  private static void exec(Connection c, String sql) throws SQLException {
    try (Statement st = c.createStatement()) {
      st.execute(sql);
    }
  }
}
