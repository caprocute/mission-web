package vn.viettel.okrkpi.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * DV-15-04 trên ClickHouse 25.8 và Valkey 8 thật, Cube, Keycloak và MOD-08 giả lập: ngữ cảnh an
 * ninh gửi Cube đúng nhánh của người xem, thu hẹp theo đơn vị con và từ chối đơn vị ngoài nhánh, bộ
 * đệm 60 giây theo phạm vi, so sánh kỳ trước theo kỳ liền trước trong kho, lỗi lớp ngữ nghĩa, cổng
 * vai trò, vòng đời dashboard qua cổng dịch vụ MOD-08 và so khớp mọi yêu cầu, phản hồi với {@code
 * platform.yaml}.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"okrkpi.opa.enabled=false", "mission.analytics.cube-timeout=5s"})
class AnalyticsApiIT {

  static final String TENANT = "0192f000-0000-7000-8000-00000000a001";
  static final String OTHER_TENANT = "0192f000-0000-7000-8000-00000000b002";
  static final String UNIT_TINH = "0192f000-0000-7000-8000-0000000000e1";
  static final String UNIT_HUYEN = "0192f000-0000-7000-8000-0000000000e2";
  static final String UNIT_KHAC = "0192f000-0000-7000-8000-0000000000e3";
  static final String KY_Q3 = "0192f000-0000-7000-8000-0000000000a3";
  static final String KY_Q4 = "0192f000-0000-7000-8000-0000000000a4";
  static final String BI_THU = "0192f000-0000-7000-8000-000000000101";
  static final String TRUONG_HUYEN = "0192f000-0000-7000-8000-000000000102";
  static final String DB = "analytics_it";
  static final String CH_PASSWORD = "mat-khau-kiem-thu";
  static final KeyPair KEYS = keys();
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final OpenApiInteractionValidator CONTRACT =
      OpenApiInteractionValidator.createForSpecificationUrl(
              Path.of("../../../contracts/openapi/modules/platform.yaml")
                  .toAbsolutePath()
                  .toUri()
                  .toString())
          .withLevelResolver(
              LevelResolver.create()
                  .withLevel("validation.request.security.missing", ValidationReport.Level.IGNORE)
                  .withLevel(
                      "validation.schema.additionalProperties", ValidationReport.Level.IGNORE)
                  .build())
          .build();

  static FakeUpstream upstream;

  @Container
  static final GenericContainer<?> CLICKHOUSE =
      new GenericContainer<>("mirror.gcr.io/clickhouse/clickhouse-server:25.8")
          .withEnv("CLICKHOUSE_PASSWORD", CH_PASSWORD)
          .withExposedPorts(8123)
          .waitingFor(Wait.forHttp("/ping").forPort(8123));

