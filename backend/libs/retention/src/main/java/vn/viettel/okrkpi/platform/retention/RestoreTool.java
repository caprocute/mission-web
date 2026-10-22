package vn.viettel.okrkpi.platform.retention;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Công cụ khôi phục dữ liệu lưu trữ (TL-03 "Cơ chế lưu trữ"; DB-RET-04): chỉ chạy khi có {@link
 * Approval} hai người; tải các tệp Parquet của một tenant theo tệp kê khai, kiểm băm, nạp vào một
 * CSDL tạm cô lập {@code khoi_phuc_<phiếu>} trên cụm khôi phục (không phải cụm vận hành), ghi hạn
 * dùng vào chú thích của CSDL và ghi sự kiện kiểm toán mang người duyệt. {@link #cleanup()} xoá
 * CSDL tạm quá hạn. Cột C2 vẫn là bản mã; đọc nội dung cần quy trình phá kính riêng (SEC-58).
 */
public final class RestoreTool {

  private static final Logger log = LoggerFactory.getLogger(RestoreTool.class);
  static final String PREFIX = "khoi_phuc_";

  public record Result(String database, Map<String, Long> rows, Instant expiresAt) {}

  private final String adminUrl;
  private final String adminUser;
  private final String adminPassword;
  private final ArchiveStore store;
  private final RetentionAudit audit;
  private final ObjectMapper json;
  private final Path workDir;
  private final Clock clock;

  public RestoreTool(
      String adminUrl,
      String adminUser,
      String adminPassword,
      ArchiveStore store,
      RetentionAudit audit,
      ObjectMapper json,
      Path workDir,
      Clock clock) {
    this.adminUrl = adminUrl;
    this.adminUser = adminUser;
    this.adminPassword = adminPassword;
    this.store = store;
    this.audit = audit;
    this.json = json;
    this.workDir = workDir;
    this.clock = clock;
  }

  /**
   * @param approval bắt buộc; thiếu thì từ chối trước khi đọc bất kỳ tệp nào
   */
  public Result restore(String manifestKey, UUID tenant, Approval approval, Duration ttl)
      throws SQLException, IOException {
    if (approval == null) {
      throw new IllegalStateException("Khôi phục dữ liệu lưu trữ cần phê duyệt hai người");
    }
    Manifest m = json.readValue(store.read(manifestKey), Manifest.class);
    String db = PREFIX + approval.reference().toLowerCase(Locale.ROOT).replace('-', '_');
    Instant expires = clock.instant().plus(ttl);
    try (Connection admin = DriverManager.getConnection(adminUrl, adminUser, adminPassword)) {
      exec(admin, "CREATE DATABASE " + ParquetCodec.quote(db));
      Map<String, String> note = new LinkedHashMap<>();
      note.put("het_han", expires.toString());
      note.put("phieu", approval.reference());
      note.put("tenant", tenant.toString());
      note.put("ke_khai", manifestKey);
      exec(
          admin,
          "COMMENT ON DATABASE "
              + ParquetCodec.quote(db)
              + " IS '"
              + json.writeValueAsString(note).replace("'", "''")
              + "'");
    }
    Map<String, Long> rows = new LinkedHashMap<>();
    String target = adminUrl.replaceFirst("/[^/?]+(\\?|$)", "/" + db + "$1");
    try (Connection c = DriverManager.getConnection(target, adminUser, adminPassword)) {
      exec(c, "CREATE SCHEMA " + ParquetCodec.quote(m.schema()));
      ParquetCodec codec = new ParquetCodec(workDir);
      for (Manifest.TableEntry t : m.tables()) {
        exec(c, createTable(m.schema(), t));
        long n = 0;
        for (Manifest.FileEntry f : t.files()) {
          if (!f.tenant().equals(tenant)) {
            continue;
          }
          Path p = Files.createTempFile(workDir, "khoi-phuc-", ".parquet");
          try {
            store.download(f.key(), p);
            if (!ArchiveStore.sha256(p).equals(f.sha256())) {
              throw new IOException("Băm tệp lưu trữ không khớp tệp kê khai: " + f.key());
            }
            n += codec.load(p, t.columns(), c, m.schema(), t.table());
          } finally {
            Files.deleteIfExists(p);
          }
        }
        rows.put(t.table(), n);
      }
    }
    Map<String, String> attrs = new LinkedHashMap<>();
    attrs.put("phieu", approval.reference());
    attrs.put("nguoi_duyet", approval.approvedBy().toString());
    attrs.put("csdl_tam", db);
    attrs.put("het_han", expires.toString());
    attrs.put("so_dong", Long.toString(rows.values().stream().mapToLong(Long::longValue).sum()));
    audit.record(
        "RETENTION_RESTORE",
        tenant,
        approval.requestedBy(),
        "retention.archive",
        manifestKey,
        attrs);
    log.info("Đã khôi phục {} vào {} (hết hạn {})", manifestKey, db, expires);
    return new Result(db, rows, expires);
  }

  /** Xoá các CSDL tạm đã quá hạn; trả tên CSDL đã xoá. */
  public List<String> cleanup() throws SQLException {
    List<String> dropped = new ArrayList<>();
    Instant now = clock.instant();
    try (Connection admin = DriverManager.getConnection(adminUrl, adminUser, adminPassword)) {
      Map<String, String> notes = new LinkedHashMap<>();
      try (PreparedStatement ps =
          admin.prepareStatement(
              "SELECT datname, shobj_description(oid, 'pg_database') FROM pg_database WHERE"
                  + " datname LIKE 'khoi\\_phuc\\_%'")) {
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            notes.put(rs.getString(1), rs.getString(2));
          }
        }
      }
      Function<String, Instant> expiry =
          note -> {
            try {
              return Instant.parse(json.readTree(note).path("het_han").asText());
            } catch (Exception e) {
              // CSDL tạm không có hạn đọc được thì coi như đã hết hạn.
              return Instant.EPOCH;
            }
          };
      for (Map.Entry<String, String> e : notes.entrySet()) {
        if (!expiry.apply(e.getValue() == null ? "{}" : e.getValue()).isAfter(now)) {
          exec(admin, "DROP DATABASE " + ParquetCodec.quote(e.getKey()) + " WITH (FORCE)");
          dropped.add(e.getKey());
        }
      }
    }
    return dropped;
  }

  /**
   * Câu lệnh DDL không tham số hoá được; mọi tên đã qua {@link ParquetCodec#quote} (chỉ chữ thường,
   * số, gạch dưới) và kiểu cột qua {@link #safeType}.
   */
  private static void exec(Connection c, String ddl) throws SQLException {
    try (Statement st = c.createStatement()) {
      st.execute(ddl);
    }
  }

  private static String createTable(String schema, Manifest.TableEntry t) {
    List<String> cols = new ArrayList<>();
    for (ParquetCodec.Column c : t.columns()) {
      cols.add(ParquetCodec.quote(c.name()) + " " + safeType(c.pgType()));
    }
    return "CREATE TABLE "
        + ParquetCodec.quote(schema)
        + "."
        + ParquetCodec.quote(t.table())
        + " ("
        + String.join(", ", cols)
        + ")";
  }

  /** Kiểu cột từ tệp kê khai chỉ được gồm ký tự của {@code format_type}. */
  private static String safeType(String t) {
    if (!t.matches("[a-z0-9 ,()\\[\\]_\"]+")) {
      throw new IllegalArgumentException("Kiểu cột không hợp lệ trong tệp kê khai: " + t);
    }
    return t;
  }
}
