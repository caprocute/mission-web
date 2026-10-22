package vn.viettel.okrkpi.platform.retention;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Period;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import vn.viettel.okrkpi.shared.audit.DefaultAuditLogger;
import vn.viettel.okrkpi.shared.audit.LoggingAuditSink;
import vn.viettel.okrkpi.shared.kernel.ActorId;

/**
 * Điểm vào của các job lưu giữ (DL-13), chạy bằng CronJob hoặc Job trong {@code
 * deploy/charts/mission-core/templates/retention.yaml}:
 *
 * <pre>
 *   luu-tru SCHEMA                 lưu trữ phân vùng kỳ quá hạn nóng của một schema
 *   xoa-mat-ma                     huỷ DEK của lớp THUC_HIEN, VET_TINH hết tổng thời hạn
 *   xoa-phan-vung SCHEMA           đặt thời hạn pg_partman cho lớp VAN_HANH, KY_THUAT và gỡ phân vùng
 *   khoi-phuc KE_KHAI TENANT PHIEU NGUOI_DE_NGHI NGUOI_DUYET [SO_NGAY]   khôi phục vào CSDL tạm
 *   don-khoi-phuc                  xoá CSDL tạm quá hạn
 * </pre>
 *
 * Cấu hình qua biến môi trường {@code VT_RT_*}; mật khẩu và khoá chỉ đọc từ tệp do ESO gắn: {@code
 * VT_RT_DB_URL}; {@code VT_RT_<AI>_USER}, {@code VT_RT_<AI>_PASSWORD_FILE} với AI là {@code OWNER}
 * ({@code mig_<schema>}), {@code CONFIG} ({@code ro_config}), {@code KEYRING}, {@code EVIDENCE}
 * (kèm {@code VT_RT_EVIDENCE_DB_URL}, tuỳ chọn), {@code RESTORE} (kèm {@code VT_RT_RESTORE_URL},
 * cụm khôi phục cô lập); {@code VT_RT_HOLD_TABLES} (bảng {@code schema.bảng} có {@code legal_hold},
 * cách nhau bằng dấu phẩy); {@code VT_RT_S3_ENDPOINT}, {@code VT_RT_S3_REGION}, {@code
 * VT_RT_S3_BUCKET}, {@code VT_RT_S3_PREFIX}, {@code VT_RT_S3_ACCESS_KEY_FILE}, {@code
 * VT_RT_S3_SECRET_KEY_FILE}; {@code VT_RT_TENANTS_FILE}; {@code VT_RT_CLASSES_FILE} (tuỳ chọn);
 * {@code VT_RT_JOB_ACTOR}; {@code VT_RT_WORK_DIR}; {@code VT_RT_HOLD_EXTENSION} (mặc định P1Y).
 */
public final class RetentionMain {

