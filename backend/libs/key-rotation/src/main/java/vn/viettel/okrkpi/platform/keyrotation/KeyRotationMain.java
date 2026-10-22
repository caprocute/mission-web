package vn.viettel.okrkpi.platform.keyrotation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import vn.viettel.okrkpi.shared.audit.DefaultAuditLogger;
import vn.viettel.okrkpi.shared.audit.LoggingAuditSink;
import vn.viettel.okrkpi.shared.crypto.CryptoProperties;
import vn.viettel.okrkpi.shared.crypto.DekCache;
import vn.viettel.okrkpi.shared.crypto.DekService;
import vn.viettel.okrkpi.shared.crypto.FieldEncryptor;
import vn.viettel.okrkpi.shared.crypto.JcaAesGcmCryptoProvider;
import vn.viettel.okrkpi.shared.crypto.OpenBaoTransitClient;
import vn.viettel.okrkpi.shared.kernel.ActorId;

/**
 * Điểm vào cho CronJob, Job của Helm ({@code
 * deploy/charts/mission-core/templates/key-rotation.yaml}):
 *
 * <pre>
 *   thang                       job đầu tháng
 *   boc-lai                     bọc lại DEK theo phiên bản KEK mới
 *   ma-hoa-lai TENANT DEK PHIEU NGUOI_DE_NGHI NGUOI_DUYET   mã hoá lại khi nghi lộ DEK
 * </pre>
 *
 * Cấu hình qua biến môi trường; mật khẩu và token chỉ đọc từ tệp do ESO/CSI gắn: {@code
 * VT_KR_DB_URL}, {@code VT_KR_KEYRING_USER}, {@code VT_KR_KEYRING_PASSWORD_FILE}, {@code
 * VT_KR_DATA_USER}, {@code VT_KR_DATA_PASSWORD_FILE}, {@code VT_KR_OPENBAO_ADDR}, {@code
 * VT_KR_OPENBAO_TOKEN_FILE}, {@code VT_KR_TRANSIT_MOUNT}, {@code VT_KR_KEK_PREFIX}, {@code
 * VT_KR_TENANTS_FILE} (mỗi dòng một mã tenant), {@code VT_KR_JOB_ACTOR}, {@code VT_KR_PUSHGATEWAY}
 * (tuỳ chọn), {@code VT_KR_BATCH}.
 */
public final class KeyRotationMain {

  private KeyRotationMain() {}

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      throw new IllegalArgumentException("Thiếu tên job: thang | boc-lai | ma-hoa-lai");
    }
    Map<String, String> env = System.getenv();
    Clock clock = Clock.systemUTC();
    JdbcTemplate keyring = jdbc(env, "KEYRING");
    CryptoProperties props = new CryptoProperties();
    props.setOpenBaoBaseUrl(URI.create(env.get("VT_KR_OPENBAO_ADDR")));
    props.setTokenFile(Path.of(env.get("VT_KR_OPENBAO_TOKEN_FILE")));
    props.setTransitMount(env.getOrDefault("VT_KR_TRANSIT_MOUNT", "transit"));
    props.setKekPrefix(env.getOrDefault("VT_KR_KEK_PREFIX", "kek-"));
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RotationReporter reporter =
        new RotationReporter(
            registry,
            new DefaultAuditLogger(new LoggingAuditSink(), clock::instant),
            ActorId.of(UUID.fromString(env.get("VT_KR_JOB_ACTOR"))));
    TenantSource tenants = () -> readTenants(Path.of(env.get("VT_KR_TENANTS_FILE")));
    int batch = Integer.parseInt(env.getOrDefault("VT_KR_BATCH", "200"));
    JcaAesGcmCryptoProvider provider = new JcaAesGcmCryptoProvider();
    OpenBaoTransitClient transit = new OpenBaoTransitClient(props, mapper, clock);
    DekService dekService = new DekService(keyring, transit, provider, new DekCache(), clock);
    Object result =
        switch (args[0]) {
          case "thang" ->
              new MonthlyDekRotationJob(keyring, dekService, tenants, reporter, clock).run();
          case "boc-lai" ->
              new KekRewrapJob(
                      keyring, new TransitRewrapClient(props, mapper), tenants, reporter, batch)
                  .run();
          case "ma-hoa-lai" -> {
            if (args.length != 6) {
              throw new IllegalArgumentException(
                  "ma-hoa-lai TENANT DEK PHIEU NGUOI_DE_NGHI NGUOI_DUYET");
            }
            Approval approval =
                new Approval(
                    ActorId.of(UUID.fromString(args[4])),
                    ActorId.of(UUID.fromString(args[5])),
                    args[3]);
            yield new DekReencryptJob(
                    keyring,
                    jdbc(env, "DATA"),
                    new FieldEncryptor(dekService, provider),
                    dekService,
                    reporter,
                    clock,
                    Map.of(),
                    Map.of(
                        "evidence.upload_session.file_name_enc",
                        "evidence.object_meta.file_name_enc"),
                    batch)
                .run(UUID.fromString(args[1]), UUID.fromString(args[2]), approval, () -> false);
          }
          default -> throw new IllegalArgumentException("Job không hợp lệ: " + args[0]);
        };
    String metrics = prometheusText(registry);
    System.out.println(result);
    System.out.print(metrics);
    String push = env.get("VT_KR_PUSHGATEWAY");
    if (push != null && !push.isBlank()) {
      HttpResponse<String> r =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(URI.create(push + "/metrics/job/key-rotation-" + args[0]))
                      .POST(HttpRequest.BodyPublishers.ofString(metrics))
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      if (r.statusCode() / 100 != 2) {
        throw new IllegalStateException("Pushgateway trả HTTP " + r.statusCode());
      }
    }
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

  /** Định dạng văn bản Prometheus cho các gauge, counter của job (chỉ số đếm, không có khoá). */
  static String prometheusText(MeterRegistry registry) {
    StringBuilder sb = new StringBuilder();
    Map<String, String> types = new HashMap<>();
    for (Meter m : registry.getMeters()) {
      String name = m.getId().getName().replace('.', '_');
      double value;
      if (m instanceof Counter c) {
        name = name + "_total";
        value = c.count();
        types.putIfAbsent(name, "counter");
      } else if (m instanceof Gauge g) {
        value = g.value();
        types.putIfAbsent(name, "gauge");
      } else {
        continue;
      }
      String tags =
          m.getId().getTags().stream()
              .map((Tag t) -> t.getKey() + "=\"" + t.getValue() + "\"")
              .collect(Collectors.joining(","));
      sb.append(name)
          .append(tags.isEmpty() ? "" : "{" + tags + "}")
          .append(' ')
          .append((long) value)
          .append('\n');
    }
    StringBuilder head = new StringBuilder();
    types.forEach((n, t) -> head.append("# TYPE ").append(n).append(' ').append(t).append('\n'));
    return head.append(sb).toString();
  }

  private static JdbcTemplate jdbc(Map<String, String> env, String who) throws IOException {
    DriverManagerDataSource ds =
        new DriverManagerDataSource(
            env.get("VT_KR_DB_URL"),
            env.get("VT_KR_" + who + "_USER"),
            Files.readString(
                    Path.of(env.get("VT_KR_" + who + "_PASSWORD_FILE")), StandardCharsets.UTF_8)
                .trim());
    return new JdbcTemplate(ds);
  }
}
