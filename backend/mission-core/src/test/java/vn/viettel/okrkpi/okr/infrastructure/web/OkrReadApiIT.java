package vn.viettel.okrkpi.okr.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.PathItem;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.MissionCoreApplication;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.okr.api.OkrApi;
import vn.viettel.okrkpi.okr.api.OkrApi.KeyResultSnapshot;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Kiểm thử tích hợp CORE-01-02 trên PostgreSQL thật: chín thao tác đọc của SCR-0201 khớp {@code
 * okr.yaml}, tiến độ tính ở máy chủ, tenant khác không thấy mục tiêu, 2.000 mục tiêu phân trang con
 * trỏ không trùng không sót, xuất mẫu trả 202 và tác vụ báo lỗi rõ khi SVC-11 chưa nối, {@link
 * OkrApi} trả ảnh chụp KR cho MOD-02.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OkrReadApiIT extends ContractConformanceTest {

  static final UUID ACTOR = UUID.fromString("01927a02-0000-7000-8000-0000000000ac");
  static final UUID UNIT = UUID.fromString("01927a02-0000-7000-8000-0000000000f1");
  static final UUID PEER_UNIT = UUID.fromString("01927a02-0000-7000-8000-0000000000f2");
  static final UUID CHILD_UNIT = UUID.fromString("01927a02-0000-7000-8000-0000000000f3");
  static final UUID BIG_UNIT = UUID.fromString("01927a02-0000-7000-8000-0000000000f4");
  static final int BUCKET = 20271;

  @TestConfiguration
  static class OkrReadTestConfig {

    @Bean
    OkrCaller testOkrCaller() {
      return new OkrCaller() {
        @Override
        public Optional<UUID> currentUserId() {
          return Optional.of(ACTOR);
        }

        @Override
        public Optional<UUID> currentUnitId() {
          return Optional.of(UNIT);
        }
      };
    }
  }

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private OkrApi okrApi;
  @Autowired private OkrExportJobs exportJobs;

  @Autowired
  @Qualifier("okrDataSource") private DataSource okrDataSource;

  @Autowired
  @Qualifier("okrTransactionManager") private PlatformTransactionManager okrTm;

  @Autowired
  @Qualifier("configDataSource") private DataSource configDataSource;

  @Autowired
  @Qualifier("configTransactionManager") private PlatformTransactionManager configTm;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final TenantId otherTenant = TenantId.of(UUID.randomUUID());
  private final UUID period = UUID.randomUUID();
  private final UUID otherPeriod = UUID.randomUUID();
  private UUID o1;
  private UUID o2;
  private UUID o3;
  private UUID kr1;
  private UUID kr2;
  private UUID basis;

  @Override
  protected ContractModule contractModule() {
    return ContractModule.OKR;
  }

  @BeforeAll
  void seed() throws Exception {
    insertPeriod(tenant, period);
    insertPeriod(otherTenant, otherPeriod);
    inTenant(
        tenant,
        jdbc -> {
          for (String table :
              List.of(
                  "objective", "objective_basis", "objective_review", "key_result", "kr_checkin")) {
            jdbc.sql("SELECT okr.fn_create_period_partitions(?::regclass, ?)")
                .params("okr." + table, BUCKET)
                .query()
                .listOfRows();
          }
          basis = UUID.randomUUID();
          jdbc.sql(
                  "INSERT INTO okr.basis (id, basis_kind, doc_number, title, valid_from, created_by,"
                      + " updated_by) VALUES (?, 'NGHI_QUYET', '12-NQ/TU', 'Nghị quyết Đại hội',"
                      + " DATE '2026-01-01', ?, ?)")
              .params(basis, ACTOR, ACTOR)
              .update();
          o1 = objective(jdbc, period, UNIT, "MT-01", "DA_DUYET", null, 1);
          o2 = objective(jdbc, period, PEER_UNIT, "MT-01", "DA_DUYET", null, 1);
          o3 = objective(jdbc, period, CHILD_UNIT, "MT-01", "NHAP", o1, 2);
          kr1 = keyResult(jdbc, period, o1, "KR 1.1", "TANG", "0", "100", "50", "0.6");
          kr2 = keyResult(jdbc, period, o1, "KR 1.2", "GIAM", "10", "0", null, "0.4");
          jdbc.sql(
                  "INSERT INTO okr.kr_checkin (id, period_id, period_bucket, key_result_id, value,"
                      + " confidence, channel, idempotency_key, status, blocker_note, created_by,"
                      + " updated_by) VALUES (?, ?, ?, ?, 5, 'CAO', 'WEB', ?, 'DA_GUI',"
                      + " 'Chờ kinh phí', ?, ?)")
              .params(
                  UUID.randomUUID(),
                  period,
                  BUCKET,
                  kr2,
                  UUID.randomUUID().toString(),
                  ACTOR,
                  ACTOR)
              .update();
          jdbc.sql(
                  "INSERT INTO okr.objective_basis (period_bucket, objective_id, basis_id,"
                      + " created_by) VALUES (?, ?, ?, ?)")
              .params(BUCKET, o1, basis, ACTOR)
              .update();
          link(jdbc, "BASIS", basis, "OBJECTIVE", o1, "CAN_CU");
          link(jdbc, "OBJECTIVE", o1, "OBJECTIVE", o3, "PHAN_RA");
          link(jdbc, "KEY_RESULT", kr1, "OBJECTIVE", o2, "NGANG");
          // 2.000 mục tiêu của một đơn vị để kiểm phân trang con trỏ.
          jdbc.sql(
                  "INSERT INTO okr.objective (id, period_id, period_bucket, owner_type,"
                      + " owner_unit_id, code, title, tier, status, created_by, updated_by)"
                      + " SELECT gen_random_uuid(), ?, ?, 'TAP_THE', ?, 'MT-' || lpad(g::text, 4,"
                      + " '0'), 'Mục tiêu ' || g, 1, 'NHAP', ?, ? FROM generate_series(1, 2000) g")
              .params(period, BUCKET, BIG_UNIT, ACTOR, ACTOR)
              .update();
          return null;
        });
  }

  @Test
  void cayMucTieuTinhTienDoVaKhopHopDong() throws Exception {
    MvcResult r = perform(get("/api/v1/okr/tree").param("period", period.toString()));
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    JsonNode body = body(r);
    // Đơn vị cao nhất: parent = null (oneOf [OkrObjectiveRef, null]); bộ so khớp hợp đồng chưa hiểu
    // nhánh null của oneOf nên kiểm hợp đồng trên cây của đơn vị cấp dưới bên dưới.
    assertThat(body.has("parent")).isTrue();
    assertThat(body.get("parent").isNull()).isTrue();
    assertThat(body.at("/unit/id").asText()).isEqualTo(UNIT.toString());
    assertThat(body.get("viewerOwnUnit").asBoolean()).isTrue();
    assertThat(body.get("recommendedMax").asInt()).isEqualTo(3);
    assertThat(body.at("/period/weekCount").asInt()).isEqualTo(13);
    JsonNode objectives = body.get("objectives");
    assertThat(objectives).hasSize(1);
    // KR 1.1 = 50/100 = 0,5; KR 1.2 giảm 10 → 0, check-in 5 = 0,5; mục tiêu = 0,5.
    assertThat(objectives.at("/0/progress").decimalValue()).isEqualByComparingTo("0.5");
    assertThat(objectives.at("/0/keyResults/0/progress").decimalValue())
        .isEqualByComparingTo("0.5");
    assertThat(objectives.at("/0/keyResults/1/progress").decimalValue())
        .isEqualByComparingTo("0.5");
    assertThat(body.get("bases")).hasSize(1);
    assertThat(body.at("/peerLinks/0/fromLabel").asText()).isEqualTo("KR 1.1");
    assertThat(body.at("/peerLinks/0/fromKeyResultId").asText()).isEqualTo(kr1.toString());
    assertThat(body.at("/peerLinks/0/peerTitle").asText()).isEqualTo("Mục tiêu MT-01");

    MvcResult child =
        perform(
            get("/api/v1/okr/tree")
                .param("period", period.toString())
                .param("unit", CHILD_UNIT.toString()));
    assertResponseConforms(child, "/okr/tree", PathItem.HttpMethod.GET);
    assertThat(body(child).at("/parent/id").asText()).isEqualTo(o1.toString());
    assertThat(body(child).get("viewerOwnUnit").asBoolean()).isFalse();

    assertThat(
            perform(get("/api/v1/okr/tree").param("period", UUID.randomUUID().toString()))
                .getResponse()
                .getStatus())
        .isEqualTo(404);
  }

  /**
   * Bỏ {@code period}: hợp đồng lấy kỳ đang MO. Tenant có cả kỳ quý đã khoá và kỳ tháng đang mở
   * cùng bao trùm hôm nay thì lấy kỳ đang mở; tenant chưa có kỳ nào (server dev mới) thì 404.
   */
  @Test
  void cayMucTieuKhongTruyenKyLayKyDangMo() throws Exception {
    TenantId t = TenantId.of(UUID.randomUUID());
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
    int quarter = (today.getMonthValue() - 1) / 3;
    LocalDate qStart = LocalDate.of(today.getYear(), quarter * 3 + 1, 1);
    int bucket = today.getYear() * 10 + quarter + 1;
    UUID locked = UUID.randomUUID();
    UUID open = UUID.randomUUID();
    insertPeriod(t, locked, "QUY", qStart, qStart.plusMonths(3).minusDays(1), "DA_KHOA", bucket);
    insertPeriod(
        t,
        open,
        "THANG",
        today.withDayOfMonth(1),
        today.withDayOfMonth(today.lengthOfMonth()),
        "MO",
        bucket);
    MvcResult r =
        TenantContext.call(
            t,
            () -> mvc.perform(get("/api/v1/okr/tree").param("unit", UNIT.toString())).andReturn());
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(r).at("/period/id").asText()).isEqualTo(open.toString());

    TenantId empty = TenantId.of(UUID.randomUUID());
    MvcResult none =
        TenantContext.call(
            empty,
            () -> mvc.perform(get("/api/v1/okr/tree").param("unit", UNIT.toString())).andReturn());
    assertThat(none.getResponse().getStatus()).isEqualTo(404);
  }

  @Test
  void danhSachMucTieuCoDemVaLoc() throws Exception {
    MvcResult r =
        perform(
            get("/api/v1/okr/objectives")
                .param("period", period.toString())
                .param("unit", UNIT.toString())
                .param("view", "table"));
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(r, "/okr/objectives", PathItem.HttpMethod.GET);
    assertThat(body(r).get("items")).hasSize(1);
    assertThat(body(r).at("/counts/objectives").asInt()).isEqualTo(1);
    assertThat(body(r).at("/counts/recommendedMax").asInt()).isEqualTo(3);

    MvcResult filtered =
        perform(
            get("/api/v1/okr/objectives")
                .param("period", period.toString())
                .param("unit", CHILD_UNIT.toString())
                .param("status", "DA_DUYET"));
    assertThat(body(filtered).get("items")).isEmpty();

    MvcResult search =
        perform(
            get("/api/v1/okr/objectives")
                .param("period", period.toString())
                .param("unit", BIG_UNIT.toString())
                .param("q", "muc tieu 1999"));
    assertThat(body(search).get("items")).hasSize(1);

    MvcResult bad =
        perform(
            get("/api/v1/okr/objectives").param("period", period.toString()).param("view", "x"));
    assertThat(bad.getResponse().getStatus()).isEqualTo(422);
  }

  @Test
  void haiNghinMucTieuPhanTrangKhongTrungKhongSot() throws Exception {
    Set<String> seen = new HashSet<>();
    String cursor = null;
    int pages = 0;
    do {
      MockHttpServletRequestBuilder req =
          get("/api/v1/okr/objectives")
              .param("period", period.toString())
              .param("unit", BIG_UNIT.toString())
              .param("limit", "200");
      if (cursor != null) {
        req.param("cursor", cursor);
      }
      JsonNode page = body(perform(req));
      page.get("items").forEach(i -> assertThat(seen.add(i.get("id").asText())).isTrue());
      cursor = page.hasNonNull("nextCursor") ? page.get("nextCursor").asText() : null;
      pages++;
    } while (cursor != null && pages < 20);
    assertThat(seen).hasSize(2000);
    assertThat(pages).isEqualTo(10);
  }

  @Test
  void chiTietMucTieuVaKrCoEtag() throws Exception {
    MvcResult r = perform(get("/api/v1/okr/objectives/" + o3));
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    assertThat(r.getResponse().getHeader("ETag")).isNotBlank();
    assertResponseConforms(r, "/okr/objectives/{id}", PathItem.HttpMethod.GET);
    assertThat(body(r).at("/parentObjective/code").asText()).isEqualTo("MT-01");
    assertThat(body(r).at("/period/id").asText()).isEqualTo(period.toString());
    assertThat(body(r).get("permittedActions").isArray()).isTrue();

    MvcResult one = perform(get("/api/v1/okr/objectives/" + o1));
    assertThat(body(one).get("peerLinks")).hasSize(3);

    MvcResult kr = perform(get("/api/v1/okr/key-results/" + kr2));
    assertThat(kr.getResponse().getStatus()).isEqualTo(200);
    assertThat(kr.getResponse().getHeader("ETag")).isNotBlank();
    assertResponseConforms(kr, "/okr/key-results/{id}", PathItem.HttpMethod.GET);
    assertThat(body(kr).at("/lastCheckin/value").decimalValue()).isEqualByComparingTo("5");
    assertThat(body(kr).get("latestBlocker").asText()).isEqualTo("Chờ kinh phí");
    assertThat(body(kr).at("/objective/id").asText()).isEqualTo(o1.toString());
    assertThat(body(kr).get("permittedActions").isArray()).isTrue();

    assertThat(
            perform(get("/api/v1/okr/objectives/" + UUID.randomUUID())).getResponse().getStatus())
        .isEqualTo(404);
    assertThat(
            perform(get("/api/v1/okr/key-results/" + UUID.randomUUID())).getResponse().getStatus())
        .isEqualTo(404);
  }

  @Test
  void danhSachKrTheoDonViVaCapDuoi() throws Exception {
    MvcResult r =
        perform(
            get("/api/v1/okr/key-results")
                .param("period", period.toString())
                .param("unit", UNIT.toString()));
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(r, "/okr/key-results", PathItem.HttpMethod.GET);
    JsonNode body = body(r);
    assertThat(body.get("items")).hasSize(2);
    assertThat(body.at("/period/id").asText()).isEqualTo(period.toString());
    assertThat(body.at("/items/1/lastCheckin/value").decimalValue()).isEqualByComparingTo("5");

    MvcResult subtree =
        perform(
            get("/api/v1/okr/key-results")
                .param("period", period.toString())
                .param("scope", "subtree"));
    assertThat(body(subtree).get("items")).hasSize(2);

    MvcResult byObjective =
        perform(get("/api/v1/okr/key-results").param("objective", o1.toString()));
    assertThat(body(byObjective).get("items")).hasSize(2);

    MvcResult risk =
        perform(
            get("/api/v1/okr/key-results")
                .param("period", period.toString())
                .param("riskFlag", "RUI_RO"));
    assertThat(body(risk).get("items")).isEmpty();

    MvcResult need =
        perform(
            get("/api/v1/okr/key-results")
                .param("period", period.toString())
                .param("needCheckin", "true"));
    // KR 1.1 chưa có check-in; KR 1.2 vừa check-in.
    assertThat(body(need).get("items")).hasSize(1);
    assertThat(body(need).at("/items/0/id").asText()).isEqualTo(kr1.toString());
  }

  @Test
  void banDoLienKetDocVaNgang() throws Exception {
    MvcResult r =
        perform(get("/api/v1/okr/alignment").param("objective", o1.toString()).param("depth", "2"));
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(r, "/okr/alignment", PathItem.HttpMethod.GET);
    JsonNode body = body(r);
    assertThat(body.get("rootId").asText()).isEqualTo(o1.toString());
    Set<String> ids = new HashSet<>();
    body.get("nodes").forEach(n -> ids.add(n.get("id").asText()));
    assertThat(ids)
        .contains(
            o1.toString(),
            o2.toString(),
            o3.toString(),
            kr1.toString(),
            kr2.toString(),
            basis.toString());
    assertThat(body.get("edges")).hasSize(3);

    MvcResult byUnit = perform(get("/api/v1/okr/alignment").param("period", period.toString()));
    assertThat(body(byUnit).get("nodes").size()).isGreaterThanOrEqualTo(3);

    assertThat(perform(get("/api/v1/okr/alignment").param("depth", "9")).getResponse().getStatus())
        .isEqualTo(422);
    assertThat(
            perform(get("/api/v1/okr/alignment").param("objective", UUID.randomUUID().toString()))
                .getResponse()
                .getStatus())
        .isEqualTo(404);
  }

  @Test
  void lienKetVaCanCu() throws Exception {
    MvcResult links =
        perform(
            get("/api/v1/okr/links").param("fromId", o1.toString()).param("linkKind", "PHAN_RA"));
    assertThat(links.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(links, "/okr/links", PathItem.HttpMethod.GET);
    assertThat(body(links).get("items")).hasSize(1);
    assertThat(body(links).at("/items/0/toId").asText()).isEqualTo(o3.toString());

    MvcResult bases =
        perform(
            get("/api/v1/okr/bases")
                .param("kind", "NGHI_QUYET")
                .param("validOn", "2027-02-01")
                .param("q", "nghi quyet"));
    assertThat(bases.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(bases, "/okr/bases", PathItem.HttpMethod.GET);
    assertThat(body(bases).get("items")).hasSize(1);
    assertThat(body(perform(get("/api/v1/okr/bases").param("validOn", "2025-01-01"))).get("items"))
        .isEmpty();
    assertThat(perform(get("/api/v1/okr/links").param("fromType", "X")).getResponse().getStatus())
        .isEqualTo(422);
  }

  @Test
  void xuatMauTraTacVuVaBaoLoiKhiChuaNoiDichVu() throws Exception {
    String content =
        "{\"periodId\":\"" + period + "\",\"unitId\":\"" + UNIT + "\",\"format\":\"PDF\"}";
    MvcResult r =
        perform(
            post("/api/v1/okr/objectives/export")
                .header("Idempotency-Key", "xuat-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(content));
    assertThat(r.getResponse().getStatus()).isEqualTo(202);
    assertResponseConforms(r, "/okr/objectives/export", PathItem.HttpMethod.POST);
    String jobId = body(r).get("id").asText();
    assertThat(r.getResponse().getHeader("Location")).isEqualTo("/api/v1/jobs/" + jobId);
    assertThat(exportJobs.find(tenant, jobId))
        .hasValueSatisfying(
            j -> {
              assertThat(j.job().status()).isEqualTo(JobStatus.THAT_BAI);
              assertThat(j.job().problem().getProperties())
                  .containsEntry("code", OkrExportJobs.UNAVAILABLE);
            });
  }

  @Test
  void tenantKhacKhongThayMucTieu() throws Exception {
    MvcResult r =
        TenantContext.call(
            otherTenant, () -> mvc.perform(get("/api/v1/okr/objectives/" + o1)).andReturn());
    assertThat(r.getResponse().getStatus()).isEqualTo(404);
    MvcResult tree =
        TenantContext.call(
            otherTenant,
            () ->
                mvc.perform(get("/api/v1/okr/tree").param("period", otherPeriod.toString()))
                    .andReturn());
    assertThat(body(tree).get("objectives")).isEmpty();
    assertThat(okrApi.findKeyResult(otherTenant, kr1)).isEmpty();
  }

  @Test
  void congOkrApiChoModuleGiaoViec() throws Exception {
    Optional<KeyResultSnapshot> snapshot =
        TenantContext.call(tenant, () -> okrApi.findKeyResult(tenant, kr1));
    assertThat(snapshot)
        .hasValueSatisfying(
            s -> {
              assertThat(s.approved()).isTrue();
              assertThat(s.ownerUnitId()).isEqualTo(UNIT);
              assertThat(s.progress()).isEqualByComparingTo("0.5");
            });
    assertThat(
            TenantContext.call(tenant, () -> okrApi.findKeyResultById(tenant, kr1))
                .orElseThrow()
                .progressPercent())
        .isEqualTo(50.0);
    List<KeyResultSnapshot> approved =
        TenantContext.call(
            tenant, () -> okrApi.listApprovedKeyResults(tenant, PeriodId.of(period), UNIT));
    assertThat(approved).extracting(KeyResultSnapshot::id).containsExactly(kr1, kr2);
    assertThat(
            TenantContext.call(
                tenant,
                () -> okrApi.listApprovedKeyResults(tenant, PeriodId.of(period), CHILD_UNIT)))
        .isEmpty();
  }

  // ---------------------------------------------------------------- dữ liệu mẫu

  private UUID objective(
      JdbcClient jdbc, UUID p, UUID unit, String code, String status, UUID parent, int tier) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO okr.objective (id, period_id, period_bucket, owner_type, owner_unit_id,"
                + " code, title, tier, status, parent_objective_id, created_by, updated_by)"
                + " VALUES (?, ?, ?, 'TAP_THE', ?, ?, ?, ?, ?, ?, ?, ?)")
        .params(id, p, BUCKET, unit, code, "Mục tiêu " + code, tier, status, parent, ACTOR, ACTOR)
        .update();
    return id;
  }

  private UUID keyResult(
      JdbcClient jdbc,
      UUID p,
      UUID objective,
      String code,
      String direction,
      String baseline,
      String target,
      String current,
      String weight) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO okr.key_result (id, period_id, period_bucket, objective_id, code, title,"
                + " metric_type, unit, baseline_value, target_value, current_value, direction,"
                + " weight, due_date, verifier_person_id, checkin_frequency, status, created_by,"
                + " updated_by) VALUES (?, ?, ?, ?, ?, ?, 'SO_LUONG', 'việc', ?, ?, ?, ?, ?, ?, ?,"
                + " 'TUAN', 'DA_DUYET', ?, ?)")
        .params(
            id,
            p,
            BUCKET,
            objective,
            code,
            "Kết quả " + code,
            new BigDecimal(baseline),
            new BigDecimal(target),
            current == null ? null : new BigDecimal(current),
            direction,
            new BigDecimal(weight),
            LocalDate.of(2027, 3, 31),
            ACTOR,
            ACTOR,
            ACTOR)
        .update();
    return id;
  }

  private void link(
      JdbcClient jdbc, String fromType, UUID fromId, String toType, UUID toId, String kind) {
    jdbc.sql(
            "INSERT INTO okr.okr_link (id, period_id, from_type, from_id, to_type, to_id,"
                + " link_kind, created_by, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
        .params(UUID.randomUUID(), period, fromType, fromId, toType, toId, kind, ACTOR, ACTOR)
        .update();
  }

  private void insertPeriod(TenantId t, UUID id) throws Exception {
    insertPeriod(t, id, "QUY", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 3, 31), "MO", BUCKET);
  }

  private void insertPeriod(
      TenantId t, UUID id, String kind, LocalDate start, LocalDate end, String status, int bucket)
      throws Exception {
    JdbcClient jdbc = JdbcClient.create(configDataSource);
    TenantContext.call(
        t,
        () ->
            new TransactionTemplate(configTm)
                .execute(
                    s ->
                        jdbc.sql(
                                "INSERT INTO config.period (id, code, name, kind, flow,"
                                    + " start_date, end_date, period_bucket, status, locked_at,"
                                    + " created_by, updated_by) VALUES (?, ?, 'Quý I năm 2027', ?,"
                                    + " 'NHA_NUOC', ?, ?, ?, ?, ?, ?, ?)")
                            .params(
                                id,
                                kind + "-" + id.toString().substring(0, 8),
                                kind,
                                start,
                                end,
                                bucket,
                                status,
                                // ck_period__locked: kỳ đã khoá phải có thời điểm khoá.
                                "DA_KHOA".equals(status)
                                    ? java.sql.Timestamp.from(java.time.Instant.now())
                                    : null,
                                ACTOR,
                                ACTOR)
                            .update()));
  }

  private <T> T inTenant(TenantId t, java.util.function.Function<JdbcClient, T> work)
      throws Exception {
    JdbcClient jdbc = JdbcClient.create(okrDataSource);
    return TenantContext.call(
        t, () -> new TransactionTemplate(okrTm).execute(s -> work.apply(jdbc)));
  }

  private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
    return TenantContext.call(tenant, () -> mvc.perform(request).andReturn());
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }
}