  @Container
  static final GenericContainer<?> VALKEY =
      new GenericContainer<>("mirror.gcr.io/valkey/valkey:8").withExposedPorts(6379);

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) throws Exception {
    if (upstream == null) {
      upstream = new FakeUpstream();
    }
    r.add("mission.analytics.cube-url", upstream::url);
    r.add("mission.analytics.cube-api-secret", () -> FakeUpstream.CUBE_SECRET);
    r.add("mission.analytics.core-url", upstream::url);
    r.add(
        "mission.analytics.token-url",
        () -> upstream.url() + "/realms/mission/protocol/openid-connect/token");
    r.add("mission.analytics.client-secret", () -> "bi-mat-kiem-thu");
    r.add("mission.analytics.superset-url", upstream::url);
    r.add("mission.analytics.superset-public-url", () -> "https://bi.mission.example");
    r.add("mission.analytics.superset-username", () -> FakeUpstream.SUPERSET_USER);
    r.add("mission.analytics.superset-password", () -> FakeUpstream.SUPERSET_PASSWORD);
    r.add("mission.analytics.clickhouse-url", AnalyticsApiIT::chUrl);
    r.add("mission.analytics.clickhouse-database", () -> DB);
    r.add("mission.analytics.clickhouse-username", () -> "default");
    r.add("mission.analytics.clickhouse-password", () -> CH_PASSWORD);
    r.add(
        "mission.analytics.valkey-url",
        () -> "redis://" + VALKEY.getHost() + ":" + VALKEY.getMappedPort(6379));
  }

  static String chUrl() {
    return "http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123);
  }

  @TestConfiguration
  static class Jwt {
    @Bean
    JwtDecoder testJwtDecoder() {
      return NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
    }
  }

  @LocalServerPort int port;

  /** Bảng của kho phân tích tối giản, cùng tên cột với V002, V003, V008, V010 của DV-15-01. */
  @BeforeAll
  static void warehouse() throws Exception {
    ch("CREATE DATABASE IF NOT EXISTS " + DB);
    ch(
        "CREATE TABLE "
            + DB
            + ".dim_org_scope (tenant_id UUID, id UUID, code String, name String,"
            + " org_path_dang String, org_path_chuyen_mon String) ENGINE = MergeTree ORDER BY (tenant_id, id)");
    ch(
        "CREATE TABLE "
            + DB
            + ".dim_org_closure (tenant_id UUID, tree LowCardinality(String), ancestor_id UUID,"
            + " descendant_id UUID, depth Int32, _version UInt64, _is_deleted UInt8)"
            + " ENGINE = ReplacingMergeTree(_version, _is_deleted) ORDER BY (tenant_id, tree, descendant_id, ancestor_id)");
    ch(
        "CREATE TABLE "
            + DB
            + ".cube_period (tenant_id UUID, id UUID, code String, name String,"
            + " kind LowCardinality(String), flow LowCardinality(String), start_date Date32, end_date Date32,"
            + " status String) ENGINE = MergeTree ORDER BY (tenant_id, id)");
    ch(
        "INSERT INTO "
            + DB
            + ".dim_org_scope VALUES"
            + " ('"
            + TENANT
            + "', '"
            + UNIT_TINH
            + "', 'T01', 'Tỉnh uỷ', '/t01', '/cm01'),"
            + " ('"
            + TENANT
            + "', '"
            + UNIT_HUYEN
            + "', 'H01', 'Huyện uỷ', '/t01/h01', '/cm01/h01'),"
            + " ('"
            + TENANT
            + "', '"
            + UNIT_KHAC
            + "', 'T02', 'Tỉnh khác', '/t02', ''),"
            + " ('"
            + OTHER_TENANT
            + "', '"
            + UNIT_HUYEN
            + "', 'X', 'Tenant khác', '/x01', '')");
    ch(
        "INSERT INTO "
            + DB
            + ".dim_org_closure VALUES"
            + " ('"
            + TENANT
            + "', 'DANG', '"
            + UNIT_HUYEN
            + "', '"
            + UNIT_HUYEN
            + "', 0, 1, 0),"
            + " ('"
            + TENANT
            + "', 'DANG', '"
            + UNIT_TINH
            + "', '"
            + UNIT_HUYEN
            + "', 1, 1, 0),"
            + " ('"
            + TENANT
            + "', 'DANG', '"
            + UNIT_TINH
            + "', '"
            + UNIT_TINH
            + "', 0, 1, 0)");
    ch(
        "INSERT INTO "
            + DB
            + ".cube_period VALUES"
            + " ('"
            + TENANT
            + "', '"
            + KY_Q3
            + "', 'Q3', 'Quý III', 'QUY', 'NHA_NUOC', '2026-07-01', '2026-09-30', 'DA_KHOA'),"
            + " ('"
            + TENANT
            + "', '"
            + KY_Q4
            + "', 'Q4', 'Quý IV', 'QUY', 'NHA_NUOC', '2026-10-01', '2026-12-31', 'DANG_MO'),"
            + " ('"
            + TENANT
            + "', '0192f000-0000-7000-8000-0000000000a9', 'Q3D', 'Quý III Đảng', 'QUY', 'DANG', '2026-07-01', '2026-09-30', 'DA_KHOA')");
  }

  @AfterAll
  static void stop() {
    if (upstream != null) {
      upstream.close();
    }
  }

  @Test
  void queryUsesViewerBranchCachesPerScopeAndMatchesContract() {
    // limit riêng để không trùng khoá đệm với truy vấn của các ca khác (cùng Valkey).
    Map<String, Object> q =
        Map.of(
            "measures",
            List.of("okr.tien_do_o"),
            "dimensions",
            List.of("org_unit.ten"),
            "limit",
            37);
    Reply first = post("/analytics/query", token(BI_THU, "leader", UNIT_TINH), q, Map.of());
    assertThat(first.status()).isEqualTo(200);
    assertThat(first.json().path("semanticModelVersion").asText()).isEqualTo("2026.4");
    assertThat(first.json().path("stale").asBoolean()).isFalse();
    assertThat(first.json().path("columns").get(1).path("unit").asText()).isEqualTo("%");
    assertThat(first.json().path("rows").get(0).get(1).decimalValue()).isEqualByComparingTo("72.5");
    int calls = upstream.dataCalls().size();
    FakeUpstream.CubeCall call = upstream.dataCalls().get(calls - 1);
    assertThat(call.tenant()).isEqualTo(TENANT);
    assertThat(call.orgPath()).isEqualTo("/t01");

    Reply again = post("/analytics/query", token(BI_THU, "leader", UNIT_TINH), q, Map.of());
    assertThat(again.body()).isEqualTo(first.body());
    assertThat(upstream.dataCalls()).hasSize(calls);

    Reply huyen = post("/analytics/query", token(TRUONG_HUYEN, "manager", UNIT_HUYEN), q, Map.of());
    assertThat(huyen.status()).isEqualTo(200);
    assertThat(upstream.dataCalls()).hasSize(calls + 1);
    assertThat(upstream.dataCalls().get(calls).orgPath()).isEqualTo("/t01/h01");
  }

  /** Tiêu chí task: p95 khi trúng đệm ≤ 1 giây (đo cục bộ, tuần tự, không phải thử tải). */
  @Test
  void cachedQueryP95AndValkeyKeyLayout() throws Exception {
    String bt = token(BI_THU, "leader", UNIT_TINH);
    Map<String, Object> q =
        Map.of("measures", List.of("okr.so_muc_tieu"), "periodId", KY_Q4, "limit", 41);
    assertThat(post("/analytics/query", bt, q, Map.of()).status()).isEqualTo(200);
    int calls = upstream.dataCalls().size();
    List<Long> ms = new java.util.ArrayList<>();
    for (int i = 0; i < 100; i++) {
      long t0 = System.nanoTime();
      assertThat(post("/analytics/query", bt, q, Map.of()).status()).isEqualTo(200);
      ms.add((System.nanoTime() - t0) / 1_000_000);
    }
    java.util.Collections.sort(ms);
    long p95 = ms.get(94);
    System.out.printf("DV-15-04 p95 trúng đệm: %d ms (trung vị %d ms)%n", p95, ms.get(49));
    assertThat(p95).isLessThan(1000);
    assertThat(upstream.dataCalls()).hasSize(calls);
    String keys =
        VALKEY
            .execInContainer(
                "valkey-cli", "--scan", "--pattern", "t:" + TENANT + ":analytics:query:*")
            .getStdout();
    assertThat(keys).contains("t:" + TENANT + ":analytics:query:all:" + KY_Q4 + ":");
    long ttl =
        Long.parseLong(
            VALKEY
                .execInContainer(
                    "valkey-cli",
                    "TTL",
                    keys.lines().filter(k -> k.contains(KY_Q4)).findFirst().orElseThrow())
                .getStdout()
                .trim());
    assertThat(ttl).isBetween(1L, 66L);
  }

  @Test
  void unitFilterNarrowsButNeverWidensScope() {
    String bt = token(BI_THU, "leader", UNIT_TINH);
    Reply down =
        post(
            "/analytics/query",
            bt,
            Map.of("measures", List.of("okr.so_muc_tieu"), "unitId", UNIT_HUYEN),
            Map.of());
    assertThat(down.status()).isEqualTo(200);
    assertThat(last().orgPath()).isEqualTo("/t01/h01");
    Reply outside =
        post(
            "/analytics/query",
            bt,
            Map.of("measures", List.of("okr.so_muc_tieu"), "unitId", UNIT_KHAC),
            Map.of());
    assertThat(outside.status()).isEqualTo(403);
    assertThat(outside.json().path("code").asText()).isEqualTo("COMMON-403-OUT-OF-SCOPE");
    Reply up =
        post(
            "/analytics/query",
            token(TRUONG_HUYEN, "manager", UNIT_HUYEN),
            Map.of("measures", List.of("okr.so_muc_tieu"), "unitId", UNIT_TINH),
            Map.of());
    assertThat(up.status()).isEqualTo(403);
  }

  @Test
  void compareToPreviousPeriodUsesPriorQuarterOfSameFlow() {
    Reply r =
        post(
            "/analytics/query",
            token(BI_THU, "leader", UNIT_TINH),
            Map.of(
                "measures",
                List.of("okr.tien_do_o"),
                "periodId",
                KY_Q4,
                "compareToPreviousPeriod",
                true),
            Map.of());
    assertThat(r.status()).isEqualTo(200);
    List<FakeUpstream.CubeCall> calls = upstream.dataCalls();
    assertThat(calls.get(calls.size() - 2).query().path("filters").toString()).contains(KY_Q4);
    assertThat(calls.get(calls.size() - 1).query().path("filters").toString()).contains(KY_Q3);
    assertThat(r.json().path("columns").get(1).path("name").asText())
        .isEqualTo("okr.tien_do_o.previous");
    assertThat(r.json().path("rows").get(0).get(1).decimalValue()).isEqualByComparingTo("61.5");
  }

  @Test
  void semanticErrorsAndRolesAreRejectedWithContractProblems() {
    Reply unknown =
        post(
            "/analytics/query",
            token(BI_THU, "leader", UNIT_TINH),
            Map.of("measures", List.of("okr.khong_co")),
            Map.of());
    assertThat(unknown.status()).isEqualTo(422);
    assertThat(unknown.json().path("code").asText()).isEqualTo("ANALYTICS-422-INVALID-COMBINATION");
    Reply combo =
        post(
            "/analytics/query",
            token(BI_THU, "leader", UNIT_TINH),
            Map.of("measures", List.of("task.so_nhiem_vu"), "dimensions", List.of("okr.chu_the")),
            Map.of());
    assertThat(combo.status()).isEqualTo(422);
    Reply member =
        post(
            "/analytics/query",
            token(BI_THU, "member", UNIT_TINH),
            Map.of("measures", List.of("okr.tien_do_o")),
            Map.of());
    assertThat(member.status()).isEqualTo(403);
    Reply anonymous =
        post("/analytics/query", null, Map.of("measures", List.of("okr.tien_do_o")), Map.of());
    assertThat(anonymous.status()).isEqualTo(401);
    Reply inspectorModel =
        get("/analytics/semantic-model", token(BI_THU, "inspector", UNIT_TINH), Map.of());
    assertThat(inspectorModel.status()).isEqualTo(403);
    Reply model = get("/analytics/semantic-model", token(BI_THU, "leader", UNIT_TINH), Map.of());
    assertThat(model.status()).isEqualTo(200);
    assertThat(model.json().path("measures").toString()).doesNotContain("tenant_id");
  }

  @Test
  void dashboardLifecycleThroughConfigService() {
    String bt = token(BI_THU, "leader", UNIT_TINH);
    Map<String, Object> widget = new LinkedHashMap<>();
    widget.put("id", "w1");
    widget.put("x", 0);
    widget.put("y", 0);
    widget.put("w", 3);
    widget.put("h", 2);
    widget.put("measure", "okr.tien_do_o");
    widget.put("dimensions", List.of("org_unit.ten"));
    widget.put("chartType", "COT");
    Map<String, Object> input = Map.of("title", "Bảng của Bí thư", "layout", List.of(widget));
    Reply created =
        post(
            "/analytics/dashboards",
            bt,
            input,
            Map.of("Idempotency-Key", UUID.randomUUID().toString()));
    assertThat(created.status()).isEqualTo(201);
    String id = created.json().path("id").asText();
    assertThat(created.headers().get("location")).endsWith("/api/v1/analytics/dashboards/" + id);
    assertThat(created.headers().get("etag")).isEqualTo("\"0\"");
    assertThat(created.json().path("semanticModelVersion").asText()).isEqualTo("2026.4");
    Map<String, String> sent = upstream.configHeaders.get(upstream.configHeaders.size() - 1);
    assertThat(sent.get("x-acting-user")).isEqualTo(BI_THU);
    assertThat(sent).containsKey("idempotency-key");

    Reply dup = post("/analytics/dashboards", bt, input, Map.of());
    assertThat(dup.status()).isEqualTo(409);
    assertThat(dup.json().path("code").asText()).isEqualTo("ANALYTICS-409-DUPLICATE-TITLE");

    Map<String, Object> bad = new LinkedHashMap<>(widget);
    bad.put("measure", "task.so_nhiem_vu");
    bad.put("dimensions", List.of("okr.chu_the"));
    Reply invalid =
        post("/analytics/dashboards", bt, Map.of("title", "Sai", "layout", List.of(bad)), Map.of());
    assertThat(invalid.status()).isEqualTo(422);
    assertThat(invalid.json().path("code").asText()).isEqualTo("ANALYTICS-422-INVALID-COMBINATION");
    Reply tooMany =
        post(
            "/analytics/dashboards",
            bt,
            Map.of("title", "Nhiều", "layout", java.util.Collections.nCopies(21, widget)),
            Map.of());
    assertThat(tooMany.status()).isEqualTo(422);

    Reply withData = get("/analytics/dashboards/" + id, bt, Map.of("period", KY_Q4));
    assertThat(withData.status()).isEqualTo(200);
    assertThat(withData.json().path("data").path("widgets").get(0).path("widgetId").asText())
        .isEqualTo("w1");

    // Trưởng huyện chưa được chia sẻ: không thấy.
    String th = token(TRUONG_HUYEN, "manager", UNIT_HUYEN);
    assertThat(get("/analytics/dashboards/" + id, th, Map.of()).status()).isEqualTo(404);

    Reply noIfMatch = put("/analytics/dashboards/" + id, bt, input, Map.of());
    assertThat(noIfMatch.status()).isEqualTo(428);
    Reply updated =
        put(
            "/analytics/dashboards/" + id,
            bt,
            Map.of("title", "Bảng điều hành", "layout", List.of(widget)),
            Map.of("If-Match", "\"0\""));
    assertThat(updated.status()).isEqualTo(200);
    assertThat(updated.json().path("rowVersion").asLong()).isEqualTo(1);
    Reply stale = put("/analytics/dashboards/" + id, bt, input, Map.of("If-Match", "\"0\""));
    assertThat(stale.status()).isEqualTo(409);

    Reply shared =
        post(
            "/analytics/dashboards/" + id + "/shares",
            bt,
            Map.of("shareScope", "DON_VI", "sharedUnitIds", List.of(UNIT_TINH)),
            Map.of("If-Match", "\"1\""));
    assertThat(shared.status()).isEqualTo(200);
    assertThat(shared.json().path("layout").get(0).path("measure").asText())
        .isEqualTo("okr.tien_do_o");
    // Đơn vị cấp trên của huyện được chia sẻ: trưởng huyện thấy, số liệu trong nhánh của huyện.
    Reply seen = get("/analytics/dashboards/" + id, th, Map.of());
    assertThat(seen.status()).isEqualTo(200);
    assertThat(last().orgPath()).isEqualTo("/t01/h01");
    Reply list = get("/analytics/dashboards", th, Map.of("limit", "20"));
    assertThat(list.status()).isEqualTo(200);
    assertThat(list.json().path("items").get(0).path("id").asText()).isEqualTo(id);
    assertThat(get("/analytics/dashboards", th, Map.of("owner", "me")).json().path("items"))
        .isEmpty();
    Reply notOwner = put("/analytics/dashboards/" + id, th, input, Map.of("If-Match", "\"2\""));
    assertThat(notOwner.status()).isEqualTo(403);
  }

  @Test
  void embedTokenLocksViewerBranchIntoSupersetRls() throws Exception {
    String code = "BANG_DIEU_HANH_DON_VI";
    String embedded = "cda0f042-a44d-5667-a5ca-6ea85a36d3d1";
    Reply ok =
        post(
            "/analytics/embed-tokens",
            token(TRUONG_HUYEN, "manager", UNIT_TINH),
            Map.of("dashboardCode", code, "unitId", UNIT_HUYEN),
            Map.of());
    assertThat(ok.status()).isEqualTo(201);
    assertThat(ok.headers().get("cache-control")).isEqualTo("no-store");
    assertThat(ok.json().path("embedUrl").asText())
        .isEqualTo("https://bi.mission.example/embedded/" + embedded);
    assertThat(ok.json().path("lockedOrgPathPrefix").asText()).isEqualTo("/t01/h01");
    Instant expires = Instant.parse(ok.json().path("expiresAt").asText());
    assertThat(expires).isBetween(Instant.now().plusSeconds(240), Instant.now().plusSeconds(301));
    JsonNode grant = upstream.guestRequests.get(upstream.guestRequests.size() - 1);
    assertThat(ok.json().path("token").asText())
        .isEqualTo("token-khach-" + upstream.guestRequests.size());
    assertThat(grant.path("resources").get(0).path("type").asText()).isEqualTo("dashboard");
    assertThat(grant.path("resources").get(0).path("id").asText()).isEqualTo(embedded);
    String clause = grant.path("rls").get(0).path("clause").asText();
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("__user = '([A-Za-z0-9_.-]+)'").matcher(clause);
    assertThat(m.matches()).as(clause).isTrue();
    com.nimbusds.jwt.SignedJWT cubeJwt = com.nimbusds.jwt.SignedJWT.parse(m.group(1));
    assertThat(
            cubeJwt.verify(
                new com.nimbusds.jose.crypto.MACVerifier(
                    FakeUpstream.CUBE_SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
        .isTrue();
    assertThat(cubeJwt.getJWTClaimsSet().getStringClaim("tenant")).isEqualTo(TENANT);
    assertThat(cubeJwt.getJWTClaimsSet().getStringClaim("org_path")).isEqualTo("/t01/h01");
    assertThat(cubeJwt.getJWTClaimsSet().getExpirationTime().toInstant())
        .isBefore(Instant.now().plusSeconds(301));

    // Token truy cập của tài khoản dịch vụ được dùng lại giữa các lượt cấp.
    int logins = upstream.supersetLogins.get();
    assertThat(
            post(
                    "/analytics/embed-tokens",
                    token(BI_THU, "leader", UNIT_TINH),
                    Map.of("dashboardCode", code),
                    Map.of())
                .status())
        .isEqualTo(201);
    assertThat(upstream.supersetLogins.get()).isEqualTo(logins);

    Reply unknown =
        post(
            "/analytics/embed-tokens",
            token(BI_THU, "leader", UNIT_TINH),
            Map.of("dashboardCode", "KHONG_CO"),
            Map.of());
    assertThat(unknown.status()).isEqualTo(404);
    Reply outside =
        post(
            "/analytics/embed-tokens",
            token(BI_THU, "leader", UNIT_HUYEN),
            Map.of("dashboardCode", code, "unitId", UNIT_KHAC),
            Map.of());
    assertThat(outside.status()).isEqualTo(403);
    Reply member =
        post(
            "/analytics/embed-tokens",
            token(BI_THU, "member", UNIT_TINH),
            Map.of("dashboardCode", code),
            Map.of());
    assertThat(member.status()).isEqualTo(403);
  }

  private static FakeUpstream.CubeCall last() {
    List<FakeUpstream.CubeCall> calls = upstream.dataCalls();
    return calls.get(calls.size() - 1);
  }

  record Reply(int status, String body, JsonNode json, Map<String, String> headers) {}

  Reply post(String path, String token, Object body, Map<String, String> headers) {
    return call("POST", path, token, body, withKey(headers), Map.of());
  }

  Reply put(String path, String token, Object body, Map<String, String> headers) {
    return call("PUT", path, token, body, withKey(headers), Map.of());
  }

  private static Map<String, String> withKey(Map<String, String> headers) {
    Map<String, String> h = new LinkedHashMap<>(headers);
    h.putIfAbsent("Idempotency-Key", UUID.randomUUID().toString());
    return h;
  }

  Reply get(String path, String token, Map<String, String> params) {
    return call("GET", path, token, null, Map.of(), params);
  }

  private Reply call(
      String method,
      String path,
      String token,
      Object body,
      Map<String, String> headers,
      Map<String, String> params) {
    try {
      StringBuilder qs = new StringBuilder();
      params.forEach((k, v) -> qs.append(qs.isEmpty() ? "?" : "&").append(k).append('=').append(v));
      String text = body == null ? null : JSON.writeValueAsString(body);
      HttpRequest.Builder b =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path + qs))
              .method(
                  method,
                  text == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(text));
      if (text != null) {
        b.header("Content-Type", "application/json");
      }
      if (token != null) {
        b.header("Authorization", "Bearer " + token);
      }
      headers.forEach(b::header);
      HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
      Map<String, String> h = new LinkedHashMap<>();
      r.headers().map().forEach((k, v) -> h.put(k.toLowerCase(), v.get(0)));
      JsonNode node = r.body().isBlank() ? null : JSON.readTree(r.body());
      if (r.statusCode() != 401) {
        SimpleRequest.Builder rq = new SimpleRequest.Builder(Request.Method.valueOf(method), path);
        params.forEach(rq::withQueryParam);
        headers.forEach(rq::withHeader);
        if (text != null) {
          rq.withContentType("application/json").withBody(text);
        }
        SimpleResponse.Builder rs =
            SimpleResponse.Builder.status(r.statusCode()).withBody(r.body());
        h.forEach(rs::withHeader);
        rs.withContentType(h.getOrDefault("content-type", "application/json"));
        // Ca lỗi có thể cố ý gửi yêu cầu sai hợp đồng (thiếu If-Match, 21 widget): chỉ so khớp phản
        // hồi.
        ValidationReport report =
            r.statusCode() >= 400
                ? CONTRACT.validateResponse(path, Request.Method.valueOf(method), rs.build())
                : CONTRACT.validate(rq.build(), rs.build());
        assertThat(report.getMessages()).as(report.toString()).isEmpty();
      }
      return new Reply(r.statusCode(), r.body(), node, h);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static String token(String user, String role, String unit) {
    try {
      JWTClaimsSet.Builder c =
          new JWTClaimsSet.Builder()
              .subject(user)
              .issuer("https://idp.test/realms/mission")
              .claim("tenant_id", TENANT)
              .claim("realm_access", Map.of("roles", List.of(role)))
              .issueTime(new Date())
              .expirationTime(Date.from(Instant.now().plusSeconds(300)));
      if (unit != null) {
        c.claim("unit_id", unit);
      }
      SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), c.build());
      jwt.sign(new RSASSASigner(KEYS.getPrivate()));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static void ch(String sql) throws Exception {
    HttpRequest req =
        HttpRequest.newBuilder(URI.create(chUrl() + "/"))
            .header(
                "Authorization",
                "Basic "
                    + Base64.getEncoder()
                        .encodeToString(
                            ("default:" + CH_PASSWORD).getBytes(StandardCharsets.UTF_8)))
            .POST(HttpRequest.BodyPublishers.ofString(sql))
            .build();
    HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
  }

  private static KeyPair keys() {
    try {
      KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
      g.initialize(2048);
      return g.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
