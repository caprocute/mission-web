package vn.viettel.okrkpi.workflow.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.PathItem;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flowable.engine.ProcessEngineConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import vn.viettel.okrkpi.shared.job.JobIds;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.workflow.application.saga.SagaService;
import vn.viettel.okrkpi.workflow.domain.saga.SagaInstance;
import vn.viettel.okrkpi.workflow.domain.saga.SagaStatus;
import vn.viettel.okrkpi.workflow.infrastructure.release.LocalCellEngineGateway;
import vn.viettel.okrkpi.workflow.infrastructure.release.OutboxTemplateEventPublisher;
import vn.viettel.okrkpi.workflow.infrastructure.saga.FlowableSagaEngine;
import vn.viettel.okrkpi.workflow.infrastructure.saga.SagaJobSource;
import vn.viettel.okrkpi.workflow.infrastructure.saga.SagaWorker;
import vn.viettel.okrkpi.workflow.support.WorkflowIntegrationTest;
import vn.viettel.okrkpi.workflow.support.WorkflowTestDatabase;

/**
 * Thư viện và vòng đời mẫu luồng nội bộ SCR-1505 (DV-06-09, BS-01) trên PostgreSQL thật, engine
 * Flowable thật và engine thử H2: soạn có linter máy chủ, chạy bộ test với tham số của mọi
 * wf_binding (đọc chéo tenant), chặn mẫu vượt ngân sách câu SQL, đề nghị phát hành mở merge request
 * qua hộp thư lệnh, phê duyệt bốn mắt có bước lên AAL2, pipeline ký rồi saga ghi HIEU_LUC, phát sự
 * kiện qua outbox và triển khai cuốn chiếu; cell không khoẻ thì saga dừng và bù trừ. Phản hồi đối
 * chiếu lược đồ của {@code platform.yaml}.
 */
@SpringBootTest(
    properties = {
      "mission.workflow.release.cells=CELL-A,CELL-B",
      "mission.workflow.release.window.peak-hours=",
      "mission.workflow.release.window.period-ends=",
      "mission.workflow.release.cell-retry.max-attempts=2",
      "mission.workflow.release.cell-retry.initial-backoff=PT0.05S",
      "mission.workflow.release.cell-retry.max-backoff=PT0.05S",
      "mission.workflow.release.code-host.merge-request-base-url=https://ma-nguon.example.invalid/mr",
      "flowable.process.async.executor.default-async-job-acquire-wait-time=100ms",
      "flowable.process.async.executor.reset-expired-jobs-interval=200ms",
      "okrkpi.saga.worker.enabled=false"
    })
@Import(WorkflowTemplateApiIT.TestBeans.class)
class WorkflowTemplateApiIT extends WorkflowIntegrationTest {

  static final UUID PLATFORM = UUID.fromString("0192f1a2-0000-7000-8000-00000000b006");
  static final String BASE = "/api/v1/workflow/templates";

  /** Bản giả bộ tuần tự hoá Avro của outbox (không có Apicurio trong kiểm thử). */
  static class TestBeans {
    @Bean
    @Primary
    OutboxPayloadSerializer templateTestSerializer() {
      return (topic, payload) ->
          new OutboxPayloadSerializer.SerializedPayload(new byte[] {1}, 1L, 1);
    }
  }

  @Autowired private ObjectMapper json;
  @Autowired private SagaService sagas;
  @Autowired private FlowableSagaEngine sagaEngine;
  @Autowired private LocalCellEngineGateway cells;
  @Autowired private ProcessEngineConfiguration engineConfiguration;

  private final JdbcTemplate owner = WorkflowTestDatabase.owner();
  private final UUID author = UUID.randomUUID();
  private final UUID approver = UUID.randomUUID();
  private SagaWorker worker;

  @BeforeEach
  void worker() {
    // Nền chung tắt bộ thực thi nền; saga phát hành cần nó để chạy bước và hẹn giờ thử lại.
    if (!engineConfiguration.getAsyncExecutor().isActive()) {
      engineConfiguration.getAsyncExecutor().start();
    }
    worker = new SagaWorker(sagaEngine, sagas, 16, Duration.ofSeconds(1));
  }

  @AfterEach
  void healCells() {
    cells.simulateOutage("CELL-A", false);
    cells.simulateOutage("CELL-B", false);
  }

