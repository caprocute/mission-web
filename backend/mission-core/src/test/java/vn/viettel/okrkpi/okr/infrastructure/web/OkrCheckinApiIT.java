package vn.viettel.okrkpi.okr.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.models.PathItem;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
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
import vn.viettel.okrkpi.okr.domain.ProgressCalculator;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Kiểm thử tích hợp CORE-01-08 trên PostgreSQL thật: gửi check-in idempotent, chặn theo BR-03-01,
 * BR-03-02, BR-03-05, tính lại tiến độ KR và mục tiêu trong cùng giao dịch, gửi đồng thời không mất
 * cập nhật, bản nháp chỉ người tạo thấy, danh sách KR cần check-in, p95 phía máy chủ ≤ 200 ms.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OkrCheckinApiIT extends ContractConformanceTest {

  static final UUID OWNER = UUID.fromString("01927a08-0000-7000-8000-0000000000ac");
  static final UUID OTHER = UUID.fromString("01927a08-0000-7000-8000-0000000000ae");
  static final UUID VERIFIER = UUID.fromString("01927a08-0000-7000-8000-0000000000ad");
  static final String PATH = "/okr/key-results/{id}/checkins";

  /** Người gọi theo luồng kiểm thử (mặc định {@link #OWNER}). */
  static final InheritableThreadLocal<UUID> CALLER =
      new InheritableThreadLocal<>() {
        @Override
        protected UUID initialValue() {
          return OWNER;
        }
      };

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  /** Thay bean {@code testOkrCaller} của {@code OkrReadApiIT} cùng gói. */
  @MockitoBean(name = "testOkrCaller")
  private OkrCaller caller;

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
  private final UUID locked = UUID.randomUUID();
  private final AtomicInteger codes = new AtomicInteger();

  @Override
  protected ContractModule contractModule() {
    return ContractModule.OKR;
  }

  @BeforeEach
  void caller() {
    CALLER.set(OWNER);
    when(caller.currentUserId()).thenAnswer(inv -> Optional.of(CALLER.get()));
    when(caller.currentUnitId()).thenReturn(Optional.empty());
  }

  @BeforeAll
  void seed() throws Exception {
    insertPeriod(period, "Quý I năm 2027", "2027-01-01", "2027-03-31", 20271, "MO");
    insertPeriod(locked, "Quý II năm 2027", "2027-04-01", "2027-06-30", 20272, "DA_KHOA");
    inTenant(
        jdbc -> {
          for (int bucket : List.of(20271, 20272)) {
            for (String table : List.of("objective", "key_result", "kr_checkin")) {
              jdbc.sql("SELECT okr.fn_create_period_partitions(?::regclass, ?)")
                  .params("okr." + table, bucket)
                  .query()
                  .listOfRows();
            }
          }
          return null;
        });
  }

  @Test
  void guiHaiLanCungKhoaChiTaoMotDongVaKhopHopDong() throws Exception {
    Okr okr = objective("DA_DUYET", period, 20271);
    String key = UUID.randomUUID().toString();
    MvcResult first = checkin(okr.kr1(), key, submit("4", "CAO"));
    MvcResult second = checkin(okr.kr1(), key, submit("4", "CAO"));
    assertThat(first.getResponse().getStatus()).isEqualTo(201);
    assertThat(second.getResponse().getStatus()).isEqualTo(201);
    assertThat(body(second).at("/checkin/id")).isEqualTo(body(first).at("/checkin/id"));
    assertThat(countSubmitted(okr.kr1())).isEqualTo(1);
    assertThat(body(first).at("/checkin/status").asText()).isEqualTo("DA_GUI");
    assertResponseConforms(first, PATH, PathItem.HttpMethod.POST);
  }

  @Test
  void cungKhoaKhacNoiDungTra422() throws Exception {
    Okr okr = objective("DA_DUYET", period, 20271);
    String key = UUID.randomUUID().toString();
    assertThat(checkin(okr.kr1(), key, submit("4", "CAO")).getResponse().getStatus())
        .isEqualTo(201);
    MvcResult r = checkin(okr.kr1(), key, submit("5", "CAO"));
    assertThat(r.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(r).get("code").asText()).isEqualTo("COMMON-422-IDEMPOTENCY-MISMATCH");
    assertThat(countSubmitted(okr.kr1())).isEqualTo(1);
  }

  @Test
  void mucTieuChoDuyetBiTuChoi() throws Exception {
    Okr okr = objective("CHO_DUYET", period, 20271);
    MvcResult r = checkin(okr.kr1(), UUID.randomUUID().toString(), submit("4", "CAO"));
    assertThat(r.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(r).get("code").asText()).isEqualTo("OKR-409-OBJECTIVE-NOT-APPROVED");
    assertThat(countSubmitted(okr.kr1())).isZero();
  }

  @Test
  void kyDaKhoaTra409VoiMsg0305() throws Exception {
    Okr okr = objective("DA_DUYET", locked, 20272);
    MvcResult r = checkin(okr.kr1(), UUID.randomUUID().toString(), submit("4", "CAO"));
    assertThat(r.getResponse().getStatus()).isEqualTo(409);
    JsonNode problem = body(r);
    assertThat(problem.get("code").asText()).isEqualTo("OKR-409-PERIOD-LOCKED");
    assertThat(problem.get("detail").asText())
        .isEqualTo("Kỳ Quý II năm 2027 đã khoá. Check-in lưu trên máy chưa được gửi.");
  }

  @Test
  void sauKhiGuiGiaTriVaTienDoKhopCongThuc() throws Exception {
    Okr okr = objective("DA_DUYET", period, 20271);
    MvcResult r = checkin(okr.kr1(), UUID.randomUUID().toString(), submit("4", "TRUNG_BINH"));
    JsonNode b = body(r);
    assertThat(b.at("/keyResult/currentValue").decimalValue()).isEqualByComparingTo("4");
    assertThat(b.at("/keyResult/progress").decimalValue()).isEqualByComparingTo("0.4");
    assertThat(b.at("/keyResult/confidence").asText()).isEqualTo("TRUNG_BINH");
    // KR 2 chưa check-in, tiến độ 0; trọng số 0,5 và 0,5 nên tiến độ mục tiêu 0,2.
    assertThat(b.at("/objective/progress").decimalValue()).isEqualByComparingTo("0.2");
    assertThat(b.at("/objective/code").asText()).isEqualTo(okr.code());
    List<BigDecimal> stored =
        inTenant(
            jdbc ->
                List.of(
                    jdbc.sql("SELECT current_value FROM okr.key_result WHERE id = ?")
                        .param(okr.kr1())
                        .query(BigDecimal.class)
                        .single(),
                    jdbc.sql("SELECT progress FROM okr.objective WHERE id = ?")
                        .param(okr.objective())
                        .query(BigDecimal.class)
                        .single()));
    assertThat(stored.get(0)).isEqualByComparingTo("4");
    assertThat(stored.get(1)).isEqualByComparingTo("0.2");
  }

  @Test
  void guiDongThoiKhongMatCapNhat() throws Exception {
    Okr okr = objective("DA_DUYET", period, 20271);
    ExecutorService pool = Executors.newFixedThreadPool(8);
    List<Callable<Integer>> work = new ArrayList<>();
    for (int i = 1; i <= 8; i++) {
      String value = String.valueOf(i);
      work.add(
          () -> {
            ObjectNode b = submit(value, "CAO").put("confirmLargeChange", true);
            return checkin(okr.kr1(), UUID.randomUUID().toString(), b).getResponse().getStatus();
          });
      String other = String.valueOf(10 - i);
      work.add(
          () -> {
            ObjectNode b = submit(other, "CAO").put("confirmLargeChange", true);
            return checkin(okr.kr2(), UUID.randomUUID().toString(), b).getResponse().getStatus();
          });
    }
    List<Integer> statuses = new ArrayList<>();
    for (Future<Integer> f : pool.invokeAll(work)) {
      statuses.add(f.get());
    }
    pool.shutdown();
    assertThat(statuses).allMatch(s -> s == 201 || s == 409);
    long ok = statuses.stream().filter(s -> s == 201).count();
    assertThat(ok).isPositive();
    // Mỗi lần gửi thành công tăng row_version đúng một; giá trị hiện tại là của lần gửi cuối; tiến
    // độ
    // mục tiêu khớp tiến độ hai KR đang lưu.
    inTenant(
        jdbc -> {
          long versions =
              jdbc.sql("SELECT sum(row_version) FROM okr.key_result WHERE objective_id = ?")
                  .param(okr.objective())
                  .query(Long.class)
                  .single();
          long submitted =
              jdbc.sql(
                      "SELECT count(*) FROM okr.kr_checkin c JOIN okr.key_result k ON k.id ="
                          + " c.key_result_id WHERE k.objective_id = ? AND c.status = 'DA_GUI'")
                  .param(okr.objective())
                  .query(Long.class)
                  .single();
          assertThat(versions).isEqualTo(submitted).isEqualTo(ok);
          for (UUID kr : List.of(okr.kr1(), okr.kr2())) {
            BigDecimal current =
                jdbc.sql("SELECT current_value FROM okr.key_result WHERE id = ?")
                    .param(kr)
                    .query(BigDecimal.class)
                    .optional()
                    .orElse(null);
            Optional<BigDecimal> last =
                jdbc.sql(
                        "SELECT value FROM okr.kr_checkin WHERE key_result_id = ? AND status ="
                            + " 'DA_GUI' ORDER BY checkin_at DESC LIMIT 1")
                    .param(kr)
                    .query(BigDecimal.class)
                    .optional();
            last.ifPresent(v -> assertThat(current).isEqualByComparingTo(v));
          }
          List<ProgressCalculator.Weighted> weights =
              jdbc.sql("SELECT weight, progress FROM okr.key_result WHERE objective_id = ?")
                  .param(okr.objective())
                  .query(
                      (rs, i) ->
                          new ProgressCalculator.Weighted(
                              rs.getBigDecimal("weight"), rs.getBigDecimal("progress")))
                  .list();
          BigDecimal objective =
              jdbc.sql("SELECT progress FROM okr.objective WHERE id = ?")
                  .param(okr.objective())
                  .query(BigDecimal.class)
                  .single();
          assertThat(objective).isEqualByComparingTo(ProgressCalculator.objective(weights));
          return null;
        });
  }

  @Test
  void chenhLonPhaiXacNhan() throws Exception {
    Okr okr = objective("DA_DUYET", period, 20271);
    checkin(okr.kr1(), UUID.randomUUID().toString(), submit("1", "CAO"));
    MvcResult r = checkin(okr.kr1(), UUID.randomUUID().toString(), submit("9", "CAO"));
    assertThat(r.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(r).get("code").asText()).isEqualTo("OKR-422-LARGE-CHANGE-UNCONFIRMED");
    assertThat(body(r).get("detail").asText())
        .isEqualTo("Giá trị mới chênh 80% so với lần trước. Đồng chí có chắc chắn không?");
    ObjectNode confirmed = submit("9", "CAO").put("confirmLargeChange", true);
    assertThat(
            checkin(okr.kr1(), UUID.randomUUID().toString(), confirmed).getResponse().getStatus())
        .isEqualTo(201);
  }

  @Test
  void kiemTruongBr0302() throws Exception {
    Okr okr = objective("DA_DUYET", period, 20271);
    ObjectNode b = submit("1.23456", "CAO");
    b.put("highlights", "x".repeat(1001));
    MvcResult r = checkin(okr.kr1(), UUID.randomUUID().toString(), b);
    assertThat(r.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(r).get("code").asText()).isEqualTo("OKR-422-VALIDATION");
    List<String> fields = new ArrayList<>();
    body(r).get("errors").forEach(e -> fields.add(e.get("field").asText()));
    assertThat(fields).containsExactlyInAnyOrder("value", "highlights");
  }

  @Test
  void nguoiKhacKhongCheckInDuocVaKhongThayBanNhap() throws Exception {
    Okr okr = objective("DA_DUYET", period, 20271);
    ObjectNode draft = submit("2", "THAP").put("status", "NHAP");
    assertThat(checkin(okr.kr1(), UUID.randomUUID().toString(), draft).getResponse().getStatus())
        .isEqualTo(201);
    draft.put("value", 3);
    MvcResult second = checkin(okr.kr1(), UUID.randomUUID().toString(), draft);
    assertThat(body(second).at("/checkin/status").asText()).isEqualTo("NHAP");
    // Bản nháp không đổi tiến độ.
    assertThat(body(second).at("/keyResult/progress").decimalValue()).isEqualByComparingTo("0");
    checkin(okr.kr1(), UUID.randomUUID().toString(), submit("1", "CAO"));
    draft.put("value", 4).put("confirmLargeChange", true);
    MvcResult latest = checkin(okr.kr1(), UUID.randomUUID().toString(), draft);

    MvcResult mine = perform(get("/api/v1/okr/key-results/" + okr.kr1() + "/checkins"));
    assertThat(mine.getResponse().getStatus()).isEqualTo(200);
    List<String> statuses = new ArrayList<>();
    body(mine).get("items").forEach(i -> statuses.add(i.get("status").asText()));
    assertThat(statuses).containsExactly("NHAP", "DA_GUI");
    assertThat(body(mine).at("/items/0/id")).isEqualTo(body(latest).at("/checkin/id"));
    assertResponseConforms(mine, PATH, PathItem.HttpMethod.GET);

    CALLER.set(OTHER);
    MvcResult theirs = perform(get("/api/v1/okr/key-results/" + okr.kr1() + "/checkins"));
    assertThat(body(theirs).get("items")).hasSize(1);
    assertThat(body(theirs).at("/items/0/status").asText()).isEqualTo("DA_GUI");
    MvcResult forbidden = checkin(okr.kr1(), UUID.randomUUID().toString(), submit("1", "CAO"));
    assertThat(forbidden.getResponse().getStatus()).isEqualTo(403);
  }

  @Test
  void danhSachKrCanCheckInCuaToi() throws Exception {
    Okr okr = objective("DA_DUYET", period, 20271);
    String url =
        "/api/v1/okr/key-results?period=" + period + "&owner=me&needCheckin=true&limit=200";
    MvcResult before = perform(get(url));
    assertThat(before.getResponse().getStatus()).isEqualTo(200);
    List<String> ids = ids(body(before));
    assertThat(ids).contains(okr.kr1().toString(), okr.kr2().toString());
    int count = body(before).at("/counts/needCheckin").asInt();
    assertResponseConforms(before, "/okr/key-results", PathItem.HttpMethod.GET);

    checkin(okr.kr1(), UUID.randomUUID().toString(), submit("4", "CAO"));
    JsonNode after = body(perform(get(url)));
    assertThat(ids(after)).doesNotContain(okr.kr1().toString()).contains(okr.kr2().toString());
    assertThat(after.at("/counts/needCheckin").asInt()).isEqualTo(count - 1);
  }

  @Test
  void thoiGianGuiP95DuoiHaiTramMiliGiay() throws Exception {
    Okr okr = objective("DA_DUYET", period, 20271);
    long[] samples = new long[40];
    for (int i = -10; i < samples.length; i++) {
      ObjectNode b = submit(String.valueOf(Math.floorMod(i, 10)), "CAO");
      b.put("confirmLargeChange", true);
      long t = System.nanoTime();
      MvcResult r = checkin(okr.kr1(), UUID.randomUUID().toString(), b);
      long elapsed = System.nanoTime() - t;
      assertThat(r.getResponse().getStatus()).isEqualTo(201);
      if (i >= 0) {
        samples[i] = elapsed;
      }
    }
    Arrays.sort(samples);
    assertThat(samples[(int) (samples.length * 0.95)] / 1_000_000.0).isLessThan(200.0);
  }

  // ---------------------------------------------------------------- tiện ích

  record Okr(UUID objective, String code, UUID kr1, UUID kr2) {}

  private Okr objective(String status, UUID periodId, int bucket) throws Exception {
    UUID objective = UUID.randomUUID();
    UUID kr1 = UUID.randomUUID();
    UUID kr2 = UUID.randomUUID();
    String code = "O" + (100 + codes.incrementAndGet());
    inTenant(
        jdbc -> {
          jdbc.sql(
                  "INSERT INTO okr.objective (id, period_id, period_bucket, owner_type,"
                      + " owner_unit_id, code, title, tier, status, created_by, updated_by)"
                      + " VALUES (?, ?, ?, 'TAP_THE', ?, ?, 'Mục tiêu kiểm thử check-in', 1, ?,"
                      + " ?, ?)")
              .params(objective, periodId, bucket, UUID.randomUUID(), code, status, OWNER, OWNER)
              .update();
          int n = 0;
          for (UUID kr : List.of(kr1, kr2)) {
            n++;
            jdbc.sql(
                    "INSERT INTO okr.key_result (id, period_id, period_bucket, objective_id, code,"
                        + " title, metric_type, unit, baseline_value, target_value, direction,"
                        + " weight, due_date, verifier_person_id, checkin_frequency, status,"
                        + " created_by, updated_by) VALUES (?, ?, ?, ?, ?, 'Kết quả kiểm thử',"
                        + " 'SO_LUONG', 'chi bộ', 0, 10, 'TANG', 0.5, DATE '2027-03-31', ?,"
                        + " 'TUAN', ?, ?, ?)")
                .params(
                    kr,
                    periodId,
                    bucket,
                    objective,
                    "KR " + code.substring(1) + "." + n,
                    VERIFIER,
                    "DA_DUYET".equals(status) ? "DA_DUYET" : status,
                    OWNER,
                    OWNER)
                .update();
          }
          return null;
        });
    return new Okr(objective, code, kr1, kr2);
  }

  private ObjectNode submit(String value, String confidence) {
    ObjectNode b = json.createObjectNode();
    b.put("status", "DA_GUI");
    b.put("value", new BigDecimal(value));
    b.put("confidence", confidence);
    b.put("channel", "WEB");
    return b;
  }

  private MvcResult checkin(UUID kr, String key, ObjectNode body) throws Exception {
    return perform(
        post("/api/v1/okr/key-results/" + kr + "/checkins")
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body)));
  }

  private long countSubmitted(UUID kr) throws Exception {
    return inTenant(
        jdbc ->
            jdbc.sql(
                    "SELECT count(*) FROM okr.kr_checkin WHERE key_result_id = ? AND status ="
                        + " 'DA_GUI'")
                .param(kr)
                .query(Long.class)
                .single());
  }

  private static List<String> ids(JsonNode page) {
    List<String> ids = new ArrayList<>();
    page.get("items").forEach(i -> ids.add(i.get("id").asText()));
    return ids;
  }

  private void insertPeriod(
      UUID id, String name, String start, String end, int bucket, String status) {
    JdbcClient jdbc = JdbcClient.create(configDataSource);
    TenantContext.run(
        tenant,
        () ->
            new TransactionTemplate(configTm)
                .executeWithoutResult(
                    s ->
                        jdbc.sql(
                                "INSERT INTO config.period (id, code, name, kind, flow,"
                                    + " start_date, end_date, period_bucket, status, locked_at,"
                                    + " created_by, updated_by) VALUES (?, ?, ?, 'QUY', 'NHA_NUOC',"
                                    + " ?::date, ?::date, ?, ?, CASE WHEN ? = 'DA_KHOA' THEN now()"
                                    + " END, ?, ?)")
                            .params(
                                id,
                                "Q-" + id.toString().substring(0, 8),
                                name,
                                start,
                                end,
                                bucket,
                                status,
                                status,
                                OWNER,
                                OWNER)
                            .update()));
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
}
