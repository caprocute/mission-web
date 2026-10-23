package vn.viettel.okrkpi.platform.db.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bộ chạy migration dùng cho Kubernetes Job (Helm hook {@code pre-upgrade}) và kiểm thử.
 *
 * <p>Với mỗi CSDL trong danh sách, chạy lần lượt từng schema bằng tài khoản {@code mig_<schema>}.
 * Theo TL-03 L4596, ở cell bridge có nhiều CSDL dữ liệu tenant, các CSDL được xử lý song song theo
 * nhóm (mặc định 5); một CSDL lỗi không chặn CSDL khác, nhưng mã thoát khác 0 để chặn khởi động
 * phiên bản mới cho tenant đó.
 *
 * <p>Tham số dòng lệnh:
 *
 * <pre>
 *   --schemas=okr,task           danh sách schema, theo thứ tự áp
 *   --db=jdbc:postgresql://...   một CSDL; lặp lại được
 *   --db-list=/duong/dan/tep     tệp mỗi dòng một chuỗi kết nối (lấy từ registry), bỏ dòng trống, dòng '#'
 *   --parallel=5                 số CSDL chạy song song
 * </pre>
 */
public final class MigrationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(MigrationRunner.class);

  /** Số CSDL chạy song song mặc định (TL-03 L4596). */
  public static final int DEFAULT_PARALLEL = 5;

  private final MigrationCredentials credentials;
  private final SchemaMigrator migrator;

  public MigrationRunner(MigrationCredentials credentials, MigrationOptions options) {
    this.credentials = credentials;
    this.migrator = new SchemaMigrator(options);
  }

  /** Kết quả của một CSDL. */
  public record DatabaseOutcome(
      String jdbcUrl, boolean success, int appliedMigrations, String error) {}

  /** Tham số đã phân tích từ dòng lệnh. */
  public record Arguments(List<String> databases, List<String> schemas, int parallel) {}

  public static void main(String[] args) {
    if (standby(System.getenv("VT_SITE_ROLE"))) {
      // INF-28: CSDL ở TTDL chờ là bản sao chỉ đọc; migration chỉ chạy ở TTDL primary.
      LOG.info("TTDL chờ (VT_SITE_ROLE=standby): bỏ qua migration");
      System.exit(0);
    }
    int code;
    try {
      Arguments parsed = parse(args);
      List<DatabaseOutcome> outcomes =
          new MigrationRunner(new EnvironmentCredentials(), MigrationOptions.defaults())
              .run(parsed.databases(), parsed.schemas(), parsed.parallel());
      code = exitCode(outcomes);
    } catch (IllegalArgumentException e) {
      LOG.error("Tham số không hợp lệ: {}", e.getMessage());
      code = 2;
    }
    System.exit(code);
  }

  /** {@code true} khi vai trò TTDL là chờ (INF-28, QA-15). */
  public static boolean standby(String siteRole) {
    return siteRole != null && "standby".equalsIgnoreCase(siteRole.trim());
  }

  /** 0 khi mọi CSDL thành công, 1 khi có ít nhất một CSDL lỗi. */
  public static int exitCode(List<DatabaseOutcome> outcomes) {
    return outcomes.stream().allMatch(DatabaseOutcome::success) ? 0 : 1;
  }

  /** Phân tích tham số dòng lệnh. */
  public static Arguments parse(String[] args) {
    List<String> databases = new ArrayList<>();
    List<String> schemas = new ArrayList<>();
    int parallel = DEFAULT_PARALLEL;
    for (String arg : args) {
      if (arg.startsWith("--db=")) {
        databases.add(arg.substring("--db=".length()));
      } else if (arg.startsWith("--db-list=")) {
        databases.addAll(readDatabaseList(Path.of(arg.substring("--db-list=".length()))));
      } else if (arg.startsWith("--schemas=")) {
        Arrays.stream(arg.substring("--schemas=".length()).split(","))
            .map(String::strip)
            .filter(s -> !s.isEmpty())
            .map(SchemaNames::require)
            .forEach(schemas::add);
      } else if (arg.startsWith("--parallel=")) {
        parallel = Integer.parseInt(arg.substring("--parallel=".length()));
        if (parallel < 1) {
          throw new IllegalArgumentException("--parallel phải từ 1 trở lên");
        }
      } else {
        throw new IllegalArgumentException("Tham số lạ: " + arg);
      }
    }
    if (databases.isEmpty()) {
      throw new IllegalArgumentException("Cần ít nhất một --db hoặc --db-list");
    }
    if (schemas.isEmpty()) {
      throw new IllegalArgumentException("Cần --schemas");
    }
    return new Arguments(List.copyOf(databases), List.copyOf(schemas), parallel);
  }

  private static List<String> readDatabaseList(Path file) {
    try {
      return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
          .map(String::strip)
          .filter(line -> !line.isEmpty() && !line.startsWith("#"))
          .toList();
    } catch (IOException e) {
      throw new IllegalArgumentException("Không đọc được danh sách CSDL " + file, e);
    }
  }

  /** Chạy migration cho mọi CSDL, trả kết quả theo đúng thứ tự danh sách. */
  public List<DatabaseOutcome> run(List<String> databases, List<String> schemas, int parallel) {
    List<String> checkedSchemas = schemas.stream().map(SchemaNames::require).toList();
    ExecutorService pool = Executors.newFixedThreadPool(Math.min(parallel, databases.size()));
    try {
      List<Future<DatabaseOutcome>> futures = new ArrayList<>();
      for (String url : databases) {
        futures.add(pool.submit(() -> migrateDatabase(url, checkedSchemas)));
      }
      List<DatabaseOutcome> outcomes = new ArrayList<>();
      for (int i = 0; i < futures.size(); i++) {
        outcomes.add(await(futures.get(i), databases.get(i)));
      }
      return outcomes;
    } finally {
      pool.shutdownNow();
    }
  }

  private static DatabaseOutcome await(Future<DatabaseOutcome> future, String url) {
    try {
      return future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new DatabaseOutcome(url, false, 0, "Bị ngắt khi chờ");
    } catch (ExecutionException e) {
      return new DatabaseOutcome(url, false, 0, String.valueOf(e.getCause()));
    }
  }

  /** Một CSDL: áp lần lượt từng schema; schema lỗi thì dừng CSDL đó. */
  DatabaseOutcome migrateDatabase(String url, List<String> schemas) {
    String target = redact(url);
    int applied = 0;
    for (String schema : schemas) {
      try {
        MigrateResult result = migrator.migrate(url, schema, credentials.forSchema(url, schema));
        applied += result.migrationsExecuted;
        LOG.info(
            "Đã áp {} migration cho schema {} trên {} (phiên bản {})",
            result.migrationsExecuted,
            schema,
            target,
            result.targetSchemaVersion);
      } catch (RuntimeException e) {
        LOG.error("Migration schema {} trên {} thất bại: {}", schema, target, e.getMessage());
        return new DatabaseOutcome(url, false, applied, schema + ": " + e.getMessage());
      }
    }
    return new DatabaseOutcome(url, true, applied, null);
  }

  /** Bỏ phần tham số sau dấu '?' để không lộ thông tin đăng nhập nếu có trong chuỗi kết nối. */
  static String redact(String url) {
    int q = url.indexOf('?');
    return q < 0 ? url : url.substring(0, q) + "?…";
  }
}
