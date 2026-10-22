package vn.viettel.okrkpi.okr.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.models.PathItem;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
import vn.viettel.okrkpi.okr.application.port.OkrApprovalEvents;
import vn.viettel.okrkpi.okr.application.port.OkrApprovalSignal;
import vn.viettel.okrkpi.okr.application.port.OkrApprovalStarter;
import vn.viettel.okrkpi.okr.infrastructure.persistence.OutboxOkrApprovalEvents;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.job.TrackedJob;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer.SerializedPayload;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Kiểm thử tích hợp CORE-01-06 trên PostgreSQL thật: phê duyệt ghi EVT-01 vào outbox cùng giao dịch
 * với {@code DA_DUYET}, chặn tự duyệt (BR-02-08), trả lại bắt buộc ý kiến (BR-02-09) và phát
 * EVT-21, rút lại chỉ người soạn, lịch sử quyết định, duyệt hàng loạt 200 bộ trong đó 3 bộ lệch
 * phiên bản.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OkrApprovalApiIT extends ContractConformanceTest {

  static final UUID REVIEWER = UUID.fromString("01927a03-0000-7000-8000-0000000006a1");
  static final UUID AUTHOR = UUID.fromString("01927a03-0000-7000-8000-0000000006a2");
  static final UUID VERIFIER = UUID.fromString("01927a03-0000-7000-8000-0000000006a3");
  static final UUID UNIT = UUID.fromString("01927a03-0000-7000-8000-0000000006f0");

  /** Người gọi theo luồng kiểm thử (mặc định người duyệt). */
  static final InheritableThreadLocal<UUID> CALLER =
      new InheritableThreadLocal<>() {
        @Override
        protected UUID initialValue() {
          return REVIEWER;
        }
      };

  /** Outbox thật với bộ tuần tự giả (không có Apicurio). */
  @TestConfiguration
  static class ApprovalEventsConfig {

    @Bean
    @Primary
    OkrApprovalEvents testOkrApprovalEvents(
        @Qualifier("okrDataSource") DataSource dataSource, ObjectMapper json) {
      return new OutboxOkrApprovalEvents(
          new OutboxWriter(
              new JdbcTemplate(dataSource),
              "okr",
              (topic, payload) -> new SerializedPayload(new byte[] {1}, 1L, 1),
              json,
              Clock.systemUtc()),
          () -> null);
    }
  }

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @MockitoBean(name = "testOkrCaller")
  private OkrCaller caller;

  @MockitoBean private OkrApprovalStarter starter;
  @MockitoBean private OkrApprovalSignal signal;

  /** Mọi đơn vị kiểm thử là cấp dưới của {@link #UNIT}, không đơn vị nào có cấp trên. */
  @MockitoBean private OrgDirectoryApi orgDirectory;

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private OkrApproveBatchJobs batches;
  @Autowired private vn.viettel.okrkpi.okr.api.OkrApi okrApi;

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
  private UUID basis;

  @Override
  protected ContractModule contractModule() {
    return ContractModule.OKR;
  }

  @BeforeEach
  void caller() {
    CALLER.set(REVIEWER);
    when(caller.currentUserId()).thenAnswer(inv -> Optional.of(CALLER.get()));
    when(caller.currentUnitId()).thenReturn(Optional.of(UNIT));
    when(orgDirectory.isDescendant(any(), any(), eq(UNIT), any())).thenReturn(true);
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
                                "INSERT INTO config.period (id, code, name, kind, flow, start_date,"
                                    + " end_date, period_bucket, status, created_by, updated_by)"
                                    + " VALUES (?, ?, 'Quý I năm 2027', 'QUY', 'NHA_NUOC',"
                                    + " DATE '2027-01-01', DATE '2027-03-31', 20271, 'MO', ?, ?)")
                            .params(
                                period, "Q-" + period.toString().substring(0, 8), AUTHOR, AUTHOR)
                            .update()));
    inTenant(
        jdbc -> {
          for (String table :
              List.of(
                  "objective", "objective_basis", "objective_review", "key_result", "kr_checkin")) {
            jdbc.sql("SELECT okr.fn_create_period_partitions(?::regclass, 20271)")
                .param("okr." + table)
                .query()
                .listOfRows();
          }
          basis = UUID.randomUUID();
          jdbc.sql(
                  "INSERT INTO okr.basis (id, basis_kind, doc_number, title, valid_from, created_by,"
                      + " updated_by) VALUES (?, 'NGHI_QUYET', '05-NQ/TU', 'Nghị quyết chuyên đề',"
                      + " DATE '2026-01-01', ?, ?)")
              .params(basis, AUTHOR, AUTHOR)
              .update();
          return null;
        });
  }

  @Test
  void pheDuyetGhiEvt01CungGiaoDichVaKhopHopDong() throws Exception {
    UUID id = pending(UUID.randomUUID(), AUTHOR);
    String key = UUID.randomUUID().toString();
    MvcResult r = approve(id, "\"0\"", "Đồng ý với bộ OKR", key);
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    JsonNode body = body(r);
    assertThat(body.get("status").asText()).isEqualTo("DA_DUYET");
    assertThat(r.getResponse().getHeader("ETag")).isEqualTo("\"1\"");
    assertResponseConforms(r, "/okr/objectives/{id}/approve", PathItem.HttpMethod.POST);

    Map<String, Object> row =
        inTenant(
            jdbc ->
                jdbc.sql(
                        "SELECT o.status, o.approved_by, o.row_version,"
                            + " (SELECT count(*) FROM okr.key_result k WHERE k.objective_id = o.id"
                            + " AND k.status = 'DA_DUYET') AS krs,"
                            + " (SELECT count(*) FROM okr.outbox_event e WHERE e.aggregate_id = o.id"
                            + " AND e.topic = 'okr.objective.approved.v1'"
                            + " AND e.aggregate_version = o.row_version) AS evt,"
                            + " (SELECT decision FROM okr.objective_review v"
                            + " WHERE v.objective_id = o.id) AS decision"
                            + " FROM okr.objective o WHERE o.id = ?")
                    .param(id)
                    .query()
                    .singleRow());
    assertThat(row.get("status")).isEqualTo("DA_DUYET");
    assertThat(row.get("approved_by")).isEqualTo(REVIEWER);
    assertThat(((Number) row.get("krs")).intValue()).isEqualTo(2);
    assertThat(((Number) row.get("evt")).intValue()).isEqualTo(1);
    assertThat(row.get("decision")).isEqualTo("DUYET");
    verify(signal).decided(eq(tenant), eq(id), any(), eq("DUYET"), eq(REVIEWER), eq(0L));

    // Phát lại cùng khoá: cùng kết quả, không ghi thêm sự kiện.
    MvcResult again = approve(id, "\"0\"", "Đồng ý với bộ OKR", key);
    assertThat(again.getResponse().getStatus()).isEqualTo(200);
    assertThat(count(id, "okr.objective.approved.v1")).isEqualTo(1);
  }

  @Test
  void nguoiSoanTuDuyetBiChan403() throws Exception {
    UUID id = pending(UUID.randomUUID(), REVIEWER);
    MvcResult r = approve(id, "\"0\"", null);
    assertThat(r.getResponse().getStatus()).isEqualTo(403);
    JsonNode p = body(r);
    assertThat(p.get("code").asText()).isEqualTo("OKR-403-SELF-APPROVAL");
    assertThat(p.get("msg").asText()).isEqualTo("MSG-02-11");
    assertThat(status(id)).isEqualTo("CHO_DUYET");
    assertThat(count(id, "okr.objective.approved.v1")).isZero();
  }

  @Test
  void traLaiBatBuocYKienVaThongBaoNguoiSoan() throws Exception {
    UUID id = pending(UUID.randomUUID(), AUTHOR);
    MvcResult missing = sendBack(id, "\"0\"", "  ");
    assertThat(missing.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(missing).get("code").asText()).isEqualTo("OKR-422-COMMENT-REQUIRED");
    assertThat(body(missing).get("msg").asText()).isEqualTo("MSG-02-10");

    MvcResult r = sendBack(id, "\"0\"", "Đề nghị viết lại KR 1.2 thành chỉ tiêu đo được.");
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(r).get("status").asText()).isEqualTo("NHAP");
    assertResponseConforms(r, "/okr/objectives/{id}/return", PathItem.HttpMethod.POST);
    assertThat(count(id, "notify.message.requested.v1")).isEqualTo(1);

    // Trình lại rồi người duyệt cũ trả lại lần nữa: lần trình mới có dòng ý kiến riêng.
    inTenant(
        jdbc ->
            jdbc.sql("UPDATE okr.objective SET status = 'CHO_DUYET' WHERE id = ?")
                .param(id)
                .update());
    MvcResult second = sendBack(id, "\"1\"", "Vẫn chưa bổ sung căn cứ.");
    assertThat(second.getResponse().getStatus()).isEqualTo(200);
    List<Integer> rounds =
        inTenant(
            jdbc ->
                jdbc.sql(
                        "SELECT version_no FROM okr.objective_review WHERE objective_id = ?"
                            + " ORDER BY version_no")
                    .param(id)
                    .query(Integer.class)
                    .list());
    assertThat(rounds).containsExactly(1, 2);
  }

  @Test
  void rutLaiChiNguoiSoanKhiConChoDuyet() throws Exception {
    UUID id = pending(UUID.randomUUID(), AUTHOR);
    MvcResult other = withdraw(id, "\"0\"");
    assertThat(other.getResponse().getStatus()).isEqualTo(403);

    CALLER.set(AUTHOR);
    MvcResult r = withdraw(id, "\"0\"");
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(r).get("status").asText()).isEqualTo("NHAP");
    assertResponseConforms(r, "/okr/objectives/{id}/withdraw", PathItem.HttpMethod.POST);
    verify(signal).cancelled(eq(tenant), eq(id), any(), eq(AUTHOR), eq(0L));

    MvcResult again = withdraw(id, "\"1\"");
    assertThat(again.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(again).get("code").asText()).isEqualTo("OKR-409-INVALID-STATE");
  }

  @Test
  void lichSuGhepQuyetDinhMoiNhatTruoc() throws Exception {
    UUID id = pending(UUID.randomUUID(), AUTHOR);
    sendBack(id, "\"0\"", "Bổ sung căn cứ.");
    inTenant(
        jdbc ->
            jdbc.sql("UPDATE okr.objective SET status = 'CHO_DUYET' WHERE id = ?")
                .param(id)
                .update());
    approve(id, "\"1\"", null);
    MvcResult r = perform(get("/api/v1/okr/objectives/" + id + "/history"));
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    JsonNode items = body(r).get("items");
    List<String> actions = new ArrayList<>();
    items.forEach(i -> actions.add(i.get("action").asText()));
    assertThat(actions).containsExactly("DUYET", "TRA_LAI", "TAO");
    assertThat(items.get(1).get("comment").asText()).isEqualTo("Bổ sung căn cứ.");
    assertThat(items.get(0).get("versionNo").asInt()).isEqualTo(2);
    assertResponseConforms(r, "/okr/objectives/{id}/history", PathItem.HttpMethod.GET);

    MvcResult page = perform(get("/api/v1/okr/objectives/" + id + "/history?limit=2"));
    String cursor = body(page).get("nextCursor").asText();
    MvcResult rest =
        perform(get("/api/v1/okr/objectives/" + id + "/history?limit=2&cursor=" + cursor));
    assertThat(body(rest).at("/items/0/action").asText()).isEqualTo("TAO");
  }

  @Test
  void duyetHangLoat200BoBaBoLechPhienBan() throws Exception {
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      ids.add(pending(UUID.randomUUID(), AUTHOR));
    }
    ObjectNode req = json.createObjectNode();
    ArrayNode items = req.putArray("items");
    for (int i = 0; i < ids.size(); i++) {
      items.addObject().put("id", ids.get(i).toString()).put("rowVersion", i < 3 ? 7 : 0);
    }
    MvcResult r =
        perform(
            post("/api/v1/okr/objectives/approve-batch")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(req.toString()));
    assertThat(r.getResponse().getStatus()).isEqualTo(202);
    assertResponseConforms(r, "/okr/objectives/approve-batch", PathItem.HttpMethod.POST);
    String jobId = body(r).get("id").asText();

    TrackedJob job = null;
    for (int i = 0; i < 600; i++) {
      job = batches.find(tenant, jobId).orElseThrow();
      if (job.job().status() == JobStatus.HOAN_TAT) {
        break;
      }
      Thread.sleep(100);
    }
    assertThat(job.job().status()).isEqualTo(JobStatus.HOAN_TAT);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> results =
        (List<Map<String, Object>>) job.job().problem().getProperties().get("items");
    List<String> failed =
        results.stream()
            .filter(m -> !Integer.valueOf(200).equals(m.get("status")))
            .map(m -> (String) m.get("id"))
            .toList();
    assertThat(failed)
        .containsExactlyInAnyOrder(
            ids.get(0).toString(), ids.get(1).toString(), ids.get(2).toString());
    assertThat(results.get(0).get("status")).isEqualTo(409);
    long approved =
        inTenant(
            jdbc ->
                jdbc.sql(
                        "SELECT count(*) FROM okr.objective WHERE status = 'DA_DUYET' AND id IN (:ids)")
                    .param("ids", ids)
                    .query(Long.class)
                    .single());
    assertThat(approved).isEqualTo(197);
    long events =
        inTenant(
            jdbc ->
                jdbc.sql(
                        "SELECT count(*) FROM okr.outbox_event WHERE topic ="
                            + " 'okr.objective.approved.v1' AND aggregate_id IN (:ids)")
                    .param("ids", ids)
                    .query(Long.class)
                    .single());
    assertThat(events).isEqualTo(197);
  }

  @Test
  void huyMucTieuBatBuocLyDoVaBaoDonViSoHuu() throws Exception {
    UUID id = pending(UUID.randomUUID(), AUTHOR);
    MvcResult shortReason = cancel(id, "\"0\"", "Ngắn");
    assertThat(shortReason.getResponse().getStatus()).isEqualTo(422);
    MvcResult r = cancel(id, "\"0\"", "Chủ trương công tác điều chỉnh theo nghị quyết mới.");
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(r).get("status").asText()).isEqualTo("DONG");
    assertResponseConforms(r, "/okr/objectives/{id}/cancel", PathItem.HttpMethod.POST);
    assertThat(count(id, "notify.message.requested.v1")).isEqualTo(1);
    assertThat(cancel(id, "\"1\"", "Huỷ lần thứ hai không hợp lệ.").getResponse().getStatus())
        .isEqualTo(409);
  }

  /** CORE-01-07: điều chỉnh giữa kỳ bằng phiên bản mới, phiên bản cũ hiệu lực tới khi duyệt. */
  @Test
  void dieuChinhGiuaKyTaoPhienBanMoi() throws Exception {
    UUID unit = UUID.randomUUID();
    UUID v1 = pending(unit, AUTHOR);
    assertThat(approve(v1, "\"0\"", null).getResponse().getStatus()).isEqualTo(200);
    UUID child = UUID.randomUUID();
    UUID childAuthor = UUID.randomUUID();
    inTenant(
        jdbc ->
            jdbc.sql(
                    "INSERT INTO okr.objective (id, period_id, period_bucket, owner_type,"
                        + " owner_unit_id, code, title, tier, status, parent_objective_id,"
                        + " created_by, updated_by) VALUES (?, ?, 20271, 'TAP_THE', ?, 'O1',"
                        + " 'Mục tiêu cấp dưới', 2, 'DA_DUYET', ?, ?, ?)")
                .params(child, period, UUID.randomUUID(), v1, childAuthor, childAuthor)
                .update());

    CALLER.set(AUTHOR);
    MvcResult created = revise(v1, "\"1\"", true);
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    assertResponseConforms(created, "/okr/objectives/{id}/revisions", PathItem.HttpMethod.POST);
    JsonNode v2 = body(created);
    UUID v2Id = UUID.fromString(v2.get("id").asText());
    assertThat(v2.get("versionNo").asInt()).isEqualTo(2);
    assertThat(v2.get("status").asText()).isEqualTo("CHO_DUYET");
    assertThat(v2.at("/keyResults/0/code").asText()).isEqualTo("KR 1.1");
    assertThat(v2.at("/bases/0/id").asText()).isEqualTo(basis.toString());
    assertThat(revise(v1, "\"1\"", false).getResponse().getStatus()).isEqualTo(409);

    // Phiên bản 1 vẫn hiệu lực: check-in vào KR của nó thành công; không sửa tại chỗ.
    UUID kr1 =
        inTenant(
            jdbc ->
                jdbc.sql("SELECT id FROM okr.key_result WHERE objective_id = ? AND code = 'KR 1.1'")
                    .param(v1)
                    .query(UUID.class)
                    .single());
    ObjectNode checkin = json.createObjectNode();
    checkin.put("status", "DA_GUI").put("value", 3).put("confidence", "CAO").put("channel", "WEB");
    MvcResult c =
        perform(
            post("/api/v1/okr/key-results/" + kr1 + "/checkins")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(checkin.toString()));
    assertThat(c.getResponse().getStatus()).isEqualTo(201);
    assertThat(listCodes(unit)).containsExactly(v1.toString());

    // CORE-01-12: KR mới thêm ở phiên bản 2 giữ giá trị đầu.
    UUID kr3 = UUID.randomUUID();
    inTenant(
        jdbc ->
            jdbc.sql(
                    "UPDATE okr.key_result SET weight = 0.3 WHERE objective_id = ? AND code = 'KR 1.2'")
                .param(v2Id)
                .update());
    inTenant(
        jdbc ->
            jdbc.sql(
                    "INSERT INTO okr.key_result (id, period_id, period_bucket, objective_id, code,"
                        + " title, metric_type, unit, baseline_value, target_value, direction,"
                        + " weight, due_date, verifier_person_id, checkin_frequency, status,"
                        + " created_by, updated_by) VALUES (?, ?, 20271, ?, 'KR 1.3', 'Kết quả"
                        + " bổ sung', 'SO_LUONG', 'chi bộ', 0, 5, 'TANG', 0.2, DATE '2027-03-31', ?,"
                        + " 'TUAN', 'CHO_DUYET', ?, ?)")
                .params(kr3, period, v2Id, VERIFIER, AUTHOR, AUTHOR)
                .update());

    CALLER.set(REVIEWER);
    assertThat(approve(v2Id, "\"1\"", null).getResponse().getStatus()).isEqualTo(200);
    assertThat(status(v1)).isEqualTo("DONG");
    Map<String, Object> carried =
        inTenant(
            jdbc ->
                jdbc.sql(
                        "SELECT (SELECT current_value FROM okr.key_result WHERE objective_id = ?"
                            + " AND code = 'KR 1.1') AS kr1, (SELECT current_value FROM"
                            + " okr.key_result WHERE id = ?) AS kr3, (SELECT progress FROM"
                            + " okr.key_result WHERE objective_id = ? AND code = 'KR 1.1') AS p1")
                    .params(v2Id, kr3, v2Id)
                    .query()
                    .singleRow());
    assertThat(((java.math.BigDecimal) carried.get("kr1")).intValue()).isEqualTo(3);
    assertThat(carried.get("kr3")).isNull();
    assertThat(((java.math.BigDecimal) carried.get("p1")).doubleValue()).isEqualTo(0.3);
    long copied =
        inTenant(
            jdbc ->
                jdbc.sql(
                        "SELECT count(*) FROM okr.kr_checkin c JOIN okr.key_result k"
                            + " ON k.id = c.key_result_id WHERE k.objective_id = ?")
                    .param(v2Id)
                    .query(Long.class)
                    .single());
    assertThat(copied).isZero();
    assertThat(listCodes(unit)).containsExactly(v2Id.toString());
    assertThat(perform(get("/api/v1/okr/objectives/" + v1 + "/history")).getResponse().getStatus())
        .isEqualTo(200);
    assertThat(count(v2Id, "notify.message.requested.v1")).isEqualTo(1);
    assertThat(status(child)).isEqualTo("DA_DUYET");
  }

  /** CORE-01-12: giao quyền check-in thay có hạn, thu hồi, hết hạn. */
  @Test
  void uyQuyenCheckinThayCoThoiHan() throws Exception {
    UUID id = pending(UUID.randomUUID(), AUTHOR);
    assertThat(approve(id, "\"0\"", null).getResponse().getStatus()).isEqualTo(200);
    UUID kr = krOf(id, "KR 1.2");
    UUID delegate = UUID.randomUUID();
    String until =
        java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(5).toString();

    MvcResult notOwner = delegate(kr, "\"" + krVersion(kr) + "\"", delegate, until);
    assertThat(notOwner.getResponse().getStatus()).isEqualTo(403);

    CALLER.set(AUTHOR);
    MvcResult set = delegate(kr, "\"" + krVersion(kr) + "\"", delegate, until);
    assertThat(set.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(set).at("/checkinDelegate/id").asText()).isEqualTo(delegate.toString());
    assertResponseConforms(set, "/okr/key-results/{id}/checkin-delegate", PathItem.HttpMethod.PUT);

    CALLER.set(delegate);
    MvcResult onBehalf = checkin(kr, "4");
    assertThat(onBehalf.getResponse().getStatus()).isEqualTo(201);
    assertThat(body(onBehalf).at("/checkin/onBehalf").asBoolean()).isTrue();
    MvcResult mine = perform(get("/api/v1/okr/key-results?owner=me&period=" + period));
    assertThat(mine.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .contains(kr.toString());

    CALLER.set(UUID.randomUUID());
    assertThat(checkin(kr, "5").getResponse().getStatus()).isEqualTo(403);

    CALLER.set(AUTHOR);
    MvcResult revoked =
        perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                    "/api/v1/okr/key-results/" + kr + "/checkin-delegate")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("If-Match", "\"" + krVersion(kr) + "\""));
    assertThat(revoked.getResponse().getStatus()).isEqualTo(200);
    assertThat(
            body(revoked).path("checkinDelegate").isMissingNode()
                || body(revoked).path("checkinDelegate").isNull())
        .isTrue();
    CALLER.set(delegate);
    assertThat(checkin(kr, "6").getResponse().getStatus()).isEqualTo(403);

    // Giao lại rồi để quá hạn: người được giao bị chặn.
    CALLER.set(AUTHOR);
    assertThat(delegate(kr, "\"" + krVersion(kr) + "\"", delegate, until).getResponse().getStatus())
        .isEqualTo(200);
    inTenant(
        jdbc ->
            jdbc.sql(
                    "UPDATE okr.kr_checkin_delegate SET valid_until = current_date - 2"
                        + " WHERE key_result_id = ? AND revoked_at IS NULL")
                .param(kr)
                .update());
    CALLER.set(delegate);
    assertThat(checkin(kr, "7").getResponse().getStatus()).isEqualTo(403);
    long history =
        inTenant(
            jdbc ->
                jdbc.sql("SELECT count(*) FROM okr.kr_checkin_delegate WHERE key_result_id = ?")
                    .param(kr)
                    .query(Long.class)
                    .single());
    assertThat(history).isEqualTo(2);
  }

  /**
   * CORE-01-11: nhắc check-in chạy nền với đồng hồ giả lập. KR tuần chưa check-in nhận đúng một
   * nhắc và người đứng đầu nhận báo quá hạn hai chu kỳ; KR đã check-in không nhận; KR tháng chỉ
   * nhận ở tuần có thứ Năm cuối tháng; hai bản sao chạy cùng lúc và chạy lại sau lỗi không gửi
   * trùng.
   */
  @Test
  void nhacCheckinNenKhongTrungKhiNhieuBanSaoVaChayLai() throws Exception {
    UUID head = UUID.randomUUID();
    UUID unit = UUID.randomUUID();
    when(orgDirectory.headsOf(any(), eq(unit), any())).thenReturn(List.of(head));
    UUID a = pending(unit, AUTHOR);
    assertThat(approve(a, "\"0\"", null).getResponse().getStatus()).isEqualTo(200);
    UUID b = pending(UUID.randomUUID(), AUTHOR);
    assertThat(approve(b, "\"0\"", null).getResponse().getStatus()).isEqualTo(200);
    UUID weekly = krOf(a, "KR 1.1");
    UUID checkedIn = krOf(a, "KR 1.2");
    UUID monthly = krOf(b, "KR 1.1");
    inTenant(
        jdbc -> {
          jdbc.sql("UPDATE okr.key_result SET checkin_frequency = 'THANG' WHERE objective_id = ?")
              .param(b)
              .update();
          jdbc.sql(
                  "INSERT INTO okr.kr_checkin (id, period_id, period_bucket, key_result_id,"
                      + " checkin_at, value, confidence, channel, idempotency_key, status,"
                      + " created_by, updated_by) VALUES (?, ?, 20271, ?,"
                      + " TIMESTAMPTZ '2027-02-09 09:00:00+07', 3, 'CAO', 'WEB', ?, 'DA_GUI', ?, ?)")
              .params(
                  UUID.randomUUID(),
                  period,
                  checkedIn,
                  UUID.randomUUID().toString(),
                  AUTHOR,
                  AUTHOR)
              .update();
          return null;
        });
    String due = "notify.message.requested.v1";

    // Thứ Năm 11/02/2027: hai bản sao chạy song song.
    var reminders = reminders("2027-02-11", 1, null);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    List<java.util.concurrent.Future<Integer>> runs = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      runs.add(pool.submit(() -> TenantContext.call(tenant, () -> reminders.run(tenant))));
    }
    for (var f : runs) {
      f.get();
    }
    pool.shutdown();
    assertThat(count(weekly, due)).isEqualTo(2);
    verify(orgDirectory, org.mockito.Mockito.atLeastOnce()).headsOf(any(), eq(unit), any());
    assertThat(count(checkedIn, due)).isZero();
    assertThat(count(monthly, due)).isZero();

    // Thứ Sáu chạy lại (pod khởi động lại): không gửi thêm.
    TenantContext.run(tenant, () -> reminders("2027-02-12", 500, null).run(tenant));
    assertThat(count(weekly, due)).isEqualTo(2);

    // Tuần sau: lỗi ở trang thứ hai, trang đầu đã ghi; chạy lại chỉ gửi phần còn thiếu.
    java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();
    try {
      TenantContext.run(tenant, () -> reminders("2027-02-18", 1, calls).run(tenant));
    } catch (IllegalStateException expected) {
      // lỗi giả lập giữa chừng
    }
    TenantContext.run(tenant, () -> reminders("2027-02-18", 1, null).run(tenant));
    TenantContext.run(tenant, () -> reminders("2027-02-18", 1, null).run(tenant));
    assertThat(count(weekly, due)).isEqualTo(4);
    assertThat(count(checkedIn, due)).isEqualTo(1);
    assertThat(count(monthly, due)).isZero();

    // Thứ Năm cuối tháng 25/02/2027: KR tháng mới được nhắc.
    TenantContext.run(tenant, () -> reminders("2027-02-25", 500, null).run(tenant));
    assertThat(count(monthly, due)).isEqualTo(1);
  }

  /**
   * Bộ nhắc dùng CSDL thật với đồng hồ cố định 07:00 giờ Việt Nam; {@code failAfter} khác null thì
   * lần ghi sự kiện thứ hai trở đi ném lỗi.
   */
  private vn.viettel.okrkpi.okr.application.OkrCheckinReminders reminders(
      String day, int pageSize, java.util.concurrent.atomic.AtomicInteger failAfter) {
    java.time.ZoneId zone = java.time.ZoneId.of("Asia/Ho_Chi_Minh");
    var outbox =
        new vn.viettel.okrkpi.okr.infrastructure.persistence.OutboxOkrReminderEvents(
            new OutboxWriter(
                new JdbcTemplate(okrDataSource),
                "okr",
                (topic, payload) -> new SerializedPayload(new byte[] {1}, 1L, 1),
                json,
                Clock.systemUtc()));
    vn.viettel.okrkpi.okr.application.port.OkrReminderEvents events =
        failAfter == null
            ? outbox
            : new vn.viettel.okrkpi.okr.application.port.OkrReminderEvents() {
              @Override
              public void checkinDue(
                  TenantId t,
                  vn.viettel.okrkpi.okr.application.port.OkrCheckinReminderRepository.DueKeyResult
                      kr,
                  List<UUID> to,
                  java.time.LocalDate end) {
                guard();
                outbox.checkinDue(t, kr, to, end);
              }

              @Override
              public void checkinOverdue(
                  TenantId t,
                  vn.viettel.okrkpi.okr.application.port.OkrCheckinReminderRepository.DueKeyResult
                      kr,
                  List<UUID> to,
                  int cycles) {
                guard();
                outbox.checkinOverdue(t, kr, to, cycles);
              }

              private void guard() {
                if (failAfter.incrementAndGet() > 1) {
                  throw new IllegalStateException("lỗi giả lập");
                }
              }
            };
    vn.viettel.okrkpi.config.api.PeriodApi periods =
        org.mockito.Mockito.mock(vn.viettel.okrkpi.config.api.PeriodApi.class);
    when(periods.findCurrent(any()))
        .thenReturn(
            Optional.of(
                new vn.viettel.okrkpi.config.api.PeriodSnapshot(
                    vn.viettel.okrkpi.shared.kernel.PeriodId.of(period),
                    tenant,
                    "Q1-2027",
                    "Quý I năm 2027",
                    java.time.LocalDate.of(2027, 1, 1),
                    java.time.LocalDate.of(2027, 3, 31),
                    vn.viettel.okrkpi.config.api.PeriodStatus.MO)));
    return new vn.viettel.okrkpi.okr.application.OkrCheckinReminders(
        new vn.viettel.okrkpi.okr.infrastructure.persistence.JdbcOkrCheckinReminderRepository(
            new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(okrDataSource)),
        periods,
        () -> null,
        () -> orgDirectory,
        events,
        new TransactionTemplate(okrTm),
        java.time.Clock.fixed(
            java.time.LocalDate.parse(day).atTime(7, 0).atZone(zone).toInstant(), zone),
        pageSize);
  }

  private UUID krOf(UUID objective, String code) throws Exception {
    return inTenant(
        jdbc ->
            jdbc.sql("SELECT id FROM okr.key_result WHERE objective_id = ? AND code = ?")
                .params(objective, code)
                .query(UUID.class)
                .single());
  }

  private long krVersion(UUID kr) throws Exception {
    return inTenant(
        jdbc ->
            jdbc.sql("SELECT row_version FROM okr.key_result WHERE id = ?")
                .param(kr)
                .query(Long.class)
                .single());
  }

  private MvcResult delegate(UUID kr, String ifMatch, UUID person, String until) throws Exception {
    return perform(
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                "/api/v1/okr/key-results/" + kr + "/checkin-delegate")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("If-Match", ifMatch)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                json.createObjectNode()
                    .put("delegatePersonId", person.toString())
                    .put("validUntil", until)
                    .put("reason", "Chủ sở hữu đi công tác dài ngày.")
                    .toString()));
  }

  private MvcResult checkin(UUID kr, String value) throws Exception {
    ObjectNode b = json.createObjectNode();
    b.put("status", "DA_GUI")
        .put("value", new java.math.BigDecimal(value))
        .put("confidence", "CAO")
        .put("channel", "WEB");
    return perform(
        post("/api/v1/okr/key-results/" + kr + "/checkins")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(b.toString()));
  }

  /** CORE-01-14: KR kiểu mốc trên CSDL thật, số ngày làm việc chậm theo lịch MOD-08. */
  @Test
  void krKieuMocGhiNgayDatVaSoNgayLamViecCham() throws Exception {
    UUID id = pending(UUID.randomUUID(), AUTHOR);
    UUID kr = krOf(id, "KR 1.2");
    inTenant(
        jdbc -> {
          jdbc.sql("UPDATE okr.objective SET status = 'NHAP' WHERE id = ?").param(id).update();
          return jdbc.sql(
                  "UPDATE okr.key_result SET metric_type = 'MOC', status = 'NHAP', target_value = 2"
                      + " WHERE id = ?")
              .param(kr)
              .update();
        });
    CALLER.set(AUTHOR);
    ObjectNode replace = json.createObjectNode();
    var items = replace.putArray("items");
    items
        .addObject()
        .put("seq", 2)
        .put("dueDate", "2026-10-16")
        .put("recognitionCondition", "Có quyết định");
    items
        .addObject()
        .put("seq", 1)
        .put("dueDate", "2026-10-02")
        .put("recognitionCondition", "Có dự thảo");
    MvcResult replaced =
        perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                    "/api/v1/okr/key-results/" + kr + "/milestones")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("If-Match", "\"" + krVersion(kr) + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(replace.toString()));
    assertThat(replaced.getResponse().getStatus()).isEqualTo(200);
    JsonNode declared = body(replaced).get("items");
    assertThat(declared).hasSize(2);
    assertThat(declared.get(0).get("seq").asInt()).isEqualTo(1);
    assertThat(declared.get(0).get("lateWorkdays").isNull()).isTrue();
    String first = declared.get(0).get("id").asText();
    // If-Match cũ sau khi đã khai báo: 409.
    assertThat(
            perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                            "/api/v1/okr/key-results/" + kr + "/milestones")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replace.toString()))
                .getResponse()
                .getStatus())
        .isEqualTo(409);
    inTenant(
        jdbc -> {
          jdbc.sql("UPDATE okr.objective SET status = 'DA_DUYET' WHERE id = ?").param(id).update();
          return jdbc.sql("UPDATE okr.key_result SET status = 'DA_DUYET' WHERE objective_id = ?")
              .param(id)
              .update();
        });
    // Hạn thứ Sáu 2/10/2026, đạt thứ Ba 6/10: chậm thứ Hai và thứ Ba (cuối tuần không tính).
    MvcResult achieved = achieve(kr, first, "2026-10-06", "VB-01/2026");
    assertThat(achieved.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(achieved).get("achievedAt").asText()).isEqualTo("2026-10-06");
    assertThat(body(achieved).get("lateWorkdays").asInt()).isEqualTo(2);
    java.math.BigDecimal current =
        inTenant(
            jdbc ->
                jdbc.sql("SELECT current_value FROM okr.key_result WHERE id = ?")
                    .param(kr)
                    .query(java.math.BigDecimal.class)
                    .single());
    assertThat(current).isEqualByComparingTo("1");
    // Đính chính: lần ghi sau thay lần trước; ngày ở tương lai bị từ chối.
    MvcResult corrected = achieve(kr, first, "2026-10-05", null);
    assertThat(body(corrected).get("lateWorkdays").asInt()).isEqualTo(1);
    assertThat(achieve(kr, first, "2999-01-01", null).getResponse().getStatus()).isEqualTo(422);

    MvcResult listed = perform(get("/api/v1/okr/key-results/" + kr + "/milestones"));
    assertThat(listed.getResponse().getHeader("ETag")).isEqualTo("\"" + krVersion(kr) + "\"");
    assertThat(body(listed).get("items").get(1).get("achievedAt").isNull()).isTrue();
    var published = TenantContext.call(tenant, () -> okrApi.listMilestones(tenant, kr));
    assertThat(published).hasSize(2);
    assertThat(published.get(0).lateWorkingDays()).isEqualTo(1);
    assertThat(perform(get("/api/v1/okr/key-results/" + kr)).getResponse().getStatus())
        .isEqualTo(200);
  }

  private MvcResult achieve(UUID kr, String milestone, String on, String evidence)
      throws Exception {
    ObjectNode b = json.createObjectNode().put("achievedAt", on);
    if (evidence != null) {
      b.put("evidenceRef", evidence);
    }
    return perform(
        post("/api/v1/okr/key-results/" + kr + "/milestones/" + milestone + "/achieve")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("If-Match", "\"" + krVersion(kr) + "\"")
            .contentType(MediaType.APPLICATION_JSON)
            .content(b.toString()));
  }

  private List<String> listCodes(UUID unit) throws Exception {
    MvcResult r =
        perform(get("/api/v1/okr/objectives?period=" + period + "&unit=" + unit + "&limit=50"));
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    List<String> ids = new ArrayList<>();
    body(r).get("items").forEach(i -> ids.add(i.get("id").asText()));
    return ids;
  }

  private MvcResult revise(UUID id, String ifMatch, boolean submit) throws Exception {
    return perform(
        post("/api/v1/okr/objectives/" + id + "/revisions")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("If-Match", ifMatch)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                json.createObjectNode()
                    .put("reason", "Bổ sung nhiệm vụ đột xuất theo thông báo kết luận.")
                    .put("submitImmediately", submit)
                    .toString()));
  }

  private MvcResult cancel(UUID id, String ifMatch, String reason) throws Exception {
    return perform(
        post("/api/v1/okr/objectives/" + id + "/cancel")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("If-Match", ifMatch)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.createObjectNode().put("reason", reason).toString()));
  }

  // ---------------------------------------------------------------- tiện ích

  /** Bộ OKR đủ điều kiện ở {@code CHO_DUYET}: một căn cứ, hai KR trọng số 50%. */
  private UUID pending(UUID unit, UUID author) throws Exception {
    UUID id = UUID.randomUUID();
    inTenant(
        jdbc -> {
          jdbc.sql(
                  "INSERT INTO okr.objective (id, period_id, period_bucket, owner_type,"
                      + " owner_unit_id, code, title, tier, status, created_by, updated_by)"
                      + " VALUES (?, ?, 20271, 'TAP_THE', ?, 'O1', 'Nâng chất lượng sinh hoạt chi"
                      + " bộ', 1, 'CHO_DUYET', ?, ?)")
              .params(id, period, unit, author, author)
              .update();
          jdbc.sql(
                  "INSERT INTO okr.objective_basis (period_bucket, objective_id, basis_id,"
                      + " created_by) VALUES (20271, ?, ?, ?)")
              .params(id, basis, author)
              .update();
          for (int k = 1; k <= 2; k++) {
            jdbc.sql(
                    "INSERT INTO okr.key_result (id, period_id, period_bucket, objective_id, code,"
                        + " title, metric_type, unit, baseline_value, target_value, direction,"
                        + " weight, due_date, verifier_person_id, checkin_frequency, status,"
                        + " created_by, updated_by) VALUES (?, ?, 20271, ?, ?, ?, 'SO_LUONG',"
                        + " 'chi bộ', 0, 10, 'TANG', 0.5, DATE '2027-03-31', ?, 'TUAN',"
                        + " 'CHO_DUYET', ?, ?)")
                .params(
                    UUID.randomUUID(),
                    period,
                    id,
                    "KR 1." + k,
                    k == 1
                        ? "Số chi bộ đạt chuẩn sinh hoạt tăng lên 10"
                        : "Số chuyên đề sinh hoạt được tổ chức đạt 10",
                    VERIFIER,
                    author,
                    author)
                .update();
          }
          return null;
        });
    return id;
  }

  private MvcResult approve(UUID id, String ifMatch, String comment) throws Exception {
    return approve(id, ifMatch, comment, UUID.randomUUID().toString());
  }

  private MvcResult approve(UUID id, String ifMatch, String comment, String idemKey)
      throws Exception {
    ObjectNode b = json.createObjectNode();
    if (comment != null) {
      b.put("comment", comment);
    }
    return perform(
        post("/api/v1/okr/objectives/" + id + "/approve")
            .header("Idempotency-Key", idemKey)
            .header("If-Match", ifMatch)
            .contentType(MediaType.APPLICATION_JSON)
            .content(b.toString()));
  }

  private MvcResult sendBack(UUID id, String ifMatch, String comment) throws Exception {
    return perform(
        post("/api/v1/okr/objectives/" + id + "/return")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("If-Match", ifMatch)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.createObjectNode().put("comment", comment).toString()));
  }

  private MvcResult withdraw(UUID id, String ifMatch) throws Exception {
    return perform(
        post("/api/v1/okr/objectives/" + id + "/withdraw")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("If-Match", ifMatch));
  }

  private String status(UUID id) throws Exception {
    return inTenant(
        jdbc ->
            jdbc.sql("SELECT status FROM okr.objective WHERE id = ?")
                .param(id)
                .query(String.class)
                .single());
  }

  private long count(UUID id, String topic) throws Exception {
    return inTenant(
        jdbc ->
            jdbc.sql("SELECT count(*) FROM okr.outbox_event WHERE aggregate_id = ? AND topic = ?")
                .params(id, topic)
                .query(Long.class)
                .single());
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