  private RetentionMain() {}

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      throw new IllegalArgumentException(
          "Thiếu lệnh: luu-tru | xoa-mat-ma | xoa-phan-vung | khoi-phuc | don-khoi-phuc");
    }
    Map<String, String> env = System.getenv();
    Clock clock = Clock.systemUTC();
    ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    RetentionAudit audit =
        new RetentionAudit(
            new DefaultAuditLogger(new LoggingAuditSink(), clock::instant),
            ActorId.of(UUID.fromString(env.get("VT_RT_JOB_ACTOR"))));
    Path work = Path.of(env.getOrDefault("VT_RT_WORK_DIR", System.getProperty("java.io.tmpdir")));
    String prefix = env.getOrDefault("VT_RT_S3_PREFIX", "luu-tru");
    Object result =
        switch (args[0]) {
          case "luu-tru" -> {
            need(args, 2, "luu-tru SCHEMA");
            yield new ArchiveJob(
                    ds(env, "VT_RT_DB_URL", "OWNER"),
                    args[1],
                    rules(env, json),
                    tenants(env),
                    classes(env),
                    store(env),
                    prefix,
                    audit,
                    json,
                    work,
                    clock)
                .run();
          }
          case "xoa-mat-ma" ->
              new CryptoEraseJob(
                      new JdbcTemplate(ds(env, "VT_RT_DB_URL", "KEYRING")),
                      rules(env, json),
                      tenants(env),
                      classes(env),
                      new LegalHoldSource(
                          env.containsKey("VT_RT_EVIDENCE_DB_URL")
                              ? new JdbcTemplate(ds(env, "VT_RT_EVIDENCE_DB_URL", "EVIDENCE"))
                              : null,
                          env.containsKey("VT_RT_HOLD_TABLES")
                              ? new JdbcTemplate(ds(env, "VT_RT_DB_URL", "CONFIG"))
                              : null,
                          Arrays.stream(env.getOrDefault("VT_RT_HOLD_TABLES", "").split(","))
                              .map(String::trim)
                              .filter(s -> !s.isEmpty())
                              .toList()),
                      store(env),
                      Period.parse(env.getOrDefault("VT_RT_HOLD_EXTENSION", "P1Y")),
                      audit,
                      clock)
                  .run();
          case "xoa-phan-vung" -> {
            need(args, 2, "xoa-phan-vung SCHEMA");
            yield new PartitionDropJob(
                    ds(env, "VT_RT_DB_URL", "OWNER"),
                    args[1],
                    rules(env, json),
                    tenants(env),
                    classes(env))
                .run();
          }
          case "khoi-phuc" -> {
            if (args.length != 6 && args.length != 7) {
              throw new IllegalArgumentException(
                  "khoi-phuc KE_KHAI TENANT PHIEU NGUOI_DE_NGHI NGUOI_DUYET [SO_NGAY]");
            }
            Approval approval =
                new Approval(
                    ActorId.of(UUID.fromString(args[4])),
                    ActorId.of(UUID.fromString(args[5])),
                    args[3]);
            yield restore(env, store(env), audit, json, work, clock)
                .restore(
                    args[1],
                    UUID.fromString(args[2]),
                    approval,
                    Duration.ofDays(args.length == 7 ? Long.parseLong(args[6]) : 7));
          }
          case "don-khoi-phuc" -> restore(env, store(env), audit, json, work, clock).cleanup();
          default -> throw new IllegalArgumentException("Lệnh không hợp lệ: " + args[0]);
        };
    System.out.println(result);
  }

  private static void need(String[] args, int n, String usage) {
    if (args.length != n) {
      throw new IllegalArgumentException(usage);
    }
  }

  private static RestoreTool restore(
      Map<String, String> env,
      ArchiveStore store,
      RetentionAudit audit,
      ObjectMapper json,
      Path work,
      Clock clock)
      throws IOException {
    return new RestoreTool(
        env.get("VT_RT_RESTORE_URL"),
        env.get("VT_RT_RESTORE_USER"),
        secret(env, "VT_RT_RESTORE_PASSWORD_FILE"),
        store,
        audit,
        json,
        work,
        clock);
  }

  private static RetentionRules rules(Map<String, String> env, ObjectMapper json)
      throws IOException {
    return new RetentionRules(new JdbcTemplate(ds(env, "VT_RT_DB_URL", "CONFIG")), json);
  }

  private static TenantSource tenants(Map<String, String> env) {
    Path file = Path.of(env.get("VT_RT_TENANTS_FILE"));
    return () -> readTenants(file);
  }

  static List<UUID> readTenants(Path file) {
    try {
      return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
          .map(String::trim)
          .filter(s -> !s.isEmpty() && !s.startsWith("#"))
          .map(UUID::fromString)
          .toList();
    } catch (IOException e) {
      throw new IllegalStateException("Không đọc được danh sách tenant", e);
    }
  }

  private static ClassMap classes(Map<String, String> env) throws IOException {
    Properties p = new Properties();
    String f = env.get("VT_RT_CLASSES_FILE");
    if (f != null && !f.isBlank()) {
      try (Reader r = Files.newBufferedReader(Path.of(f), StandardCharsets.UTF_8)) {
        p.load(r);
      }
    }
    return new ClassMap(p);
  }

  private static ArchiveStore store(Map<String, String> env) throws IOException {
    S3Client s3 =
        S3Client.builder()
            .endpointOverride(URI.create(env.get("VT_RT_S3_ENDPOINT")))
            .region(Region.of(env.getOrDefault("VT_RT_S3_REGION", "us-east-1")))
            .forcePathStyle(true)
            .httpClient(UrlConnectionHttpClient.create())
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(
                        secret(env, "VT_RT_S3_ACCESS_KEY_FILE"),
                        secret(env, "VT_RT_S3_SECRET_KEY_FILE"))))
            .build();
    return new ArchiveStore(s3, env.get("VT_RT_S3_BUCKET"));
  }

  private static DriverManagerDataSource ds(Map<String, String> env, String urlVar, String who)
      throws IOException {
    return new DriverManagerDataSource(
        env.get(urlVar),
        env.get("VT_RT_" + who + "_USER"),
        secret(env, "VT_RT_" + who + "_PASSWORD_FILE"));
  }

  private static String secret(Map<String, String> env, String var) throws IOException {
    return Files.readString(Path.of(env.get(var)), StandardCharsets.UTF_8).trim();
  }
}
