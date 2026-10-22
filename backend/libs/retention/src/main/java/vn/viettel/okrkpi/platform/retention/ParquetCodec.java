package vn.viettel.okrkpi.platform.retention;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.postgresql.PGConnection;

/**
 * Chuyển dữ liệu một bảng giữa PostgreSQL và Parquet bằng DuckDB trong tiến trình. PostgreSQL xuất
 * CSV qua {@code COPY ... TO STDOUT} (múi giờ UTC, kiểu ngày ISO), DuckDB đọc CSV với cột kiểu
 * chuỗi rồi ép về kiểu Parquet tương ứng và ghi nén zstd. Cột {@code bytea} (gồm bản mã C2) giữ
 * nguyên byte, không giải mã. Chiều ngược lại (khôi phục) đọc Parquet, ghi CSV và {@code COPY FROM
 * STDIN}. Tệp CSV trung gian nằm trong thư mục tạm của job và bị xoá ngay sau mỗi bảng.
 */
final class ParquetCodec {

  /** Cột và kiểu PostgreSQL gốc ({@code format_type}), ghi vào tệp kê khai để khôi phục. */
  record Column(String name, String pgType) {}

  private static final Pattern NUMERIC = Pattern.compile("numeric\\((\\d+),(\\d+)\\)");
  private static final Pattern IDENT = Pattern.compile("[a-z_][a-z0-9_]*");

  private final Path workDir;

  ParquetCodec(Path workDir) {
    this.workDir = workDir;
  }

