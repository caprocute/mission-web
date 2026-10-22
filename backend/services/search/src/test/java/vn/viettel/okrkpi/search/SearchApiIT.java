package vn.viettel.okrkpi.search;

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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.testcontainers.utility.MountableFile;
import vn.viettel.okrkpi.search.domain.DocType;
import vn.viettel.okrkpi.search.domain.IndexCommand;
import vn.viettel.okrkpi.search.domain.TenancyModel;
import vn.viettel.okrkpi.search.infrastructure.OpenSearchHttp;
import vn.viettel.okrkpi.search.infrastructure.OpenSearchIndex;

/**
 * DV-16-02 trên OpenSearch 2.19 và OPA 1.4 thật nạp gói chính sách {@code backend/opa}: kết quả
 * kiểm quyền lại từng mục (phạm vi B của đảng viên, Đv của trưởng đơn vị), cách ly tenant dù gửi
 * đơn vị của tenant khác, đoạn trích không HTML, không có tổng số, phân trang con trỏ, lỗi tham số
 * và quyền, so khớp phản hồi với {@code platform.yaml}, p95 thời gian phản hồi.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "mission.search.listen=false",
      "mission.search.replicas=0",
      "mission.search.payload-format=avro-binary",
      "okrkpi.opa.decision-cache-ttl=0s"
    })
class SearchApiIT {

  static final String TENANT_A = "0192f000-0000-7000-8000-00000000a001";
  static final String TENANT_B = "0192f000-0000-7000-8000-00000000b002";
  static final String UNIT_X = "0192f000-0000-7000-8000-0000000000e1";
  static final String UNIT_Y = "0192f000-0000-7000-8000-0000000000e2";
  static final String UNIT_B = "0192f000-0000-7000-8000-0000000000e3";
  static final String MEMBER = "0192f000-0000-7000-8000-000000000101";
  static final String OTHER = "0192f000-0000-7000-8000-000000000102";
  static final String MANAGER = "0192f000-0000-7000-8000-000000000103";
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

  @Container static final GenericContainer<?> OPENSEARCH = OpenSearchTestContainer.create();

  @Container
  static final GenericContainer<?> OPA =
      new GenericContainer<>("mirror.gcr.io/openpolicyagent/opa:1.4.0")
          .withCopyFileToContainer(
              MountableFile.forHostPath(Path.of("../../opa/okrkpi").toAbsolutePath()),
              "/policy/okrkpi")
          .withCommand(
              "run", "--server", "--addr", "0.0.0.0:8181", "--disable-telemetry", "/policy")
          .withExposedPorts(8181)
          .waitingFor(Wait.forHttp("/health").forPort(8181));

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("mission.search.opensearch-url", SearchApiIT::osUrl);
    r.add("okrkpi.opa.base-url", () -> "http://" + OPA.getHost() + ":" + OPA.getMappedPort(8181));
  }

  static String osUrl() {
    return "http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200);
  }

  @TestConfiguration
  static class Jwt {
    @Bean
    JwtDecoder testJwtDecoder() {
      return NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
    }
  }

  @LocalServerPort int port;

  @Autowired OpenSearchIndex index;

  static final List<UUID> MEMBER_TASKS = new ArrayList<>();
  static UUID hiddenTask;
  static UUID unitXTask;
  static UUID unitYTask;
  static UUID restricted;
  static UUID sourceDoc;

  private static boolean seeded;

  @BeforeAll
  static void org() throws Exception {
    for (String t : List.of(TENANT_A, TENANT_B)) {
      String unit = t.equals(TENANT_A) ? UNIT_X : UNIT_B;
      String body =
          JSON.writeValueAsString(
              Map.of(
                  "subtree",
                  Map.of(unit, List.of(unit), UNIT_Y, List.of(UNIT_Y)),
                  "managed_units",
                  Map.of()));
      HttpResponse<String> r =
          HTTP.send(
              HttpRequest.newBuilder(
                      URI.create(
                          "http://"
                              + OPA.getHost()
                              + ":"
                              + OPA.getMappedPort(8181)
                              + "/v1/data/org/"
                              + t))
                  .header("Content-Type", "application/json")
                  .PUT(HttpRequest.BodyPublishers.ofString(body))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(r.statusCode()).isEqualTo(204);
    }
  }

  private synchronized void seed() {
    if (seeded) {
      return;
    }
    index.ensureTenant(TENANT_A, TenancyModel.POOL);
    index.ensureTenant(TENANT_B, TenancyModel.POOL);
    List<IndexCommand> cmds = new ArrayList<>();
    int seq = 0;
    // 30 nhiệm vụ đảng viên tham gia, xen kẽ 30 nhiệm vụ người khác cùng tiêu đề (bị loại khi kiểm
    // quyền).
    for (int i = 0; i < 30; i++) {
      UUID mine = UUID.randomUUID();
      MEMBER_TASKS.add(mine);
      cmds.add(
          task(
              seq++, TENANT_A, mine, "Kiểm điểm chi bộ cuối năm đợt " + i, OTHER, List.of(MEMBER)));
      cmds.add(
          task(
              seq++,
              TENANT_A,
              UUID.randomUUID(),
              "Kiểm điểm chi bộ cuối năm đợt " + i,
              OTHER,
              List.of()));
    }
    hiddenTask = UUID.randomUUID();
    cmds.add(
        task(seq++, TENANT_A, hiddenTask, "Báo cáo giáo dục chính trị riêng", OTHER, List.of()));
    unitXTask = UUID.randomUUID();
    cmds.add(
        task(
            seq++, TENANT_A, unitXTask, "Kế hoạch giáo dục chính trị đơn vị X", UNIT_X, List.of()));
    unitYTask = UUID.randomUUID();
    cmds.add(
        task(
            seq++, TENANT_A, unitYTask, "Kế hoạch giáo dục chính trị đơn vị Y", UNIT_Y, List.of()));
    restricted = UUID.randomUUID();
    Map<String, Object> f = new HashMap<>();
    f.put("title", null);
    f.put("is_restricted", true);
    f.put("lead_assignee_id", UNIT_X);
    cmds.add(
        new IndexCommand(
            seq++,
            TENANT_A,
            DocType.TASK,
            restricted.toString(),
            IndexCommand.Mode.MERGE,
            "assign",
            1,
            f,
            Instant.now()));
    sourceDoc = UUID.randomUUID();
    cmds.add(
        new IndexCommand(
            seq++,
            TENANT_A,
            DocType.SOURCE_DOC,
            sourceDoc.toString(),
            IndexCommand.Mode.REPLACE,
            null,
            1,
            Map.of(
                "title",
                "Chỉ thị về giáo dục chính trị",
                "summary",
                "Chỉ thị về giáo dục chính trị",
                "doc_number",
                "12-CT/TW"),
            Instant.now()));
    cmds.add(
        task(
            seq++,
            TENANT_B,
            UUID.randomUUID(),
            "Kế hoạch giáo dục chính trị tenant B",
            UNIT_B,
            List.of(MEMBER)));
    assertThat(index.write(cmds)).isEmpty();
    new OpenSearchHttp(osUrl(), null, null, Duration.ofSeconds(10), JSON)
        .send("POST", "/_refresh", null);
    seeded = true;
  }

  private static IndexCommand task(
      int seq, String tenant, UUID id, String title, String lead, List<String> assignees) {
    Map<String, Object> f = new HashMap<>();
    f.put("title", title);
    f.put("lead_assignee_id", lead);
    f.put("assignee_ids", assignees);
    f.put("is_restricted", false);
    return new IndexCommand(
        seq,
        tenant,
        DocType.TASK,
        id.toString(),
        IndexCommand.Mode.MERGE,
        "assign",
        1,
        f,
        Instant.now());
  }

  static String token(String user, String tenant, String role, String unit) {
    try {
      JWTClaimsSet.Builder c =
          new JWTClaimsSet.Builder()
              .subject(user)
              .issuer("https://idp.test/realms/mission")
              .claim("tenant_id", tenant)
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

  record Reply(int status, String body, JsonNode json) {}

  Reply get(String token, Map<String, String> params) {
    seed();
    StringBuilder qs = new StringBuilder();
    params.forEach(
        (k, v) ->
            qs.append(qs.isEmpty() ? "?" : "&")
                .append(k)
                .append('=')
                .append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
    try {
      HttpRequest.Builder b =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/search" + qs));
      if (token != null) {
        b.header("Authorization", "Bearer " + token);
      }
      HttpResponse<String> r = HTTP.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
      JsonNode node = r.body().isBlank() ? null : JSON.readTree(r.body());
      if (r.statusCode() == 200) {
        SimpleRequest.Builder rq = new SimpleRequest.Builder(Request.Method.GET, "/search");
        params.forEach(rq::withQueryParam);
        ValidationReport report =
            CONTRACT.validate(
                rq.build(),
                SimpleResponse.Builder.ok()
                    .withContentType("application/json")
                    .withBody(r.body())
                    .build());
        assertThat(report.getMessages()).as(report.toString()).isEmpty();
      }
      return new Reply(r.statusCode(), r.body(), node);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static Set<String> ids(Reply r) {
    Set<String> out = new HashSet<>();
    r.json().path("items").forEach(i -> out.add(i.path("id").asText()));
    return out;
  }

  private static String fold(String s) {
    return Normalizer.normalize(s, Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .replace('đ', 'd')
        .toLowerCase();
  }

  @Test
  void dangVienChiThayNhiemVuMinhThamGiaVaPhanTrangConTro() {
    String t = token(MEMBER, TENANT_A, "member", null);
    Set<String> seen = new HashSet<>();
    String cursor = null;
    int pages = 0;
    do {
      Map<String, String> p = new HashMap<>(Map.of("q", "kiem diem chi bo", "limit", "10"));
      if (cursor != null) {
        p.put("cursor", cursor);
      }
      Reply r = get(t, p);
      assertThat(r.status()).as(r.body()).isEqualTo(200);
      assertThat(r.json().has("nextCursor")).isTrue();
      assertThat(r.json().has("total")).isFalse();
      assertThat(r.body()).doesNotContain("total", "<", "", "");
      Set<String> page = ids(r);
      // Trang cuối có thể rỗng: lô trước đầy nên còn con trỏ, nhưng ứng viên còn lại đều bị loại
      // khi kiểm quyền.
      assertThat(page).allSatisfy(id -> assertThat(seen).doesNotContain(id));
      seen.addAll(page);
      cursor = r.json().path("nextCursor").isNull() ? null : r.json().path("nextCursor").asText();
      pages++;
    } while (cursor != null && pages < 10);
    Set<String> expected = new HashSet<>();
    MEMBER_TASKS.forEach(u -> expected.add(u.toString()));
    assertThat(seen).isEqualTo(expected);
    assertThat(pages).isBetween(3, 5);

    // Không thấy nhiệm vụ chỉ của người khác dù khớp từ khoá; không thấy văn bản chỉ đạo (phạm vi
    // Đv của trưởng đơn vị).
    Reply r = get(t, Map.of("q", "giao duc chinh tri"));
    assertThat(ids(r))
        .doesNotContain(hiddenTask.toString(), unitXTask.toString(), sourceDoc.toString());
  }

  @Test
  void doanTrichVanBanThuanVaViTriKhop() {
    Reply r = get(token(MANAGER, TENANT_A, "manager", UNIT_X), Map.of("q", "GIÁO DỤC chính trị"));
    assertThat(r.status()).isEqualTo(200);
    assertThat(ids(r)).containsExactly(unitXTask.toString());
    JsonNode item = r.json().path("items").get(0);
    assertThat(item.path("type").asText()).isEqualTo("TASK");
    assertThat(item.path("deepLink").asText()).isEqualTo("/nhiem-vu/" + unitXTask);
    String snippet = item.path("snippet").asText();
    assertThat(snippet).isEqualTo("Kế hoạch giáo dục chính trị đơn vị X");
    JsonNode h = item.path("highlights").get(0);
    String hit =
        snippet.substring(
            h.path("start").asInt(), h.path("start").asInt() + h.path("length").asInt());
    assertThat(fold(hit)).isEqualTo("giao duc chinh tri");
    // Nhiệm vụ mật (không tiêu đề) và văn bản chỉ đạo chưa có đơn vị nhận không bao giờ được trả.
    assertThat(
            ids(get(token(MANAGER, TENANT_A, "manager", UNIT_X), Map.of("q", "chi thi giao duc"))))
        .doesNotContain(restricted.toString(), sourceDoc.toString());
  }

  @Test
  void tenantKhacKhongThayDuGuiDonViCuaHo() {
    Reply r =
        get(
            token(MEMBER, TENANT_A, "member", null),
            Map.of("q", "giao duc chinh tri", "unit", UNIT_B));
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.json().path("items")).isEmpty();
    Reply b =
        get(
            token(MEMBER, TENANT_B, "member", null),
            Map.of("q", "giao duc chinh tri", "unit", UNIT_B));
    assertThat(b.json().path("items")).hasSize(1);
    assertThat(b.json().path("items").get(0).path("title").asText()).endsWith("tenant B");
  }

  @Test
  void loiThamSoQuyenVaConTro() {
    String t = token(MEMBER, TENANT_A, "member", null);
    assertThat(get(t, Map.of("q", "kiem diem", "limit", "201")).status()).isEqualTo(400);
    assertThat(get(t, Map.of("q", "a")).status()).isEqualTo(400);
    assertThat(get(t, Map.of("q", "kiem diem", "cursor", "khong-hop-le")).status()).isEqualTo(400);
    assertThat(get(null, Map.of("q", "kiem diem")).status()).isEqualTo(401);
    Reply denied = get(token(MEMBER, TENANT_A, "tenant_admin", null), Map.of("q", "kiem diem"));
    assertThat(denied.status()).isEqualTo(403);
    assertThat(denied.body()).contains("COMMON-403-OUT-OF-SCOPE");
    // Con trỏ của đảng viên không dùng được cho người khác hay từ khoá khác.
    String cursor =
        get(t, Map.of("q", "kiem diem chi bo", "limit", "5")).json().path("nextCursor").asText();
    assertThat(
            get(
                    token(OTHER, TENANT_A, "member", null),
                    Map.of("q", "kiem diem chi bo", "limit", "5", "cursor", cursor))
                .status())
        .isEqualTo(400);
    assertThat(get(t, Map.of("q", "kiem diem", "limit", "5", "cursor", cursor)).status())
        .isEqualTo(400);
  }

  @Test
  void thoiGianPhanHoiP95() {
    String t = token(MEMBER, TENANT_A, "member", null);
    get(t, Map.of("q", "kiem diem chi bo"));
    List<Long> ms = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      long s = System.nanoTime();
      assertThat(get(t, Map.of("q", "kiem diem chi bo", "limit", "20")).status()).isEqualTo(200);
      ms.add((System.nanoTime() - s) / 1_000_000);
    }
    ms.sort(Long::compare);
    long p95 = ms.get(94);
    org.slf4j.LoggerFactory.getLogger(SearchApiIT.class)
        .info(
            "DV-16-02 p95 {} ms, p50 {} ms (100 lần, 20 kết quả "
                + "mỗi trang, 30 ứng viên kiểm quyền OPA)",
            p95,
            ms.get(49));
    assertThat(p95).isLessThan(1000);
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
