package vn.viettel.okrkpi.okr.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.swagger.v3.oas.models.PathItem;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.MissionCoreApplication;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.shared.infrastructure.identity.KeycloakAdminClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Kiểm thử tích hợp CORE-01-10 trên PostgreSQL thật, integration-gateway thay bằng máy chủ HTTP giả
 * (YC-PT-13): lấy số liệu nguồn khớp hợp đồng, nguồn chậm trả 502 trong 3,2 giây, ghi đè thiếu lý
 * do trả MSG-03-04, có lý do thì lưu đủ giá trị nguồn, thời điểm lấy và lý do, giá trị nguồn bị sửa
 * ở client không qua được BR-03-04, nguồn lỗi thì check-in là nhập tay.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OkrSourceValueApiIT extends ContractConformanceTest {

  static final UUID OWNER = UUID.fromString("01927a08-0000-7000-8000-0000000001ac");
  static final UUID VERIFIER = UUID.fromString("01927a08-0000-7000-8000-0000000001ad");
  static final String CSDL = "CSDL đảng viên";
  static final String SOURCE_PATH = "/okr/key-results/{id}/source-value";

  /** Hành vi của nguồn giả: {@code ok}, {@code slow} (5 giây) hoặc {@code error}. */
  static final AtomicReference<String> MODE = new AtomicReference<>("ok");

  static final AtomicReference<String> VALUE = new AtomicReference<>("2.6");
  static final AtomicInteger HITS = new AtomicInteger();
  static final HttpServer GATEWAY = gateway();

  private static HttpServer gateway() {
    try {
      HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      s.setExecutor(Executors.newCachedThreadPool());
      s.createContext(
          "/internal/v1/integ/kr-source-values",
          ex -> {
            HITS.incrementAndGet();
            int code = 200;
            String body =
                "{\"value\":"
                    + VALUE.get()
                    + ",\"fetchedAt\":\"2026-10-22T08:00:00+07:00\",\"dataSource\":\""
                    + CSDL
                    + "\"}";
            if ("slow".equals(MODE.get())) {
              try {
                Thread.sleep(5_000);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            } else if ("error".equals(MODE.get())) {
              code = 503;
              body = "{}";
            }
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(code, b.length);
            ex.getResponseBody().write(b);
            ex.close();
          });
      s.start();
      return s;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
    registry.add(
        "mission.okr.source.base-url", () -> "http://127.0.0.1:" + GATEWAY.getAddress().getPort());
    // Không để các lần thử nghiệm lỗi làm mở mạch giữa các ca kiểm thử.
    registry.add(
        "resilience4j.circuitbreaker.instances.integration-gateway.minimumNumberOfCalls",
        () -> "1000");
  }

  /** Thay bean {@code testOkrCaller} của {@code OkrReadApiIT} cùng gói. */
  @MockitoBean(name = "testOkrCaller")
  private OkrCaller caller;

  @MockitoBean private KeycloakAdminClient keycloak;

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;

  @Autowired
  @Qualifier("okrDataSource") private DataSource okrDataSource;

  @Autowired
  @Qualifier("okrTransactionManager") private PlatformTransactionManager okrTm;

  @Autowired
  @Qualifier("configDataSource") private DataSource configDataSource;

  @Autowired
  @Qualifier("configTransactionManager") private PlatformTransactionManager configTm;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID period = UUID.randomUUID();
  private final AtomicInteger codes = new AtomicInteger();

  @Override
  protected ContractModule contractModule() {
    return ContractModule.OKR;
  }

  @BeforeEach
  void caller() {
    MODE.set("ok");
    VALUE.set("2.6");
    when(caller.currentUserId()).thenReturn(Optional.of(OWNER));
    when(caller.currentUnitId()).thenReturn(Optional.empty());
    when(keycloak.serviceToken()).thenReturn("svc-token");
  }

  @BeforeAll
  void seed() throws Exception {
    JdbcClient config = JdbcClient.create(configDataSource);
    TenantContext.run(
        tenant,
        () ->
            new TransactionTemplate(configTm)
                .executeWithoutResult(
                    s ->
                        config
                            .sql(
                                "INSERT INTO config.period (id, code, name, kind, flow,"
                                    + " start_date, end_date, period_bucket, status, created_by,"
                                    + " updated_by) VALUES (?, ?, 'Quý III năm 2027', 'QUY',"
                                    + " 'NHA_NUOC', DATE '2027-07-01', DATE '2027-09-30', 20273,"
                                    + " 'MO', ?, ?)")
                            .params(period, "Q-" + period.toString().substring(0, 8), OWNER, OWNER)
                            .update()));
    inTenant(
        jdbc -> {
          for (String table : List.of("objective", "key_result", "kr_checkin")) {
            jdbc.sql("SELECT okr.fn_create_period_partitions(?::regclass, ?)")
                .params("okr." + table, 20273)
                .query()
                .listOfRows();
          }
          return null;
        });
  }

  @AfterAll
  void stopGateway() {
    GATEWAY.stop(0);
  }

  @Test
  void laySoLieuNguonKhopHopDong() throws Exception {
    UUID kr = keyResult(CSDL);
    MvcResult r = perform(get("/api/v1/okr/key-results/" + kr + "/source-value"));
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    JsonNode b = body(r);
    assertThat(b.get("value").decimalValue()).isEqualByComparingTo("2.6");
    assertThat(b.get("dataSource").asText()).isEqualTo(CSDL);
    assertResponseConforms(r, SOURCE_PATH, PathItem.HttpMethod.GET);
    // KR không gắn nguồn thì không có số liệu nguồn.
    UUID manual = keyResult(null);
    assertThat(
            perform(get("/api/v1/okr/key-results/" + manual + "/source-value"))
                .getResponse()
                .getStatus())
        .isEqualTo(404);
  }

  @Test
  void nguonCham5GiayTra502Trong32Giay() throws Exception {
    UUID kr = keyResult(CSDL);
    MODE.set("slow");
    long start = System.nanoTime();
    MvcResult r = perform(get("/api/v1/okr/key-results/" + kr + "/source-value"));
    Duration took = Duration.ofNanos(System.nanoTime() - start);
    assertThat(r.getResponse().getStatus()).isEqualTo(502);
    assertThat(took).isLessThan(Duration.ofMillis(3_200));
    JsonNode problem = body(r);
    assertThat(problem.get("code").asText()).isEqualTo("OKR-502-SOURCE-UNAVAILABLE");
    assertThat(problem.get("detail").asText())
        .isEqualTo("Không lấy được số liệu từ CSDL đảng viên. Đồng chí có thể nhập tay.");
    assertResponseConforms(r, SOURCE_PATH, PathItem.HttpMethod.GET);
  }

  @Test
  void ghiDeThieuLyDoTra422CoLyDoThiLuuDu() throws Exception {
    UUID kr = keyResult(CSDL);
    assertThat(
            perform(get("/api/v1/okr/key-results/" + kr + "/source-value"))
                .getResponse()
                .getStatus())
        .isEqualTo(200);
    MvcResult missing = checkin(kr, submit("3", "2.6", null));
    assertThat(missing.getResponse().getStatus()).isEqualTo(422);
    JsonNode problem = body(missing);
    assertThat(problem.get("code").asText()).isEqualTo("OKR-422-OVERRIDE-REASON-REQUIRED");
    assertThat(problem.get("detail").asText())
        .isEqualTo("Vui lòng ghi lý do khi thay đổi số liệu lấy từ CSDL đảng viên.");

    MvcResult ok = checkin(kr, submit("3", "2.6", "Bổ sung 1 đảng viên kết nạp chưa đồng bộ"));
    assertThat(ok.getResponse().getStatus()).as(text(ok)).isEqualTo(201);
    Map<String, Object> row = stored(kr);
    assertThat((BigDecimal) row.get("source_value")).isEqualByComparingTo("2.6");
    assertThat(row.get("source_fetched_at")).isNotNull();
    assertThat(row.get("override_reason")).isEqualTo("Bổ sung 1 đảng viên kết nạp chưa đồng bộ");
    assertThat((BigDecimal) row.get("value")).isEqualByComparingTo("3");
  }

  @Test
  void giaTriNguonBiSuaOClientVanPhaiCoLyDo() throws Exception {
    UUID kr = keyResult(CSDL);
    perform(get("/api/v1/okr/key-results/" + kr + "/source-value"));
    int hits = HITS.get();
    // Client tự đặt giá trị nguồn bằng giá trị nhập để né lý do.
    MvcResult forged = checkin(kr, submit("9", "9", null));
    assertThat(forged.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(forged).get("code").asText()).isEqualTo("OKR-422-OVERRIDE-REASON-REQUIRED");
    // Giá trị lấy trong đệm, không gọi lại nguồn.
    assertThat(HITS.get()).isEqualTo(hits);
    MvcResult same = checkin(kr, submit("2.6", "2.6", null));
    assertThat(same.getResponse().getStatus()).as(text(same)).isEqualTo(201);
    assertThat((BigDecimal) stored(kr).get("source_value")).isEqualByComparingTo("2.6");
  }

  @Test
  void nguonLoiThiCheckInLaNhapTay() throws Exception {
    UUID kr = keyResult(CSDL);
    MODE.set("error");
    MvcResult r = checkin(kr, submit("3", "2.6", null));
    assertThat(r.getResponse().getStatus()).as(text(r)).isEqualTo(201);
    Map<String, Object> row = stored(kr);
    assertThat(row.get("source_value")).isNull();
    assertThat(row.get("source_fetched_at")).isNull();
    assertThat(
            body(r).at("/checkin/sourceValue").isMissingNode()
                || body(r).at("/checkin/sourceValue").isNull())
        .isTrue();
  }

  // ---------------------------------------------------------------- tiện ích

  private UUID keyResult(String dataSource) throws Exception {
    UUID objective = UUID.randomUUID();
    UUID kr = UUID.randomUUID();
    String code = "O" + (300 + codes.incrementAndGet());
    inTenant(
        jdbc -> {
          jdbc.sql(
                  "INSERT INTO okr.objective (id, period_id, period_bucket, owner_type,"
                      + " owner_unit_id, code, title, tier, status, created_by, updated_by)"
                      + " VALUES (?, ?, 20273, 'TAP_THE', ?, ?, 'Mục tiêu kiểm thử số liệu"
                      + " nguồn', 1, 'DA_DUYET', ?, ?)")
              .params(objective, period, UUID.randomUUID(), code, OWNER, OWNER)
              .update();
          jdbc.sql(
                  "INSERT INTO okr.key_result (id, period_id, period_bucket, objective_id, code,"
                      + " title, metric_type, unit, baseline_value, target_value, direction,"
                      + " weight, due_date, verifier_person_id, checkin_frequency, status,"
                      + " data_source, created_by, updated_by) VALUES (?, ?, 20273, ?, ?,"
                      + " 'Số đảng viên kết nạp', 'SO_LUONG', 'đảng viên', 0, 10, 'TANG', 1,"
                      + " DATE '2027-09-30', ?, 'TUAN', 'DA_DUYET', ?, ?, ?)")
              .params(
                  kr,
                  period,
                  objective,
                  "KR " + code.substring(1) + ".1",
                  VERIFIER,
                  dataSource,
                  OWNER,
                  OWNER)
              .update();
          return null;
        });
    return kr;
  }

  private ObjectNode submit(String value, String sourceValue, String reason) {
    ObjectNode b = json.createObjectNode();
    b.put("status", "DA_GUI");
    b.put("value", new BigDecimal(value));
    b.put("confidence", "CAO");
    b.put("channel", "WEB");
    if (sourceValue != null) {
      b.put("sourceValue", new BigDecimal(sourceValue));
      b.put("sourceFetchedAt", "2026-10-22T08:00:00+07:00");
    }
    if (reason != null) {
      b.put("overrideReason", reason);
    }
    return b;
  }

  private MvcResult checkin(UUID kr, ObjectNode body) throws Exception {
    return perform(
        post("/api/v1/okr/key-results/" + kr + "/checkins")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body)));
  }

  private Map<String, Object> stored(UUID kr) throws Exception {
    return inTenant(
        jdbc ->
            jdbc.sql(
                    "SELECT value, source_value, source_fetched_at, override_reason FROM"
                        + " okr.kr_checkin WHERE key_result_id = ? AND status = 'DA_GUI'"
                        + " ORDER BY checkin_at DESC LIMIT 1")
                .param(kr)
                .query()
                .singleRow());
  }

  private <T> T inTenant(java.util.function.Function<JdbcClient, T> work) throws Exception {
    JdbcClient jdbc = JdbcClient.create(okrDataSource);
    return TenantContext.call(
        tenant, () -> new TransactionTemplate(okrTm).execute(s -> work.apply(jdbc)));
  }

  private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
    return TenantContext.call(tenant, () -> mvc.perform(request).andReturn());
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private static String text(MvcResult r) throws Exception {
    return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
  }
}