  static List<Column> columns(Connection pg, String schema, String table) throws SQLException {
    List<Column> out = new ArrayList<>();
    try (PreparedStatement ps =
        pg.prepareStatement(
            "SELECT a.attname, format_type(a.atttypid, a.atttypmod) FROM pg_attribute a"
                + " JOIN pg_class c ON c.oid = a.attrelid JOIN pg_namespace n ON n.oid ="
                + " c.relnamespace WHERE n.nspname = ? AND c.relname = ? AND a.attnum > 0 AND NOT"
                + " a.attisdropped ORDER BY a.attnum")) {
      ps.setString(1, schema);
      ps.setString(2, table);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(new Column(ident(rs.getString(1)), rs.getString(2)));
        }
      }
    }
    return out;
  }

  /**
   * Xuất các dòng của một tenant sang Parquet; trả số dòng. Kết nối phải đang trong giao dịch để
   * {@code SET LOCAL} có hiệu lực (PgBouncer chế độ giao dịch).
   */
  long export(Connection pg, String schema, String table, UUID tenant, List<Column> cols, Path out)
      throws SQLException, IOException {
    Path csv = Files.createTempFile(workDir, "xuat-", ".csv");
    try {
      try (Statement st = pg.createStatement()) {
        st.execute("SET LOCAL TimeZone = 'UTC'");
        st.execute("SET LOCAL DateStyle = 'ISO, YMD'");
        st.execute("SET LOCAL IntervalStyle = 'iso_8601'");
        st.execute("SET LOCAL extra_float_digits = 3");
      }
      String sql =
          "COPY (SELECT "
              + String.join(", ", cols.stream().map(c -> quote(c.name())).toList())
              + " FROM "
              + quote(schema)
              + "."
              + quote(table)
              + " WHERE tenant_id = '"
              + tenant
              + "' ORDER BY "
              + order(cols)
              + ") TO STDOUT WITH (FORMAT csv)";
      long rows;
      try (Writer w = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
        rows = pg.unwrap(PGConnection.class).getCopyAPI().copyOut(sql, w);
      }
      try (Connection duck = DriverManager.getConnection("jdbc:duckdb:");
          Statement st = duck.createStatement()) {
        st.execute("SET TimeZone = 'UTC'");
        List<String> select = new ArrayList<>();
        List<String> spec = new ArrayList<>();
        for (int i = 0; i < cols.size(); i++) {
          spec.add("'c" + i + "': 'VARCHAR'");
          select.add(toDuck("c" + i, cols.get(i).pgType()) + " AS " + quote(cols.get(i).name()));
        }
        st.execute(
            "COPY (SELECT "
                + String.join(", ", select)
                + " FROM read_csv('"
                + literal(csv)
                + "', header = false, auto_detect = false, delim = ',', quote = '\"', escape ="
                + " '\"', allow_quoted_nulls = false, columns = {"
                + String.join(", ", spec)
                + "})) TO '"
                + literal(out)
                + "' (FORMAT parquet, COMPRESSION zstd)");
      }
      return rows;
    } finally {
      Files.deleteIfExists(csv);
    }
  }

  /** Nạp tệp Parquet vào bảng đã tạo trên CSDL đích; trả số dòng. */
  long load(Path parquet, List<Column> cols, Connection pg, String schema, String table)
      throws SQLException, IOException {
    Path csv = Files.createTempFile(workDir, "nap-", ".csv");
    try {
      try (Connection duck = DriverManager.getConnection("jdbc:duckdb:");
          Statement st = duck.createStatement()) {
        st.execute("SET TimeZone = 'UTC'");
        List<String> select = new ArrayList<>();
        for (Column c : cols) {
          select.add(fromDuck(quote(c.name()), c.pgType()));
        }
        st.execute(
            "COPY (SELECT "
                + String.join(", ", select)
                + " FROM read_parquet('"
                + literal(parquet)
                + "')) TO '"
                + literal(csv)
                + "' (FORMAT csv, HEADER false, QUOTE '\"', ESCAPE '\"', FORCE_QUOTE *)");
      }
      String sql =
          "COPY "
              + quote(schema)
              + "."
              + quote(table)
              + " ("
              + String.join(", ", cols.stream().map(c -> quote(c.name())).toList())
              + ") FROM STDIN WITH (FORMAT csv)";
      try (Reader r = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
        return pg.unwrap(PGConnection.class).getCopyAPI().copyIn(sql, r);
      }
    } finally {
      Files.deleteIfExists(csv);
    }
  }

  /** Kiểu DuckDB ghi vào Parquet cho cột đọc từ CSV dạng chuỗi. */
  static String toDuck(String c, String pgType) {
    Matcher m = NUMERIC.matcher(pgType);
    if (m.matches() && Integer.parseInt(m.group(1)) <= 38) {
      return "CAST(" + c + " AS DECIMAL(" + m.group(1) + "," + m.group(2) + "))";
    }
    return switch (pgType) {
      case "uuid" -> "CAST(" + c + " AS UUID)";
      case "smallint", "integer" -> "CAST(" + c + " AS INTEGER)";
      case "bigint" -> "CAST(" + c + " AS BIGINT)";
      case "boolean" -> "CAST(" + c + " AS BOOLEAN)";
      case "date" -> "CAST(" + c + " AS DATE)";
      case "double precision", "real" -> "CAST(" + c + " AS DOUBLE)";
      case "timestamp with time zone" -> "CAST(" + c + " AS TIMESTAMPTZ)";
      case "timestamp without time zone" -> "CAST(" + c + " AS TIMESTAMP)";
      case "bytea" -> "from_hex(substr(" + c + ", 3))";
      default -> c;
    };
  }

  /** Biểu thức DuckDB in giá trị ở dạng văn bản PostgreSQL nhận lại được. */
  static String fromDuck(String c, String pgType) {
    return switch (pgType) {
      case "bytea" ->
          "CASE WHEN " + c + " IS NULL THEN NULL ELSE '\\x' || lower(hex(" + c + ")) END";
      case "timestamp with time zone" -> "strftime(" + c + ", '%Y-%m-%d %H:%M:%S.%f+00')";
      case "timestamp without time zone" -> "strftime(" + c + ", '%Y-%m-%d %H:%M:%S.%f')";
      default -> "CAST(" + c + " AS VARCHAR)";
    };
  }

  private static String order(List<Column> cols) {
    return cols.stream().anyMatch(c -> c.name().equals("id")) ? "id" : "1";
  }

  static String ident(String s) {
    if (!IDENT.matcher(s).matches()) {
      throw new IllegalArgumentException("Tên định danh không hợp lệ: " + s);
    }
    return s;
  }

  static String quote(String ident) {
    return "\"" + ident(ident) + "\"";
  }

  private static String literal(Path p) {
    return p.toAbsolutePath().toString().replace("'", "''");
  }
}