  // --- Tiện ích ----------------------------------------------------------------------------

  RequestPostProcessor asAuthor() {
    return user(PLATFORM, author, null, "platform_admin");
  }

  RequestPostProcessor asApprover() {
    return jwt()
        .jwt(
            j ->
                j.subject(approver.toString())
                    .claim("tenant_id", PLATFORM.toString())
                    .claim("roles", List.of("second_approver"))
                    .claim("acr", "aal2")
                    .claim("auth_time", Instant.now()));
  }

  RequestPostProcessor asPipeline() {
    return jwt()
        .jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenant_id", PLATFORM.toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_service_client"));
  }

  static String fixture(String name, String code) throws IOException {
    try (InputStream in = WorkflowTemplateApiIT.class.getResourceAsStream("/mau-soan/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("__MA__", code);
    }
  }

  static String code(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  static String etag(MvcResult r) {
    return r.getResponse().getHeader("ETag");
  }

  MvcResult create(String code, String xml) throws Exception {
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("templateCode", code);
    req.put("bpmnXml", xml);
    req.put(
        "paramSchema",
        Map.of(
            "type",
            "object",
            "properties",
            Map.of("remindAfter", Map.of("type", "string", "default", "P2D"))));
    return mvc.perform(
            post(BASE)
                .with(asAuthor())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
        .andReturn();
  }

  MvcResult get(UUID id) throws Exception {
    return mvc.perform(MockMvcRequestBuildersGet.of(BASE + "/" + id).with(asAuthor())).andReturn();
  }

  /** Chạy bộ test và chờ job kết thúc; trả thân job cuối. */
  JsonNode runTests(UUID id) throws Exception {
    MvcResult accepted =
        mvc.perform(
                post(BASE + "/{id}/test", id)
                    .with(asAuthor())
                    .header("Idempotency-Key", UUID.randomUUID().toString()))
            .andExpect(status().isAccepted())
            .andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.type").value("TEST_RUN"))
            .andReturn();
    assertResponseConforms(accepted, "/workflow/templates/{id}/test", PathItem.HttpMethod.POST);
    String location = accepted.getResponse().getHeader("Location");
    assertThat(location).startsWith("/api/v1/jobs/" + SagaJobSource.JOB_PREFIX + "-");
    JsonNode[] last = new JsonNode[1];
    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(200))
        .until(
            () -> {
              last[0] =
                  body(
                      mvc.perform(MockMvcRequestBuildersGet.of(location).with(asAuthor()))
                          .andExpect(status().isOk())
                          .andReturn());
              String s = last[0].path("status").asText();
              return "HOAN_TAT".equals(s) || "THAT_BAI".equals(s);
            });
    return last[0];
  }

  MvcResult submit(UUID id, String etag) throws Exception {
    return mvc.perform(
            post(BASE + "/{id}/submit", id)
                .with(asAuthor())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("If-Match", etag)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"Đề nghị phát hành\"}"))
        .andReturn();
  }

  MvcResult approve(UUID id, String etag, RequestPostProcessor who) throws Exception {
    return mvc.perform(
            post(BASE + "/{id}/approve", id)
                .with(who)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("If-Match", etag)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andReturn();
  }

  void sign(UUID id) throws Exception {
    mvc.perform(
            post("/internal/v1/workflow/templates/{id}/signature", id)
                .with(asPipeline())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "signature",
                            Base64.getEncoder()
                                .encodeToString("cosign-gia-lap".getBytes(StandardCharsets.UTF_8)),
                            "pipelineRef",
                            "pipeline-42"))))
        .andExpect(status().isOk());
  }

  /** Mã tác vụ saga có tiền tố {@code wf-} (cổng cell định tuyến theo tiền tố). */
  UUID sagaId(MvcResult accepted) throws Exception {
    String jobId = body(accepted).path("id").asText();
    assertThat(jobId).startsWith(SagaJobSource.JOB_PREFIX + "-");
    return JobIds.parse(SagaJobSource.JOB_PREFIX, jobId).orElseThrow();
  }

  SagaInstance load(UUID sagaId) throws Exception {
    return TenantContext.call(TenantId.of(PLATFORM), () -> sagas.find(sagaId).orElseThrow());
  }

  SagaInstance drive(UUID sagaId) throws Exception {
    SagaInstance[] last = new SagaInstance[1];
    try {
      await()
          .atMost(Duration.ofSeconds(60))
          .pollInterval(Duration.ofMillis(100))
          .until(
              () -> {
                worker.poll();
                worker.reconcile();
                last[0] = load(sagaId);
                return last[0].status().isTerminal();
              });
    } catch (org.awaitility.core.ConditionTimeoutException e) {
      throw new AssertionError("Saga chưa kết thúc: " + dump(sagaId), e);
    }
    return last[0];
  }

  String dump(UUID sagaId) {
    return owner.queryForList(
            "SELECT status, current_step_no, context::text FROM flowable.saga_instance WHERE id = ?",
            sagaId)
        + " "
        + owner.queryForList(
            "SELECT * FROM flowable.saga_step WHERE saga_instance_id = ? ORDER BY step_no", sagaId)
        + " EXEC "
        + owner.queryForList(
            "SELECT id_, act_id_, is_active_ FROM flowable.act_ru_execution WHERE proc_inst_id_ IN"
                + " (SELECT proc_inst_id_ FROM flowable.act_ru_execution WHERE business_key_ = ?)",
            "SAGA:" + sagaId)
        + " JOBS "
        + owner.queryForList(
            "SELECT 'job'::text k, id_::text, type_::text, handler_type_::text FROM flowable.act_ru_job"
                + " UNION ALL SELECT 'ext', id_, type_, handler_type_ FROM flowable.act_ru_external_job"
                + " UNION ALL SELECT 'dead', id_, type_, handler_type_ FROM flowable.act_ru_deadletter_job"
                + " UNION ALL SELECT 'timer', id_, type_, handler_type_ FROM flowable.act_ru_timer_job");
  }

  /** Soạn, chạy test đạt và đề nghị phát hành; trả mã và ETag ở {@code CHO_PHE_DUYET}. */
  Map.Entry<UUID, String> pendingTemplate(String code) throws Exception {
    MvcResult created = create(code, fixture("don-gian.bpmn20.xml", code));
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    UUID id = UUID.fromString(body(created).path("id").asText());
    JsonNode job = runTests(id);
    assertThat(job.path("status").asText()).isEqualTo("HOAN_TAT");
    MvcResult tested = get(id);
    assertThat(body(tested).path("status").asText()).isEqualTo("DAT_KIEM_THU");
    MvcResult submitted = submit(id, etag(tested));
    assertThat(submitted.getResponse().getStatus()).isEqualTo(200);
    return Map.entry(id, etag(submitted));
  }

  // --- Kịch bản ----------------------------------------------------------------------------

  @Test
  void engineThuDungBoThucThiNen() {
    assertThat(engineConfiguration.getAsyncExecutor().isActive()).isTrue();
  }

  @Test
  void vongDoiDayDuTuSoanDenHieuLucVaTrienKhaiCuonChieu() throws Exception {
    String code = code("mau-thu");
    MvcResult created = create(code, fixture("don-gian.bpmn20.xml", code));
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    assertThat(created.getResponse().getHeader("Location")).startsWith(BASE + "/");
    assertThat(etag(created)).isEqualTo("\"0\"");
    assertResponseConforms(created, "/workflow/templates", PathItem.HttpMethod.POST);
    JsonNode draft = body(created);
    assertThat(draft.path("status").asText()).isEqualTo("NHAP");
    assertThat(draft.path("versionNo").asInt()).isEqualTo(1);
    UUID id = UUID.fromString(draft.path("id").asText());

    // wf_binding của một tenant khác đang dùng mẫu: bộ test phải chạy với tham số của nó.
    UUID otherTenant = UUID.randomUUID();
    owner.update(
        "INSERT INTO flowable.wf_binding (id, tenant_id, process_kind, flow, wf_template_id,"
            + " params_json, created_by, updated_by) VALUES (?, ?, 'KIEM_THU', NULL, ?,"
            + " '{\"remindAfter\":\"P3D\"}'::jsonb, ?, ?)",
        UUID.randomUUID(),
        otherTenant,
        id,
        author,
        author);

    MvcResult read = get(id);
    assertResponseConforms(read, "/workflow/templates/{id}", PathItem.HttpMethod.GET);
    assertThat(body(read).path("tenantBindingCount").asInt()).isEqualTo(1);

    JsonNode job = runTests(id);
    assertThat(job.path("status").asText()).isEqualTo("HOAN_TAT");
    assertThat(
            owner.queryForObject(
                "SELECT binding_count FROM flowable.wf_template_test_run WHERE wf_template_id = ?",
                Integer.class,
                id))
        .isEqualTo(1);
    MvcResult tested = get(id);
    assertThat(body(tested).path("status").asText()).isEqualTo("DAT_KIEM_THU");
    assertThat(body(tested).path("testCount").asInt()).isPositive();

    MvcResult submitted = submit(id, etag(tested));
    assertThat(submitted.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(submitted, "/workflow/templates/{id}/submit", PathItem.HttpMethod.POST);
    JsonNode pending = body(submitted);
    assertThat(pending.path("status").asText()).isEqualTo("CHO_PHE_DUYET");
    assertThat(pending.path("mergeRequestUrl").asText())
        .isEqualTo("https://ma-nguon.example.invalid/mr/mr-" + id);
    assertThat(pending.path("testSuiteRef").asText()).isNotBlank();
    assertThat(
            owner.queryForObject(
                "SELECT count(*) FROM flowable.wf_code_host_command WHERE wf_template_id = ?"
                    + " AND command = 'MO_MR'",
                Integer.class,
                id))
        .isEqualTo(1);

    MvcResult accepted = approve(id, etag(submitted), asApprover());
    assertThat(accepted.getResponse().getStatus()).isEqualTo(202);
    assertResponseConforms(accepted, "/workflow/templates/{id}/approve", PathItem.HttpMethod.POST);
    assertThat(body(accepted).path("type").asText()).isEqualTo("WF_TEMPLATE_RELEASE");
    UUID sagaId = sagaId(accepted);

    MvcResult planned =
        mvc.perform(MockMvcRequestBuildersGet.of(BASE + "/" + id + "/rollouts").with(asApprover()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].cellCode").value("CELL-A"))
            .andExpect(jsonPath("$.items[0].status").value("CHO"))
            .andExpect(jsonPath("$.items[1].cellCode").value("CELL-B"))
            .andReturn();
    assertResponseConforms(planned, "/workflow/templates/{id}/rollouts", PathItem.HttpMethod.GET);

    // Chưa có chữ ký thì saga chờ, mẫu chưa hiệu lực.
    worker.poll();
    assertThat(body(get(id)).path("status").asText()).isEqualTo("CHO_PHE_DUYET");
    assertThat(
            owner.queryForObject(
                "SELECT count(*) FROM flowable.wf_code_host_command WHERE wf_template_id = ?"
                    + " AND command = 'DUYET_MR'",
                Integer.class,
                id))
        .isEqualTo(1);

    sign(id);
    SagaInstance done = drive(sagaId);
    assertThat(done.status()).isEqualTo(SagaStatus.HOAN_TAT);

    MvcResult released = get(id);
    JsonNode v = body(released);
    assertThat(v.path("status").asText()).isEqualTo("HIEU_LUC");
    assertThat(v.path("approvedBy").asText()).isEqualTo(approver.toString());
    Map<String, Object> row =
        owner.queryForMap(
            "SELECT approved_by, signature FROM flowable.wf_template WHERE id = ?", id);
    assertThat(row.get("approved_by")).isEqualTo(approver);
    assertThat((byte[]) row.get("signature"))
        .isEqualTo("cosign-gia-lap".getBytes(StandardCharsets.UTF_8));
    assertThat(
            owner.queryForObject(
                "SELECT count(*) FROM flowable.outbox_event WHERE topic = ? AND aggregate_id = ?",
                Integer.class,
                OutboxTemplateEventPublisher.TOPIC,
                id))
        .isEqualTo(1);
    mvc.perform(MockMvcRequestBuildersGet.of(BASE + "/" + id + "/rollouts").with(asApprover()))
        .andExpect(jsonPath("$.items[0].status").value("DA_TRIEN_KHAI"))
        .andExpect(jsonPath("$.items[1].status").value("DA_TRIEN_KHAI"))
        .andExpect(jsonPath("$.items[0].flowableDeploymentId").isNotEmpty());
    assertThat(repository.createProcessDefinitionQuery().processDefinitionKey(code).count())
        .isEqualTo(1);

    // Phiên bản HIEU_LUC là bất biến: sửa hay chạy test đều bị từ chối.
    MvcResult edit =
        mvc.perform(
                put(BASE + "/{id}", id)
                    .with(asAuthor())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .header("If-Match", etag(released))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "bpmnXml",
                                fixture("don-gian.bpmn20.xml", code),
                                "paramSchema",
                                Map.of("type", "object")))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("WORKFLOW-409-TEMPLATE-IMMUTABLE"))
            .andReturn();
    assertResponseConforms(edit, "/workflow/templates/{id}", PathItem.HttpMethod.PUT);
    MvcResult retest =
        mvc.perform(
                post(BASE + "/{id}/test", id)
                    .with(asAuthor())
                    .header("Idempotency-Key", UUID.randomUUID().toString()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("WORKFLOW-409-TEMPLATE-IMMUTABLE"))
            .andReturn();
    assertResponseConforms(retest, "/workflow/templates/{id}/test", PathItem.HttpMethod.POST);
    // Lớp phòng vệ ở CSDL: trigger chặn sửa nội dung phiên bản đã phát hành.
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                owner.update("UPDATE flowable.wf_template SET bpmn_xml = '<x/>' WHERE id = ?", id))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);

    MvcResult library =
        mvc.perform(
                MockMvcRequestBuildersGet.of(BASE + "?status=HIEU_LUC&q=" + code)
                    .with(asApprover()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].templateCode").value(code))
            .andExpect(jsonPath("$.items[0].tenantBindingCount").value(1))
            .andReturn();
    assertResponseConforms(library, "/workflow/templates", PathItem.HttpMethod.GET);
  }

  @Test
  void nguoiSoanTuPheDuyetBiTuChoi() throws Exception {
    Map.Entry<UUID, String> p = pendingTemplate(code("mau-bon-mat"));
    MvcResult denied =
        mvc.perform(
                post(BASE + "/{id}/approve", p.getKey())
                    .with(
                        jwt()
                            .jwt(
                                j ->
                                    j.subject(author.toString())
                                        .claim("tenant_id", PLATFORM.toString())
                                        .claim("roles", List.of("second_approver"))
                                        .claim("acr", "aal2")
                                        .claim("auth_time", Instant.now())))
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .header("If-Match", p.getValue())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("WORKFLOW-403-SAME-AUTHOR"))
            .andReturn();
    assertResponseConforms(denied, "/workflow/templates/{id}/approve", PathItem.HttpMethod.POST);
    assertThat(
            owner.queryForObject(
                "SELECT count(*) FROM flowable.saga_instance WHERE correlation_id = ?",
                Integer.class,
                p.getKey()))
        .isZero();
  }

  @Test
  void mauVuotNganSachSqlBiChanDeNghiPhatHanh() throws Exception {
    String code = code("mau-nang");
    MvcResult created = create(code, fixture("nhieu-hen-gio.bpmn20.xml", code));
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    UUID id = UUID.fromString(body(created).path("id").asText());
    JsonNode job = runTests(id);
    assertThat(job.path("status").asText()).isEqualTo("THAT_BAI");
    assertThat(job.path("message").asText()).contains("vượt ngân sách 20");
    Integer measured =
        owner.queryForObject(
            "SELECT max_sql_per_completion FROM flowable.wf_template_test_run"
                + " WHERE wf_template_id = ?",
            Integer.class,
            id);
    assertThat(measured).isGreaterThan(20);
    MvcResult still = get(id);
    assertThat(body(still).path("status").asText()).isEqualTo("NHAP");
    MvcResult blocked = submit(id, etag(still));
    assertThat(blocked.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(blocked).path("code").asText()).isEqualTo("WORKFLOW-409-NOT-TESTED");
    assertResponseConforms(blocked, "/workflow/templates/{id}/submit", PathItem.HttpMethod.POST);
  }

  @Test
  void cellKhongKhoeThiSagaDungVaBuTru() throws Exception {
    String code = code("mau-cell");
    Map.Entry<UUID, String> p = pendingTemplate(code);
    UUID id = p.getKey();
    cells.simulateOutage("CELL-B", true);
    MvcResult accepted = approve(id, p.getValue(), asApprover());
    assertThat(accepted.getResponse().getStatus()).isEqualTo(202);
    UUID sagaId = sagaId(accepted);
    sign(id);
    SagaInstance undone = drive(sagaId);
    assertThat(undone.status()).isEqualTo(SagaStatus.DA_BU_TRU);
    mvc.perform(MockMvcRequestBuildersGet.of(BASE + "/" + id + "/rollouts").with(asApprover()))
        .andExpect(jsonPath("$.items[0].cellCode").value("CELL-A"))
        .andExpect(jsonPath("$.items[0].status").value("DA_BU_TRU"))
        .andExpect(jsonPath("$.items[1].cellCode").value("CELL-B"))
        .andExpect(jsonPath("$.items[1].status").value("LOI"));
    assertThat(
            owner.queryForObject(
                "SELECT last_error FROM flowable.wf_template_rollout WHERE wf_template_id = ?"
                    + " AND cell_code = 'CELL-B'",
                String.class,
                id))
        .contains("không khoẻ");
    // Bù trừ đặt lại phiên bản trước làm mặc định: cell chưa từng có mẫu nên gỡ bản vừa triển khai.
    assertThat(repository.createProcessDefinitionQuery().processDefinitionKey(code).count())
        .isZero();
    // Phiên bản đã phát hành vẫn bất biến (hợp đồng: ký → HIEU_LUC → triển khai).
    assertThat(body(get(id)).path("status").asText()).isEqualTo("HIEU_LUC");
  }

  @Test
  void linterMayChuTuChoiVaLechPhienBan() throws Exception {
    String code = code("mau-sai");
    String bad =
        fixture("don-gian.bpmn20.xml", code)
            .replace(
                "<endEvent id=\"ketThuc\"/>",
                "<serviceTask id=\"goiNgoai\" flowable:class=\"vn.vd.GoiNgoai\"/><endEvent id=\"ketThuc\"/>");
    MvcResult rejected = create(code, bad);
    assertThat(rejected.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(rejected).path("errors").findValuesAsText("rule"))
        .contains("R4_KHONG_GOI_DONG_BO");
    assertResponseConforms(rejected, "/workflow/templates", PathItem.HttpMethod.POST);

    MvcResult created = create(code, fixture("don-gian.bpmn20.xml", code));
    UUID id = UUID.fromString(body(created).path("id").asText());
    MvcResult dup = create(code, fixture("don-gian.bpmn20.xml", code));
    assertThat(dup.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(dup).path("code").asText()).isEqualTo("WORKFLOW-409-DRAFT-EXISTS");
    assertResponseConforms(dup, "/workflow/templates", PathItem.HttpMethod.POST);

    MvcResult conflict =
        mvc.perform(
                put(BASE + "/{id}", id)
                    .with(asAuthor())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .header("If-Match", "\"9\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "bpmnXml",
                                fixture("don-gian.bpmn20.xml", code),
                                "paramSchema",
                                Map.of("type", "object")))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("WORKFLOW-409-VERSION-CONFLICT"))
            .andReturn();
    assertResponseConforms(conflict, "/workflow/templates/{id}", PathItem.HttpMethod.PUT);

    MvcResult saved =
        mvc.perform(
                put(BASE + "/{id}", id)
                    .with(asAuthor())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .header("If-Match", etag(created))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "bpmnXml",
                                fixture("qua-12-user-task.bpmn20.xml", code),
                                "paramSchema",
                                Map.of("type", "object")))))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors[0].rule").value("R9_TOI_DA_12_USER_TASK_MOI_NHANH"))
            .andReturn();
    assertResponseConforms(saved, "/workflow/templates/{id}", PathItem.HttpMethod.PUT);

    MvcResult ok =
        mvc.perform(
                put(BASE + "/{id}", id)
                    .with(asAuthor())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .header("If-Match", etag(created))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "bpmnXml",
                                fixture("nhieu-hen-gio.bpmn20.xml", code),
                                "paramSchema",
                                Map.of("type", "object")))))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"1\""))
            .andReturn();
    assertResponseConforms(ok, "/workflow/templates/{id}", PathItem.HttpMethod.PUT);

    mvc.perform(MockMvcRequestBuildersGet.of(BASE + "/" + UUID.randomUUID()).with(asAuthor()))
        .andExpect(status().isNotFound());
  }

  /** {@code MockMvcRequestBuilders.get} bị che bởi phương thức {@link #get(UUID)} của lớp. */
  static final class MockMvcRequestBuildersGet {
    private MockMvcRequestBuildersGet() {}

    static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder of(
        String uri) {
      return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(uri);
    }
  }
}
