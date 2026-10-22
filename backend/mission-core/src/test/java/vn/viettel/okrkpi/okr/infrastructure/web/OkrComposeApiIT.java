package vn.viettel.okrkpi.okr.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.models.PathItem;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import vn.viettel.okrkpi.okr.application.port.OkrApprovalStarter;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Kiểm thử tích hợp CORE-01-03 trên PostgreSQL thật: bảy thao tác soạn của SCR-0202 khớp {@code
 * okr.yaml}, khoá lạc quan {@code If-Match}, phát lại {@code Idempotency-Key}, sinh mã {@code O{n}}
 * không trùng khi 50 luồng tạo song song, chặn ghi khi kỳ khoá và khi mục tiêu không còn nháp.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OkrComposeApiIT extends ContractConformanceTest {

  static final UUID ACTOR = UUID.fromString("01927a03-0000-7000-8000-0000000000ac");
  static final UUID VERIFIER = UUID.fromString("01927a03-0000-7000-8000-0000000000ad");
  static final UUID UNIT = UUID.fromString("01927a03-0000-7000-8000-0000000000f1");
  static final UUID RACE_UNIT = UUID.fromString("01927a03-0000-7000-8000-0000000000f2");
  static final UUID OTHER_UNIT = UUID.fromString("01927a03-0000-7000-8000-0000000000f9");

  /** Đơn vị của người gọi theo luồng kiểm thử (mặc định {@link #UNIT}). */
  static final InheritableThreadLocal<UUID> CALLER_UNIT =
      new InheritableThreadLocal<>() {
        @Override
        protected UUID initialValue() {
          return UNIT;
        }
      };

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  /**
   * Thay bean {@code testOkrCaller} của {@code OkrReadApiIT} (lớp cấu hình lồng của nó cùng gói nên
   * được quét vào ngữ cảnh này): người gọi là {@link #ACTOR}, đơn vị theo {@link #CALLER_UNIT}.
   */
  @MockitoBean(name = "testOkrCaller")
  private OkrCaller caller;

  /** Cổng khởi tạo tiến trình duyet-okr (CORE-01-05), giả lập workflow-service. */
  @MockitoBean private OkrApprovalStarter starter;

  /** Cây tổ chức giả: mặc định không có quan hệ cấp trên, cấp dưới. */
  @MockitoBean private OrgDirectoryApi orgDirectory;

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
  private final UUID previous = UUID.randomUUID();
  private final UUID locked = UUID.randomUUID();
  private UUID basis;
  private UUID approved;

  @Override
  protected ContractModule contractModule() {
    return ContractModule.OKR;
  }

  /** Bean giả bị đặt lại sau mỗi ca nên gắn hành vi trước từng ca. */
  @BeforeEach
  void caller() {
    when(caller.currentUserId()).thenReturn(Optional.of(ACTOR));
    when(caller.currentUnitId()).thenAnswer(inv -> Optional.of(CALLER_UNIT.get()));
  }

  @BeforeAll
  void seed() throws Exception {
    insertPeriod(period, "Quý I năm 2027", "2027-01-01", "2027-03-31", 20271, "MO");
    insertPeriod(previous, "Quý IV năm 2026", "2026-10-01", "2026-12-31", 20264, "MO");
    insertPeriod(locked, "Quý II năm 2027", "2027-04-01", "2027-06-30", 20272, "DA_KHOA");
    inTenant(
        jdbc -> {
          for (int bucket : List.of(20264, 20271, 20272)) {
            for (String table :
                List.of(
                    "objective",
                    "objective_basis",
                    "objective_review",
                    "key_result",
                    "kr_checkin")) {
              jdbc.sql("SELECT okr.fn_create_period_partitions(?::regclass, ?)")
                  .params("okr." + table, bucket)
                  .query()
                  .listOfRows();
            }
          }
          basis = UUID.randomUUID();
          jdbc.sql(
                  "INSERT INTO okr.basis (id, basis_kind, doc_number, title, valid_from, created_by,"
                      + " updated_by) VALUES (?, 'NGHI_QUYET', '05-NQ/TU', 'Nghị quyết chuyên đề',"
                      + " DATE '2026-01-01', ?, ?)")
              .params(basis, ACTOR, ACTOR)
              .update();
          approved = UUID.randomUUID();
          jdbc.sql(
                  "INSERT INTO okr.objective (id, period_id, period_bucket, owner_type,"
                      + " owner_unit_id, code, title, tier, status, created_by, updated_by)"
                      + " VALUES (?, ?, 20271, 'TAP_THE', ?, 'O90', 'Mục tiêu đã duyệt', 1,"
                      + " 'DA_DUYET', ?, ?)")
              .params(approved, period, OTHER_UNIT, ACTOR, ACTOR)
              .update();
          return null;
        });
  }

  @Test
  void taoMucTieuSinhMaVaKhopHopDong() throws Exception {
    MvcResult r = create(UNIT, period, "Nâng chất lượng sinh hoạt chi bộ", 2, null);
    assertThat(r.getResponse().getStatus()).isEqualTo(201);
    JsonNode body = body(r);
    assertThat(body.get("status").asText()).isEqualTo("NHAP");
    assertThat(body.get("versionNo").asInt()).isEqualTo(1);
    assertThat(body.get("code").asText()).matches("O\\d+");
    String no = body.get("code").asText().substring(1);
    assertThat(body.at("/keyResults/0/code").asText()).isEqualTo("KR " + no + ".1");
    assertThat(body.at("/keyResults/1/code").asText()).isEqualTo("KR " + no + ".2");
    assertThat(body.at("/bases/0/id").asText()).isEqualTo(basis.toString());
    assertThat(r.getResponse().getHeader("ETag")).isEqualTo("\"0\"");
    // Hai KR tổng trọng số 70%: BR-02-07 chặn; đơn vị cao nhất nên không đòi mục tiêu cấp trên.
    List<String> blocking = new ArrayList<>();
    body.get("checks")
        .forEach(
            c -> {
              if ("LOI_CHAN".equals(c.get("level").asText())) {
                blocking.add(c.get("rule").asText());
              }
            });
    assertThat(blocking).containsExactly("BR-02-07");
    assertThat(r.getResponse().getHeader("Location")).endsWith(body.get("id").asText());
    assertResponseConforms(r, "/okr/objectives", PathItem.HttpMethod.POST);
  }

  @Test
  void haiPutCungIfMatchMotThanhCongMotXungDot() throws Exception {
    UUID id = id(create(UNIT, period, "Mục tiêu sửa song song", 1, null));
    MvcResult first = update(id, "\"0\"", UUID.randomUUID().toString(), "Bản sửa thứ nhất");
    MvcResult second = update(id, "\"0\"", UUID.randomUUID().toString(), "Bản sửa thứ hai");
    assertThat(first.getResponse().getStatus()).isEqualTo(200);
    assertThat(first.getResponse().getHeader("ETag")).isEqualTo("\"1\"");
    assertResponseConforms(first, "/okr/objectives/{id}", PathItem.HttpMethod.PUT);
    assertThat(second.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(second).get("code").asText()).isEqualTo("OKR-409-VERSION-CONFLICT");

    MvcResult missing =
        perform(
            put("/api/v1/okr/objectives/" + id)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectiveBody("Thiếu If-Match", null).toString()));
    assertThat(missing.getResponse().getStatus()).isEqualTo(428);
  }

  @Test
  void putLapLaiCungKhoaPhatLaiKhongTangPhienBanHaiLan() throws Exception {
    UUID id = id(create(UNIT, period, "Mục tiêu lưu nháp tự động", 2, null));
    String key = UUID.randomUUID().toString();
    MvcResult a = update(id, "\"0\"", key, "Lưu nháp lần một");
    MvcResult b = update(id, "\"0\"", key, "Lưu nháp lần một");
    assertThat(a.getResponse().getStatus()).isEqualTo(200);
    assertThat(b.getResponse().getStatus()).isEqualTo(200);
    assertThat(b.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo(a.getResponse().getContentAsString(StandardCharsets.UTF_8));
    long version =
        inTenant(
            jdbc ->
                jdbc.sql("SELECT row_version FROM okr.objective WHERE id = ?")
                    .param(id)
                    .query(Long.class)
                    .single());
    assertThat(version).isEqualTo(1);
  }

  @Test
  void putThemSuaXoaKrKhongDanhLaiMa() throws Exception {
    JsonNode created = body(create(UNIT, period, "Mục tiêu có ba KR", 3, null));
    UUID id = UUID.fromString(created.get("id").asText());
    String no = created.get("code").asText().substring(1);
    ObjectNode req = objectiveBody("Mục tiêu có ba KR", "Theo chương trình công tác của cấp uỷ");
    ArrayNode krs = req.putArray("keyResults");
    // Giữ KR 1 (sửa nội dung), bỏ KR 2, giữ KR 3, thêm một KR mới.
    ObjectNode k1 = kr("Kiểm tra 100% chi bộ đã sửa", "0.3");
    k1.put("id", created.at("/keyResults/0/id").asText());
    ObjectNode k3 = kr("Giữ nguyên kết quả thứ ba", "0.3");
    k3.put("id", created.at("/keyResults/2/id").asText());
    krs.add(k1).add(k3).add(kr("Kết quả mới thêm", "0.4"));
    MvcResult r =
        perform(
            put("/api/v1/okr/objectives/" + id)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(req.toString()));
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    List<String> codes = new ArrayList<>();
    body(r).get("keyResults").forEach(k -> codes.add(k.get("code").asText()));
    assertThat(codes)
        .containsExactlyInAnyOrder("KR " + no + ".1", "KR " + no + ".3", "KR " + no + ".4");
  }

  @Test
  void namMuoiLuongTaoSongSongKhongTrungMa() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(10);
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < 50; i++) {
        int n = i;
        Callable<Integer> task =
            () -> {
              CALLER_UNIT.set(RACE_UNIT);
              return create(
                      RACE_UNIT,
                      period,
                      "Mục tiêu song song " + n,
                      0,
                      "Đơn vị có nhiệm vụ đột xuất")
                  .getResponse()
                  .getStatus();
            };
        results.add(pool.submit(task));
      }
      for (Future<Integer> f : results) {
        assertThat(f.get()).isEqualTo(201);
      }
    } finally {
      pool.shutdown();
    }
    List<String> codes =
        inTenant(
            jdbc ->
                jdbc.sql("SELECT code FROM okr.objective WHERE owner_unit_id = ? AND period_id = ?")
                    .params(RACE_UNIT, period)
                    .query(String.class)
                    .list());
    assertThat(codes).hasSize(50);
    assertThat(new HashSet<>(codes)).hasSize(50);
  }

  @Test
  void kyKhoaTraPeriodLocked() throws Exception {
    MvcResult r = create(UNIT, locked, "Mục tiêu kỳ đã khoá", 1, null);
    assertThat(r.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(r).get("code").asText()).isEqualTo("OKR-409-PERIOD-LOCKED");
  }

  @Test
  void rangBuocTruongVaPhamVi() throws Exception {
    ObjectNode req = objectiveBody("x".repeat(201), null);
    req.put("periodId", period.toString())
        .put("ownerType", "TAP_THE")
        .put("ownerUnitId", UNIT.toString());
    ObjectNode bad = kr("Chỉ tiêu bằng giá trị đầu", "0.5");
    bad.put("targetValue", 0)
        .put("verifierPersonId", ACTOR.toString())
        .put("dueDate", "2027-05-01");
    req.putArray("keyResults").add(bad);
    MvcResult r = postObjective(req);
    assertThat(r.getResponse().getStatus()).isEqualTo(422);
    Set<String> fields = new HashSet<>();
    body(r).get("errors").forEach(e -> fields.add(e.get("field").asText()));
    assertThat(fields)
        .contains(
            "title",
            "keyResults[0].targetValue",
            "keyResults[0].verifierPersonId",
            "keyResults[0].dueDate");

    MvcResult outside = create(OTHER_UNIT, period, "Đơn vị ngoài phạm vi", 1, null);
    assertThat(outside.getResponse().getStatus()).isEqualTo(403);
  }

  @Test
  void mucTieuKhongConNhapKhongSuaDuoc() throws Exception {
    CALLER_UNIT.set(OTHER_UNIT);
    try {
      MvcResult r =
          update(approved, "\"0\"", UUID.randomUUID().toString(), "Sửa mục tiêu đã duyệt");
      assertThat(r.getResponse().getStatus()).isEqualTo(409);
      assertThat(body(r).get("code").asText()).isEqualTo("OKR-409-INVALID-STATE");
    } finally {
      CALLER_UNIT.set(UNIT);
    }
  }

  @Test
  void themKrLienKetVaXoaLienKet() throws Exception {
    JsonNode created = body(create(UNIT, period, "Mục tiêu thêm KR riêng", 1, null));
    UUID id = UUID.fromString(created.get("id").asText());
    ObjectNode krReq = kr("Kết quả do trợ lý gợi ý", "0.4");
    krReq
        .put("objectiveId", id.toString())
        .put("contentSource", "AI_ACCEPTED")
        .put("aiSuggestionId", UUID.randomUUID().toString());
    MvcResult kr =
        perform(
            post("/api/v1/okr/key-results")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(krReq.toString()));
    assertThat(kr.getResponse().getStatus()).isEqualTo(201);
    assertThat(body(kr).get("code").asText())
        .isEqualTo("KR " + created.get("code").asText().substring(1) + ".2");
    assertThat(body(kr).get("aiAssisted").asBoolean()).isTrue();
    assertResponseConforms(kr, "/okr/key-results", PathItem.HttpMethod.POST);

    ObjectNode linkReq =
        json.createObjectNode()
            .put("fromType", "OBJECTIVE")
            .put("fromId", id.toString())
            .put("toType", "OBJECTIVE")
            .put("toId", approved.toString())
            .put("linkKind", "NGANG");
    MvcResult link = postLink(linkReq);
    assertThat(link.getResponse().getStatus()).isEqualTo(201);
    assertResponseConforms(link, "/okr/links", PathItem.HttpMethod.POST);
    assertThat(postLink(linkReq).getResponse().getStatus()).isEqualTo(409);

    String linkId = body(link).get("id").asText();
    MvcResult noMatch =
        perform(
            delete("/api/v1/okr/links/" + linkId)
                .header("Idempotency-Key", UUID.randomUUID().toString()));
    assertThat(noMatch.getResponse().getStatus()).isEqualTo(428);
    MvcResult del =
        perform(
            delete("/api/v1/okr/links/" + linkId)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("If-Match", "\"0\""));
    assertThat(del.getResponse().getStatus()).isEqualTo(204);
  }

  @Test
  void saoChepKyTruocSangNhap() throws Exception {
    JsonNode source = body(create(UNIT, previous, "Mục tiêu quý trước", 2, null));
    ObjectNode req =
        json.createObjectNode()
            .put("fromPeriodId", previous.toString())
            .put("toPeriodId", period.toString())
            .put("ownerUnitId", UNIT.toString());
    req.putArray("objectiveIds").add(source.get("id").asText());
    MvcResult r =
        perform(
            post("/api/v1/okr/objectives/copy-from-period")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(req.toString()));
    assertThat(r.getResponse().getStatus()).isEqualTo(201);
    JsonNode copy = body(r).at("/items/0");
    assertThat(copy.get("periodId").asText()).isEqualTo(period.toString());
    assertThat(copy.get("status").asText()).isEqualTo("NHAP");
    assertThat(copy.get("title").asText()).isEqualTo("Mục tiêu quý trước");
    assertThat(copy.get("keyResults")).hasSize(2);
    assertThat(copy.at("/keyResults/0/dueDate").asText()).isEqualTo("2027-03-31");
    assertThat(copy.at("/bases/0/id").asText()).isEqualTo(basis.toString());
    assertResponseConforms(r, "/okr/objectives/copy-from-period", PathItem.HttpMethod.POST);
  }

  @Test
  void ngucanhSoanKhopHopDong() throws Exception {
    create(UNIT, period, "Mục tiêu để đếm", 1, "Bổ sung theo chỉ đạo");
    MvcResult r = perform(get("/api/v1/okr/compose-context").param("period", period.toString()));
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    JsonNode body = body(r);
    assertThat(body.at("/unit/id").asText()).isEqualTo(UNIT.toString());
    assertThat(body.at("/me/id").asText()).isEqualTo(ACTOR.toString());
    assertThat(body.get("existingCount").asInt()).isPositive();
    assertThat(body.at("/policy/recommendedMax").asInt()).isEqualTo(3);
    assertThat(body.at("/policy/rules")).hasSize(14);
    assertResponseConforms(r, "/okr/compose-context", PathItem.HttpMethod.GET);
  }

  @Test
  void trinhHopLeChuyenChoDuyetVaKhoiTaoTienTrinhMotLan() throws Exception {
    when(starter.start(any(), any(), any(), anyLong(), any()))
        .thenReturn(
            Optional.of(
                new OkrApprovalStarter.ApprovalStarted(
                    "wf-1", VERIFIER, java.time.LocalDate.of(2027, 1, 8))));
    UUID id = id(create(UNIT, period, "Nâng chất lượng sinh hoạt chi bộ trình duyệt", 3, null));
    String key = UUID.randomUUID().toString();
    MvcResult first = submit(id, key, "\"0\"");
    assertThat(first.getResponse().getStatus())
        .as(first.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo(200);
    assertThat(body(first).get("status").asText()).isEqualTo("CHO_DUYET");
    assertThat(first.getResponse().getHeader("ETag")).isEqualTo("\"1\"");
    assertThat(body(first).at("/keyResults/0/status").asText()).isEqualTo("CHO_DUYET");
    assertResponseConforms(first, "/okr/objectives/{id}/submit", PathItem.HttpMethod.POST);
    MvcResult replay = submit(id, key, "\"0\"");
    assertThat(replay.getResponse().getStatus()).isEqualTo(200);
    verify(starter, times(1)).start(any(), eq(id), eq(UNIT), eq(1L), eq(ACTOR));
    String wf =
        inTenant(
            jdbc ->
                jdbc.sql("SELECT wf_process_instance_id FROM okr.objective WHERE id = ?")
                    .param(id)
                    .query(String.class)
                    .single());
    assertThat(wf).isEqualTo("wf-1");
    MvcResult again = submit(id, UUID.randomUUID().toString(), "\"1\"");
    assertThat(again.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(again).get("code").asText()).isEqualTo("OKR-409-INVALID-STATE");
  }

  @Test
  void trinhConLoiChanTra422VaGiuNhap() throws Exception {
    UUID id = id(create(UNIT, period, "Mục tiêu trình thiếu trọng số", 2, null));
    MvcResult r = submit(id, UUID.randomUUID().toString(), "\"0\"");
    assertThat(r.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(r).get("code").asText()).isEqualTo("OKR-422-VALIDATION");
    List<String> rules = new ArrayList<>();
    body(r).get("errors").forEach(e -> rules.add(e.get("rule").asText()));
    assertThat(rules).contains("BR-02-07");
    String status =
        inTenant(
            jdbc ->
                jdbc.sql("SELECT status FROM okr.objective WHERE id = ?")
                    .param(id)
                    .query(String.class)
                    .single());
    assertThat(status).isEqualTo("NHAP");
    verify(starter, never()).start(any(), eq(id), any(), anyLong(), any());
  }

  @Test
  void hangChoDuyetCuaDonViCapTren() throws Exception {
    UUID parent = UUID.fromString("01927a03-0000-7000-8000-0000000000f0");
    UUID id = id(create(UNIT, period, "Mục tiêu chờ cấp trên duyệt", 3, null));
    assertThat(submit(id, UUID.randomUUID().toString(), "\"0\"").getResponse().getStatus())
        .isEqualTo(200);
    when(orgDirectory.descendantsOf(any(), any(), eq(parent), eq(1))).thenReturn(List.of(UNIT));
    CALLER_UNIT.set(parent);
    try {
      MvcResult r = perform(get("/api/v1/okr/reviews").param("period", period.toString()));
      assertThat(r.getResponse().getStatus()).isEqualTo(200);
      JsonNode set = null;
      for (JsonNode item : body(r).get("items")) {
        if (item.at("/unit/id").asText().equals(UNIT.toString())) {
          set = item;
        }
      }
      assertThat(set).isNotNull();
      List<String> ids = new ArrayList<>();
      set.get("objectives").forEach(o -> ids.add(o.get("id").asText()));
      assertThat(ids).contains(id.toString());
      assertThat(set.get("viewerIsAuthor").asBoolean()).isTrue();
      assertThat(set.at("/objectives/0/keyResults/0/targetText").asText()).isEqualTo("100 %");
      assertResponseConforms(r, "/okr/reviews", PathItem.HttpMethod.GET);
    } finally {
      CALLER_UNIT.set(UNIT);
    }
  }

  private MvcResult submit(UUID id, String key, String ifMatch) throws Exception {
    return perform(
        post("/api/v1/okr/objectives/" + id + "/submit")
            .header("Idempotency-Key", key)
            .header("If-Match", ifMatch));
  }

  // ---------------------------------------------------------------- tiện ích

  private MvcResult create(UUID unit, UUID periodId, String title, int krCount, String reason)
      throws Exception {
    ObjectNode req = objectiveBody(title, reason);
    req.put("periodId", periodId.toString())
        .put("ownerType", "TAP_THE")
        .put("ownerUnitId", unit.toString());
    req.putArray("basisIds").add(basis.toString());
    ArrayNode krs = req.putArray("keyResults");
    for (int i = 0; i < krCount; i++) {
      krs.add(kr("Kết quả then chốt số " + (i + 1), i == 0 ? "0.4" : "0.3"));
    }
    if (reason == null) {
      req.put("overLimitReason", "Theo chương trình công tác của cấp uỷ");
    }
    return postObjective(req);
  }

  private MvcResult postObjective(ObjectNode req) throws Exception {
    return perform(
        post("/api/v1/okr/objectives")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(req.toString()));
  }

  private MvcResult postLink(ObjectNode req) throws Exception {
    return perform(
        post("/api/v1/okr/links")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(req.toString()));
  }

  private MvcResult update(UUID id, String ifMatch, String key, String title) throws Exception {
    ObjectNode req = objectiveBody(title, "Theo chương trình công tác của cấp uỷ");
    req.putArray("basisIds").add(basis.toString());
    return perform(
        put("/api/v1/okr/objectives/" + id)
            .header("Idempotency-Key", key)
            .header("If-Match", ifMatch)
            .contentType(MediaType.APPLICATION_JSON)
            .content(req.toString()));
  }

  private ObjectNode objectiveBody(String title, String reason) {
    ObjectNode req = json.createObjectNode().put("title", title);
    if (reason != null) {
      req.put("overLimitReason", reason);
    }
    return req;
  }

  private ObjectNode kr(String title, String weight) {
    return json.createObjectNode()
        .put("title", title)
        .put("metricType", "TY_LE")
        .put("unit", "%")
        .put("baselineValue", 0)
        .put("targetValue", 100)
        .put("weight", Double.parseDouble(weight))
        .put("verifierPersonId", VERIFIER.toString())
        .put("checkinFrequency", "TUAN");
  }

  private static UUID id(MvcResult r) throws Exception {
    return UUID.fromString(
        new ObjectMapper()
            .readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8))
            .get("id")
            .asText());
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
                                ACTOR,
                                ACTOR)
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
