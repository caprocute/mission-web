package vn.viettel.okrkpi.workflow.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.swagger.v3.oas.models.PathItem;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.flowable.common.engine.impl.identity.Authentication;
import org.flowable.engine.ManagementService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.job.api.Job;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import vn.viettel.okrkpi.events.EventContracts;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.authz.OpaDecision;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.inbox.InboxEndpoint;
import vn.viettel.okrkpi.shared.inbox.InboxListenerRegistrar;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.shared.inbox.InboxMetrics;
import vn.viettel.okrkpi.shared.inbox.InboxOutcome;
import vn.viettel.okrkpi.shared.inbox.InboxProperties;
import vn.viettel.okrkpi.shared.inbox.InboxRetryDlqPublisher;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.outbox.OutboxHeaders;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer;
import vn.viettel.okrkpi.workflow.support.WorkflowIntegrationTest;
import vn.viettel.okrkpi.workflow.support.WorkflowTestDatabase;
import vn.vtokr.org.v1.NhanSuDaThayDoi;
import vn.vtokr.org.v1.OrgBootstrap;
import vn.vtokr.org.v1.PhanCongDaThayDoi;

/**
 * Giám sát tiến trình theo mẫu SCR-1506 (DV-06-10, BS-01) trên PostgreSQL thật và engine Flowable
 * thật: danh sách chỉ đọc {@code flowable.wf_task_projection} (đếm câu SQL, không câu nào chạm
 * {@code ACT_*}), quản trị tenant chỉ thấy tenant mình, quản trị nền tảng xem cả cell đã che nội
 * dung; sơ đồ trả BPMN cùng bước đang chờ và đã qua; chuyển người xử lý và gia hạn bắt buộc
 * Idempotency-Key và lý do, kiểm tách vai trò qua OPA, cập nhật engine và bảng chiếu cùng giao
 * dịch, ghi sổ cái qua outbox đúng một lần; gia hạn dời hẹn giờ hạn theo ngày làm việc của lịch
 * nghỉ, vượt giới hạn tham số mẫu trả 422; không có đường sửa biến hay nhảy bước. Phản hồi đối
 * chiếu lược đồ {@code platform.yaml}.
 */
@SpringBootTest(
    properties = {
      "mission.workflow.monitor.cell-code=CELL-KT",
      "mission.workflow.monitor.max-extension-working-days=5"
    })
@Import(WorkflowMonitorApiIT.TestBeans.class)
class WorkflowMonitorApiIT extends WorkflowIntegrationTest {

  static final String KEY = "kiem-thu-gia-han";
  static final String CELL = "CELL-KT";
  static final UUID PLATFORM = UUID.fromString("0192f1a2-0000-7000-8000-00000000b006");
  static final Pattern ENGINE_TABLE = Pattern.compile("(?i)\\bact_[a-z]{2,3}_");

  /** Câu SQL đã gửi xuống JDBC trên luồng kiểm thử, khi đang ghi. */
  static final ThreadLocal<List<String>> RECORDED = new ThreadLocal<>();

  /** Bản giả bộ tuần tự hoá Avro của outbox và bộ ghi câu SQL bọc DataSource. */
  static class TestBeans {
    @Bean
    @Primary
    OutboxPayloadSerializer monitorTestSerializer() {
      return (topic, payload) ->
          new OutboxPayloadSerializer.SerializedPayload(new byte[] {1}, 1L, 1);
    }

    @Bean
    static BeanPostProcessor sqlRecorder() {
      return new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
          if (bean instanceof DataSource ds && !(bean instanceof RecordingDataSource)) {
            return new RecordingDataSource(ds);
          }
          return bean;
        }
      };
    }
  }

  /** DataSource ghi lại mọi câu SQL chuẩn bị hoặc thực thi khi {@link #RECORDED} được đặt. */
  static final class RecordingDataSource extends DelegatingDataSource {
    RecordingDataSource(DataSource target) {
      super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
      return wrap(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return wrap(super.getConnection(username, password));
    }

    private static Connection wrap(Connection target) {
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              recorder(target, Statement.class));
    }

    private static InvocationHandler recorder(Object target, Class<?> statementType) {
      return (proxy, method, args) -> {
        record(method, args);
        Object result = invoke(target, method, args);
        if (result instanceof Statement s && "createStatement".equals(method.getName())) {
          return Proxy.newProxyInstance(
              Statement.class.getClassLoader(),
              new Class<?>[] {statementType},
              (p, m, a) -> {
                record(m, a);
                return invoke(s, m, a);
              });
        }
        return result;
      };
    }

    private static void record(Method method, Object[] args) {
      List<String> sink = RECORDED.get();
      if (sink != null
          && args != null
          && args.length > 0
          && args[0] instanceof String sql
          && (method.getName().startsWith("prepare")
              || method.getName().startsWith("execute")
              || method.getName().equals("addBatch"))) {
        sink.add(sql);
      }
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
      try {
        return method.invoke(target, args);
      } catch (InvocationTargetException e) {
        throw e.getCause();
      }
    }
  }

  @Autowired private ObjectMapper json;
  @Autowired private ManagementService management;
  @Autowired private InboxListenerRegistrar inboxRegistrar;
  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager transactionManager;

  private final JdbcTemplate owner = WorkflowTestDatabase.owner();
  private final UUID tenant = UUID.randomUUID();
  private final UUID otherTenant = UUID.randomUUID();
  private final UUID unit = UUID.randomUUID();
  private final UUID admin = UUID.randomUUID();
  private final UUID initiator = UUID.randomUUID();
  private final UUID current = UUID.randomUUID();
  private final UUID newcomer = UUID.randomUUID();
  private final UUID period = UUID.randomUUID();

  @BeforeEach
  void monitorPolicyAndDefinition() {
    doAnswer(inv -> decide(inv.getArgument(0))).when(opa).decide(any());
    if (repository.createProcessDefinitionQuery().processDefinitionKey(KEY).count() == 0) {
      repository
          .createDeployment()
          .name(KEY)
          .addClasspathResource("bpmn-test/" + KEY + ".bpmn20.xml")
          .deploy();
    }
  }

  /**
   * OPA giả cho SCR-1506 theo ma trận: {@code platform_admin} phạm vi N, {@code tenant_admin} phạm
   * vi Tn (tài nguyên cùng tenant), vai trò khác bị từ chối; giữ luật tách vai trò 4.1 của nền
   * chung cho {@code SCR-0203.duyet}.
   */
  static OpaDecision decide(OpaInput input) {
    String action = input.action() == null ? "" : input.action();
    List<String> roles = input.subject().roles() == null ? List.of() : input.subject().roles();
    if (action.startsWith("SCR-1506.")) {
      if (roles.contains("platform_admin")) {
        return allow();
      }
      if (roles.contains("tenant_admin")
          && input.subject().tenantId().equals(input.resource().tenantId())) {
        return allow();
      }
      return new OpaDecision(false, Set.of("out_of_scope"), Set.of(), "kiem-thu", false);
    }
    Map<String, List<String>> participants =
        input.resource().participants() == null ? Map.of() : input.resource().participants();
    if (action.startsWith("SCR-0203.duyet")
        && participants.getOrDefault("initiator", List.of()).contains(input.subject().userId())) {
      return new OpaDecision(false, Set.of("separation_of_duties"), Set.of(), "kiem-thu", false);
    }
    return allow();
  }

  static OpaDecision allow() {
    return new OpaDecision(true, Set.of(), Set.of(), "kiem-thu", false);
  }

  // --- Tiện ích ----------------------------------------------------------------------------

  RequestPostProcessor tenantAdmin() {
    return user(tenant, admin, null, "tenant_admin");
  }

  RequestPostProcessor platformAdmin() {
    return user(PLATFORM, UUID.randomUUID(), null, "platform_admin");
  }

  String group() {
    return tenant + ":appraiser:" + unit;
  }

  /** Khởi tạo tiến trình kiểm thử gia hạn: có nhóm ứng viên hoặc người được giao, hạn và nhắc. */
  ProcessInstance startMonitored(UUID tenantId, UUID assignee, String candidateGroup, Instant due) {
    Map<String, Object> vars = new HashMap<>();
    vars.put("assigneeId", assignee == null ? null : assignee.toString());
    vars.put("candidateGroup", candidateGroup == null ? "" : candidateGroup);
    vars.put("dueAt", due == null ? null : Date.from(due));
    vars.put(
        "remindAt",
        Date.from(
            (due == null ? Instant.parse("2027-06-01T00:00:00Z") : due).minus(Duration.ofDays(1))));
    vars.put("level2Id", UUID.randomUUID().toString());
    vars.put("periodId", period.toString());
    Authentication.setAuthenticatedUserId(initiator.toString());
    try {
      return runtime
          .createProcessInstanceBuilder()
          .processDefinitionKey(KEY)
          .businessKey("APPRAISAL_PROFILE:" + UUID.randomUUID())
          .overrideProcessDefinitionTenantId(tenantId.toString())
          .variables(vars)
          .start();
    } finally {
      Authentication.setAuthenticatedUserId(null);
    }
  }

  void assign(UUID person, UUID orgUnit) {
    owner.update(
        "INSERT INTO org_replica.assignment_v (tenant_id, id, valid_from, person_id, org_unit_id,"
            + " kind, branch, source_version, source_event_id)"
            + " VALUES (?, ?, ?, ?, ?, 'CHINH', 'CHINH_QUYEN', 1, ?)",
        tenant,
        UUID.randomUUID(),
        java.sql.Date.valueOf(LocalDate.of(2020, 1, 1)),
        person,
        orgUnit,
        UUID.randomUUID());
  }

  int rowVersion(String taskId) {
    return owner.queryForObject(
        "SELECT row_version FROM flowable.wf_task_projection WHERE task_id = ?",
        Integer.class,
        taskId);
  }

  Map<String, Long> outbox(UUID tenantId) {
    Map<String, Long> counts = new LinkedHashMap<>();
    owner
        .queryForList(
            "SELECT topic, count(*) AS n FROM flowable.outbox_event WHERE tenant_id = ?"
                + " GROUP BY topic ORDER BY topic",
            tenantId)
        .forEach(r -> counts.put((String) r.get("topic"), ((Number) r.get("n")).longValue()));
    return counts;
  }

  JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  MockHttpServletRequestBuilder write(
      String action, String taskId, String idem, String ifMatch, Map<String, Object> req)
      throws Exception {
    MockHttpServletRequestBuilder b =
        post("/api/v1/workflow/tasks/{taskId}/" + action, taskId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(req));
    if (idem != null) {
      b.header("Idempotency-Key", idem);
    }
    if (ifMatch != null) {
      b.header("If-Match", ifMatch);
    }
    return b;
  }

  static Map<String, Object> reassignBody(UUID assignee, String reason) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("assigneeId", assignee.toString());
    m.put("reason", reason);
    return m;
  }

  static Map<String, Object> extendBody(Instant due, String reason) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("dueAt", due.toString());
    m.put("reason", reason);
    return m;
  }

  // ------------------------------------------------------------------ danh sách

  @Test
  void danhSachChiDocBangChieuVaChiTenantCuaQuanTriTenant() throws Exception {
    Instant past = Instant.now().minus(Duration.ofDays(3));
    ProcessInstance overdue = startMonitored(tenant, current, null, past);
    ProcessInstance orphan =
        startMonitored(tenant, null, null, Instant.parse("2027-03-01T03:00:00Z"));
    ProcessInstance healthy =
        startMonitored(tenant, null, group(), Instant.parse("2027-03-02T03:00:00Z"));
    startMonitored(otherTenant, current, null, past);

    List<String> sql = new ArrayList<>();
    RECORDED.set(sql);
    MvcResult all;
    try {
      all =
          mvc.perform(
                  get("/api/v1/workflow/instances")
                      .param("template", KEY)
                      .param("period", period.toString())
                      .with(tenantAdmin()))
              .andExpect(status().isOk())
              .andReturn();
    } finally {
      RECORDED.remove();
    }
    assertThat(sql).isNotEmpty();
    assertThat(sql).noneMatch(s -> ENGINE_TABLE.matcher(s).find());
    assertThat(sql).anyMatch(s -> s.contains("flowable.wf_task_projection"));
    assertResponseConforms(all, "/workflow/instances", PathItem.HttpMethod.GET);

    JsonNode items = body(all).get("items");
    assertThat(items).hasSize(3);
    assertThat(items.get(0).get("currentTaskId").asText()).isEqualTo(openTask(overdue).getId());
    assertThat(items.get(0).get("stuck").asBoolean()).isTrue();
    assertThat(items.get(0).get("stuckReason").asText()).startsWith("Quá hạn");
    assertThat(items.get(0).get("cellCode").asText()).isEqualTo(CELL);
    assertThat(items.get(0).get("currentActivityName").asText()).isEqualTo("Thẩm định hồ sơ");
    assertThat(items.get(0).get("templateVersion").asInt()).isPositive();
    assertThat(items.get(0).get("title").asText()).isEqualTo("Thẩm định hồ sơ");
    assertThat(items.get(1).get("currentTaskId").asText()).isEqualTo(openTask(orphan).getId());
    assertThat(items.get(1).get("stuck").asBoolean()).isTrue();
    assertThat(items.get(2).get("currentTaskId").asText()).isEqualTo(openTask(healthy).getId());
    assertThat(items.get(2).get("stuck").asBoolean()).isFalse();
    assertThat(items.get(2).get("candidateRole").asText()).isEqualTo("appraiser");
    assertThat(items.get(0).get("rowVersion").asInt())
        .isEqualTo(rowVersion(openTask(overdue).getId()));
    assertThat(items.get(2).get("rowVersion").asInt())
        .isEqualTo(rowVersion(openTask(healthy).getId()));

    // Lọc theo đơn vị xử lý: chỉ bước có nhóm ứng viên ở đơn vị đó.
    MvcResult byUnit =
        mvc.perform(
                get("/api/v1/workflow/instances")
                    .param("template", KEY)
                    .param("period", period.toString())
                    .param("unit", unit.toString())
                    .with(tenantAdmin()))
            .andExpect(status().isOk())
            .andReturn();
    assertResponseConforms(byUnit, "/workflow/instances", PathItem.HttpMethod.GET);
    assertThat(body(byUnit).get("items")).hasSize(1);
    assertThat(body(byUnit).get("items").get(0).get("currentTaskId").asText())
        .isEqualTo(openTask(healthy).getId());
    mvc.perform(
            get("/api/v1/workflow/instances")
                .param("template", KEY)
                .param("period", period.toString())
                .param("unit", UUID.randomUUID().toString())
                .with(tenantAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));

    MvcResult stuck =
        mvc.perform(
                get("/api/v1/workflow/instances")
                    .param("template", KEY)
                    .param("period", period.toString())
                    .param("stuck", "true")
                    .param("limit", "1")
                    .with(tenantAdmin()))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(body(stuck).get("items")).hasSize(1);
    String cursor = body(stuck).get("nextCursor").asText();
    MvcResult next =
        mvc.perform(
                get("/api/v1/workflow/instances")
                    .param("template", KEY)
                    .param("period", period.toString())
                    .param("stuck", "true")
                    .param("limit", "1")
                    .param("cursor", cursor)
                    .with(tenantAdmin()))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(body(next).get("items").get(0).get("currentTaskId").asText())
        .isEqualTo(openTask(orphan).getId());
    assertResponseConforms(next, "/workflow/instances", PathItem.HttpMethod.GET);

    mvc.perform(
            get("/api/v1/workflow/instances")
                .param("stuck", "false")
                .param("template", KEY)
                .param("period", period.toString())
                .with(tenantAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1));
  }

  @Test
  void quanTriTenantKhongXemTongHopCellVaVaiTroKhacBiTuChoi() throws Exception {
    mvc.perform(get("/api/v1/workflow/instances").param("cell", CELL).with(tenantAdmin()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("COMMON-403-OUT-OF-SCOPE"));
    mvc.perform(get("/api/v1/workflow/instances").with(user(tenant, admin, unit, "leader")))
        .andExpect(status().isForbidden());
  }

  @Test
  void quanTriNenTangXemTongHopCellDaCheNoiDung() throws Exception {
    startMonitored(tenant, current, null, Instant.now().minus(Duration.ofDays(1)));
    startMonitored(otherTenant, null, null, Instant.parse("2027-03-01T03:00:00Z"));
    List<String> sql = new ArrayList<>();
    RECORDED.set(sql);
    MvcResult cell;
    try {
      cell =
          mvc.perform(
                  get("/api/v1/workflow/instances")
                      .param("cell", CELL)
                      .param("template", KEY)
                      .param("period", period.toString())
                      .with(platformAdmin()))
              .andExpect(status().isOk())
              .andReturn();
    } finally {
      RECORDED.remove();
    }
    assertThat(sql).noneMatch(s -> ENGINE_TABLE.matcher(s).find());
    assertResponseConforms(cell, "/workflow/instances", PathItem.HttpMethod.GET);
    JsonNode items = body(cell).get("items");
    assertThat(items).hasSize(2);
    for (JsonNode item : items) {
      assertThat(item.has("title")).isFalse();
      assertThat(item.path("assigneeId").isNull() || item.path("assigneeId").isMissingNode())
          .isTrue();
      assertThat(item.get("stuck").asBoolean()).isTrue();
      assertThat(item.get("cellCode").asText()).isEqualTo(CELL);
    }
    mvc.perform(get("/api/v1/workflow/instances").param("cell", "CELL-KHAC").with(platformAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));
  }

  // ------------------------------------------------------------------ sơ đồ

  @Test
  void soDoTraBpmnCungBuocDangChoVaDaQua() throws Exception {
    ProcessInstance instance =
        startMonitored(tenant, current, null, Instant.parse("2027-03-01T03:00:00Z"));
    tasks.complete(openTask(instance).getId());
    MvcResult diagram =
        mvc.perform(
                get("/api/v1/workflow/instances/{businessKey}/diagram", instance.getBusinessKey())
                    .with(tenantAdmin()))
            .andExpect(status().isOk())
            .andReturn();
    assertResponseConforms(
        diagram, "/workflow/instances/{businessKey}/diagram", PathItem.HttpMethod.GET);
    JsonNode d = body(diagram);
    assertThat(d.get("businessKey").asText()).isEqualTo(instance.getBusinessKey());
    assertThat(d.get("templateCode").asText()).isEqualTo(KEY);
    assertThat(d.get("bpmnXml").asText()).contains("id=\"" + KEY + "\"");
    assertThat(d.get("activeActivityIds")).extracting(JsonNode::asText).containsExactly("pheDuyet");
    assertThat(d.get("completedActivityIds"))
        .extracting(JsonNode::asText)
        .contains("start", "thamDinh")
        .doesNotContain("pheDuyet", "f1");

    mvc.perform(
            get("/api/v1/workflow/instances/{businessKey}/diagram", instance.getBusinessKey())
                .with(user(otherTenant, admin, null, "tenant_admin")))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/api/v1/workflow/instances/{businessKey}/diagram", instance.getBusinessKey())
                .with(platformAdmin()))
        .andExpect(status().isOk());
    mvc.perform(
            get(
                    "/api/v1/workflow/instances/{businessKey}/diagram",
                    "APPRAISAL_PROFILE:" + UUID.randomUUID())
                .with(tenantAdmin()))
        .andExpect(status().isNotFound());
  }

  // ------------------------------------------------------------------ chuyển người xử lý

  @Test
  void chuyenNguoiXuLyCapNhatEngineBangChieuVaGhiSoCaiMotLan() throws Exception {
    assign(newcomer, unit);
    ProcessInstance instance =
        startMonitored(tenant, null, group(), Instant.parse("2027-03-01T03:00:00Z"));
    Task task = openTask(instance);
    Map<String, Object> varsBefore = runtime.getVariables(instance.getId());
    String etag = "\"" + rowVersion(task.getId()) + "\"";
    String idem = UUID.randomUUID().toString();
    Map<String, Object> req = reassignBody(newcomer, "Đồng chí thẩm định nghỉ phép.");

    MvcResult done =
        mvc.perform(write("reassign", task.getId(), idem, etag, req).with(tenantAdmin()))
            .andExpect(status().isOk())
            .andExpect(header().exists("ETag"))
            .andExpect(jsonPath("$.assigneeId").value(newcomer.toString()))
            .andReturn();
    assertResponseConforms(done, "/workflow/tasks/{taskId}/reassign", PathItem.HttpMethod.POST);
    Task after = tasks.createTaskQuery().taskId(task.getId()).singleResult();
    assertThat(after.getAssignee()).isEqualTo(newcomer.toString());
    assertThat(tasks.getTaskComments(task.getId(), "UY_QUYEN"))
        .anyMatch(c -> c.getFullMessage().contains("nghỉ phép"));
    assertThat(
            owner.queryForObject(
                "SELECT assignee_id FROM flowable.wf_task_projection WHERE task_id = ?",
                UUID.class,
                task.getId()))
        .isEqualTo(newcomer);
    assertThat(done.getResponse().getHeader("ETag"))
        .isEqualTo("\"" + rowVersion(task.getId()) + "\"");
    assertThat(outbox(tenant))
        .containsEntry("sys.audit.v1", 1L)
        .containsEntry("notify.message.requested.v1", 1L);
    assertThat(after.getTaskDefinitionKey()).isEqualTo("thamDinh");
    assertThat(runtime.getVariables(instance.getId())).isEqualTo(varsBefore);

    // Gửi lại cùng khoá: trả phản hồi đã lưu, không ghi thêm sổ cái.
    mvc.perform(write("reassign", task.getId(), idem, etag, req).with(tenantAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assigneeId").value(newcomer.toString()));
    assertThat(outbox(tenant))
        .containsEntry("sys.audit.v1", 1L)
        .containsEntry("notify.message.requested.v1", 1L);

    // Phiên bản cũ: 409.
    assign(current, unit);
    MvcResult stale =
        mvc.perform(
                write(
                        "reassign",
                        task.getId(),
                        UUID.randomUUID().toString(),
                        etag,
                        reassignBody(current, "Điều động"))
                    .with(tenantAdmin()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("WORKFLOW-409-VERSION-CONFLICT"))
            .andReturn();
    assertResponseConforms(stale, "/workflow/tasks/{taskId}/reassign", PathItem.HttpMethod.POST);
  }

  @Test
  void chuyenNguoiXuLyTuChoiKhiViPhamDieuKien() throws Exception {
    assign(initiator, unit);
    ProcessInstance instance =
        startMonitored(tenant, null, group(), Instant.parse("2027-03-01T03:00:00Z"));
    String taskId = openTask(instance).getId();
    String etag = "\"" + rowVersion(taskId) + "\"";

    MvcResult sod =
        mvc.perform(
                write(
                        "reassign",
                        taskId,
                        UUID.randomUUID().toString(),
                        etag,
                        reassignBody(initiator, "Chuyển cho người lập hồ sơ"))
                    .with(tenantAdmin()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("WORKFLOW-403-SELF-APPROVAL"))
            .andReturn();
    assertResponseConforms(sod, "/workflow/tasks/{taskId}/reassign", PathItem.HttpMethod.POST);

    MvcResult notEligible =
        mvc.perform(
                write(
                        "reassign",
                        taskId,
                        UUID.randomUUID().toString(),
                        etag,
                        reassignBody(newcomer, "Người ngoài đơn vị"))
                    .with(tenantAdmin()))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("WORKFLOW-422-ASSIGNEE-NOT-ELIGIBLE"))
            .andReturn();
    assertResponseConforms(
        notEligible, "/workflow/tasks/{taskId}/reassign", PathItem.HttpMethod.POST);

    assign(newcomer, unit);
    mvc.perform(
            write(
                    "reassign",
                    taskId,
                    UUID.randomUUID().toString(),
                    etag,
                    reassignBody(newcomer, " "))
                .with(tenantAdmin()))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("COMMON-422-VALIDATION"));
    Map<String, Object> noReason = new LinkedHashMap<>();
    noReason.put("assigneeId", newcomer.toString());
    mvc.perform(
            write("reassign", taskId, UUID.randomUUID().toString(), etag, noReason)
                .with(tenantAdmin()))
        .andExpect(status().isUnprocessableEntity());
    mvc.perform(
            write("reassign", taskId, null, etag, reassignBody(newcomer, "Thiếu khoá"))
                .with(tenantAdmin()))
        .andExpect(status().isBadRequest());
    mvc.perform(
            write(
                    "reassign",
                    taskId,
                    UUID.randomUUID().toString(),
                    null,
                    reassignBody(newcomer, "Thiếu If-Match"))
                .with(tenantAdmin()))
        .andExpect(status().isPreconditionRequired());
    mvc.perform(
            write(
                    "reassign",
                    taskId,
                    UUID.randomUUID().toString(),
                    etag,
                    reassignBody(newcomer, "Tenant khác"))
                .with(user(otherTenant, admin, null, "tenant_admin")))
        .andExpect(status().isNotFound());

    assertThat(openTask(instance).getAssignee()).isNull();
    assertThat(outbox(tenant)).doesNotContainKey("sys.audit.v1");
  }

  // ------------------------------------------------------------------ bản sao phân công EVT-15

  /**
   * Bộ xử lý inbox thật của thư viện nền trên CSDL của ứng dụng (svc_flowable, RLS bật); Kafka thay
   * bằng bản ghi dựng tay, thân Avro mã hoá bằng lớp sinh từ hợp đồng.
   */
  @SuppressWarnings("unchecked")
  InboxMessageProcessor inboxProcessor() {
    EventContracts.trustContractPackages();
    KafkaOperations<String, byte[]> kafka = mock(KafkaOperations.class);
    when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
    return new InboxMessageProcessor(
        new JdbcTemplate(dataSource),
        transactionManager,
        "flowable",
        (topic, headers, data) ->
            switch (topic) {
              case EventTopics.ORG_BOOTSTRAP_V1 -> decode(OrgBootstrap.class, data);
              case EventTopics.EVT_16_NHAN_SU_DA_THAY_DOI -> decode(NhanSuDaThayDoi.class, data);
              default -> decode(PhanCongDaThayDoi.class, data);
            },
        new InboxRetryDlqPublisher(kafka),
        new InboxMetrics(new SimpleMeterRegistry()),
        new InboxProperties.Retry(1, null, null, null),
        vn.viettel.okrkpi.shared.kernel.Clock.systemUtc());
  }

  /** Endpoint áp bản sao của topic (bỏ consumer đối soát R1 cùng nghe org.bootstrap.v1). */
  InboxEndpoint endpoint(String topic) {
    return inboxRegistrar.endpoints().stream()
        .filter(e -> e.topic().equals(topic) && !e.consumer().endsWith(".reconcile"))
        .findFirst()
        .orElseThrow();
  }

  static PhanCongDaThayDoi phanCong(
      UUID id, UUID person, UUID orgUnit, LocalDate to, long version) {
    return PhanCongDaThayDoi.newBuilder()
        .setAssignmentId(id)
        .setPersonId(person)
        .setUnitId(orgUnit)
        .setPositionId(null)
        .setJobTitleId(UUID.randomUUID())
        .setKind("CHINH")
        .setIsPrimary(true)
        .setValidFrom(LocalDate.of(2020, 1, 1))
        .setValidTo(to)
        .setSourceVersion(version)
        .setAggregateVersion((int) version)
        .setIsHead(false)
        .build();
  }

  private long offset;

  ConsumerRecord<String, byte[]> record(
      String topic, UUID tenantId, UUID aggregateId, long version, SpecificRecord payload) {
    ConsumerRecord<String, byte[]> r =
        new ConsumerRecord<>(topic, 0, offset++, tenantId + ":" + aggregateId, encode(payload));
    r.headers().add(OutboxHeaders.EVENT_ID, bytes(UUID.randomUUID().toString()));
    r.headers().add(OutboxHeaders.TENANT_ID, bytes(tenantId.toString()));
    r.headers().add(OutboxHeaders.AGGREGATE_VERSION, bytes(Long.toString(version)));
    r.headers().add(OutboxHeaders.CE_TYPE, bytes(payload.getSchema().getName()));
    r.headers().add(OutboxHeaders.CLASSIFICATION, bytes(Classification.C1.name()));
    r.headers().add(OutboxHeaders.OCCURRED_AT, bytes(Instant.now().toString()));
    return r;
  }

  static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  @SuppressWarnings("unchecked")
  static byte[] encode(SpecificRecord payload) {
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      var encoder = EncoderFactory.get().binaryEncoder(out, null);
      new SpecificDatumWriter<SpecificRecord>((Class<SpecificRecord>) payload.getClass())
          .write(payload, encoder);
      encoder.flush();
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static <T extends SpecificRecord> T decode(Class<T> type, byte[] data) {
    try {
      return new SpecificDatumReader<>(type)
          .read(null, DecoderFactory.get().binaryDecoder(data, null));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  MvcResult reassignTo(String taskId, UUID person) throws Exception {
    return mvc.perform(
            write(
                    "reassign",
                    taskId,
                    UUID.randomUUID().toString(),
                    "*",
                    reassignBody(person, "Điều động cán bộ theo quyết định."))
                .with(tenantAdmin()))
        .andReturn();
  }

  Map<String, Object> replica(UUID assignmentId) {
    return owner.queryForMap(
        "SELECT person_id, org_unit_id, valid_to::text AS valid_to, source_version, branch, kind"
            + " FROM org_replica.assignment_v WHERE id = ?",
        assignmentId);
  }

  @Test
  void suKienPhanCongMoKhoaChuyenNguoiXuLySuKienCuKhongGhiDe() throws Exception {
    InboxMessageProcessor processor = inboxProcessor();
    InboxEndpoint evt15 = endpoint(EventTopics.EVT_15_PHAN_CONG_DA_THAY_DOI);
    InboxEndpoint boot = endpoint(EventTopics.ORG_BOOTSTRAP_V1);
    assertThat(evt15.consumer()).isEqualTo("workflow.org-replica.assignment");
    ProcessInstance instance =
        startMonitored(tenant, null, group(), Instant.parse("2027-03-01T03:00:00Z"));
    String taskId = openTask(instance).getId();

    // Chưa có bản sao: 422.
    MvcResult before = reassignTo(taskId, newcomer);
    assertThat(before.getResponse().getStatus()).isEqualTo(422);
    assertResponseConforms(before, "/workflow/tasks/{taskId}/reassign", PathItem.HttpMethod.POST);

    // EVT-15 qua inbox (svc_flowable, RLS theo tenant của sự kiện).
    UUID assignmentId = UUID.randomUUID();
    ConsumerRecord<String, byte[]> first =
        record(
            EventTopics.EVT_15_PHAN_CONG_DA_THAY_DOI,
            tenant,
            assignmentId,
            5,
            phanCong(assignmentId, newcomer, unit, null, 5));
    assertThat(processor.process(evt15, first, null)).isEqualTo(InboxOutcome.PROCESSED);
    assertThat(processor.process(evt15, first, null)).isEqualTo(InboxOutcome.DUPLICATE_SKIPPED);
    assertThat(replica(assignmentId))
        .containsEntry("person_id", newcomer)
        .containsEntry("org_unit_id", unit)
        .containsEntry("source_version", 5L)
        .containsEntry("branch", "CHINH");

    // Bản cũ hơn qua topic thường bị inbox bỏ qua theo thứ tự phiên bản; qua nạp ban đầu (không
    // kiểm
    // thứ tự ở inbox) thì upsert theo source_version không ghi đè.
    ConsumerRecord<String, byte[]> olderEvt =
        record(
            EventTopics.EVT_15_PHAN_CONG_DA_THAY_DOI,
            tenant,
            assignmentId,
            3,
            phanCong(assignmentId, newcomer, unit, LocalDate.of(2021, 1, 1), 3));
    assertThat(processor.process(evt15, olderEvt, null)).isEqualTo(InboxOutcome.STALE_SKIPPED);
    OrgBootstrap olderBoot =
        OrgBootstrap.newBuilder()
            .setBatchId(UUID.randomUUID())
            .setSeq(1)
            .setIsLast(false)
            .setEntity(phanCong(assignmentId, newcomer, unit, LocalDate.of(2021, 1, 1), 4))
            .build();
    assertThat(
            processor.process(
                boot,
                record(EventTopics.ORG_BOOTSTRAP_V1, tenant, assignmentId, 4, olderBoot),
                null))
        .isEqualTo(InboxOutcome.PROCESSED);
    Map<String, Object> kept = replica(assignmentId);
    assertThat(kept.get("source_version")).isEqualTo(5L);
    assertThat(kept.get("valid_to")).isEqualTo("infinity");

    // Nạp ban đầu cho người khác (phiên bản mới) thì có hiệu lực.
    UUID other = UUID.randomUUID();
    UUID otherAssignment = UUID.randomUUID();
    OrgBootstrap fresh =
        OrgBootstrap.newBuilder()
            .setBatchId(UUID.randomUUID())
            .setSeq(2)
            .setIsLast(true)
            .setEntity(phanCong(otherAssignment, other, unit, null, 1))
            .build();
    assertThat(
            processor.process(
                boot,
                record(EventTopics.ORG_BOOTSTRAP_V1, tenant, otherAssignment, 1, fresh),
                null))
        .isEqualTo(InboxOutcome.PROCESSED);
    assertThat(replica(otherAssignment)).containsEntry("person_id", other);

    // Sự kiện của tenant khác không mở khoá cho tenant này.
    UUID foreign = UUID.randomUUID();
    assertThat(
            processor.process(
                evt15,
                record(
                    EventTopics.EVT_15_PHAN_CONG_DA_THAY_DOI,
                    otherTenant,
                    foreign,
                    1,
                    phanCong(foreign, current, unit, null, 1)),
                null))
        .isEqualTo(InboxOutcome.PROCESSED);
    assertThat(reassignTo(taskId, current).getResponse().getStatus()).isEqualTo(422);

    // Sau EVT-15: chuyển người xử lý thành công.
    MvcResult after = reassignTo(taskId, newcomer);
    assertThat(after.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(after, "/workflow/tasks/{taskId}/reassign", PathItem.HttpMethod.POST);
    assertThat(openTask(instance).getAssignee()).isEqualTo(newcomer.toString());
  }

  @Test
  void phanCongHetHanThiNguoiNhanLaiBi422() throws Exception {
    InboxMessageProcessor processor = inboxProcessor();
    InboxEndpoint evt15 = endpoint(EventTopics.EVT_15_PHAN_CONG_DA_THAY_DOI);
    UUID person = UUID.randomUUID();
    UUID assignmentId = UUID.randomUUID();
    assertThat(
            processor.process(
                evt15,
                record(
                    EventTopics.EVT_15_PHAN_CONG_DA_THAY_DOI,
                    tenant,
                    assignmentId,
                    1,
                    phanCong(assignmentId, person, unit, null, 1)),
                null))
        .isEqualTo(InboxOutcome.PROCESSED);
    String first =
        openTask(startMonitored(tenant, null, group(), Instant.parse("2027-03-01T03:00:00Z")))
            .getId();
    assertThat(reassignTo(first, person).getResponse().getStatus()).isEqualTo(200);

    // Điều động đi: SVC-04 phát trạng thái mới với ngày hết hiệu lực là hôm qua.
    LocalDate yesterday = LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).minusDays(1);
    assertThat(
            processor.process(
                evt15,
                record(
                    EventTopics.EVT_15_PHAN_CONG_DA_THAY_DOI,
                    tenant,
                    assignmentId,
                    2,
                    phanCong(assignmentId, person, unit, yesterday, 2)),
                null))
        .isEqualTo(InboxOutcome.PROCESSED);
    assertThat(replica(assignmentId)).containsEntry("source_version", 2L);
    String second =
        openTask(startMonitored(tenant, null, group(), Instant.parse("2027-03-01T03:00:00Z")))
            .getId();
    MvcResult rejected = reassignTo(second, person);
    assertThat(rejected.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(rejected).get("code").asText()).isEqualTo("WORKFLOW-422-ASSIGNEE-NOT-ELIGIBLE");
  }

  @Test
  void nhanSuDaNghiHuuTheoEvt16ThiBi422DuCoPhanCong() throws Exception {
    InboxMessageProcessor processor = inboxProcessor();
    InboxEndpoint evt16 = endpoint(EventTopics.EVT_16_NHAN_SU_DA_THAY_DOI);
    UUID person = UUID.randomUUID();
    assign(person, unit);
    String first =
        openTask(startMonitored(tenant, null, group(), Instant.parse("2027-03-01T03:00:00Z")))
            .getId();
    // Chưa có dòng nhân sự trong bản sao: chỉ dựa vào phân công (suy luận, wiki trang 14).
    assertThat(reassignTo(first, person).getResponse().getStatus()).isEqualTo(200);

    // EVT-16: đang công tác vẫn nhận được; nghỉ hưu thì 422 dù phân công còn hiệu lực.
    assertThat(
            processor.process(
                evt16,
                record(
                    EventTopics.EVT_16_NHAN_SU_DA_THAY_DOI,
                    tenant,
                    person,
                    1,
                    nhanSu(person, "DANG_CONG_TAC", 1)),
                null))
        .isEqualTo(InboxOutcome.PROCESSED);
    String second =
        openTask(startMonitored(tenant, null, group(), Instant.parse("2027-03-01T03:00:00Z")))
            .getId();
    assertThat(reassignTo(second, person).getResponse().getStatus()).isEqualTo(200);
    assertThat(
            processor.process(
                evt16,
                record(
                    EventTopics.EVT_16_NHAN_SU_DA_THAY_DOI,
                    tenant,
                    person,
                    2,
                    nhanSu(person, "NGHI_HUU", 2)),
                null))
        .isEqualTo(InboxOutcome.PROCESSED);
    String third =
        openTask(startMonitored(tenant, null, group(), Instant.parse("2027-03-01T03:00:00Z")))
            .getId();
    MvcResult rejected = reassignTo(third, person);
    assertThat(rejected.getResponse().getStatus()).isEqualTo(422);
    assertResponseConforms(rejected, "/workflow/tasks/{taskId}/reassign", PathItem.HttpMethod.POST);
    assertThat(body(rejected).get("code").asText()).isEqualTo("WORKFLOW-422-ASSIGNEE-NOT-ELIGIBLE");
    assertThat(tasks.createTaskQuery().taskId(third).singleResult().getAssignee()).isNull();

    // Nhân sự cùng mã ở tenant khác nghỉ hưu không ảnh hưởng tenant này (RLS theo tenant).
    UUID colleague = UUID.randomUUID();
    assign(colleague, unit);
    assertThat(
            processor.process(
                evt16,
                record(
                    EventTopics.EVT_16_NHAN_SU_DA_THAY_DOI,
                    otherTenant,
                    colleague,
                    1,
                    nhanSu(colleague, "THOI_VIEC", 1)),
                null))
        .isEqualTo(InboxOutcome.PROCESSED);
    assertThat(reassignTo(third, colleague).getResponse().getStatus()).isEqualTo(200);
  }

  static NhanSuDaThayDoi nhanSu(UUID id, String status, long version) {
    return NhanSuDaThayDoi.newBuilder()
        .setPersonId(id)
        .setDisplayName("Cán bộ kiểm thử")
        .setIsPartyMember(true)
        .setStatus(status)
        .setSourceVersion(version)
        .setAggregateVersion((int) version)
        .build();
  }

  // ------------------------------------------------------------------ gia hạn

  @Test
  void giaHanDoiHenGioTheoNgayLamViecVaGhiSoCaiMotLan() throws Exception {
    // Thứ Hai 01/03/2027 10:00 giờ Việt Nam; thứ Tư 03/03 là ngày nghỉ của tenant.
    Instant due = Instant.parse("2027-03-01T03:00:00Z");
    Instant requested = Instant.parse("2027-03-05T03:00:00Z");
    owner.update(
        "INSERT INTO flowable.wf_holiday_calendar (tenant_id, calendar_date, day_kind)"
            + " VALUES (?, ?, 'NGHI_LE')",
        tenant,
        java.sql.Date.valueOf(LocalDate.of(2027, 3, 3)));
    bindLimit(tenant, 3);
    ProcessInstance instance = startMonitored(tenant, current, null, due);
    Task task = openTask(instance);
    Map<String, Object> varsBefore = runtime.getVariables(instance.getId());
    Map<String, Instant> timersBefore = timers(instance);
    assertThat(timersBefore).containsEntry("denHan", due);
    String etag = "\"" + rowVersion(task.getId()) + "\"";
    String idem = UUID.randomUUID().toString();
    Map<String, Object> req = extendBody(requested, "Chờ bổ sung minh chứng.");

    MvcResult done =
        mvc.perform(write("extend", task.getId(), idem, etag, req).with(tenantAdmin()))
            .andExpect(status().isOk())
            .andExpect(header().exists("ETag"))
            .andReturn();
    assertResponseConforms(done, "/workflow/tasks/{taskId}/extend", PathItem.HttpMethod.POST);
    assertThat(Instant.parse(body(done).get("dueAt").asText())).isEqualTo(requested);
    assertThat(tasks.createTaskQuery().taskId(task.getId()).singleResult().getDueDate().toInstant())
        .isEqualTo(requested);
    Map<String, Instant> timersAfter = timers(instance);
    assertThat(timersAfter).containsEntry("denHan", requested);
    assertThat(timersAfter.get("nhacTruoc")).isEqualTo(timersBefore.get("nhacTruoc"));
    assertThat(
            owner.queryForObject(
                "SELECT due_at FROM flowable.wf_task_projection WHERE task_id = ?",
                java.sql.Timestamp.class,
                task.getId()))
        .isEqualTo(java.sql.Timestamp.from(requested));
    int version = rowVersion(task.getId());
    assertThat(done.getResponse().getHeader("ETag")).isEqualTo("\"" + version + "\"");
    assertThat(version).isGreaterThan(Integer.parseInt(etag.replace("\"", "")));
    assertThat(outbox(tenant)).containsEntry("sys.audit.v1", 1L);
    assertThat(runtime.getVariables(instance.getId())).isEqualTo(varsBefore);
    assertThat(tasks.getTaskComments(task.getId(), "GIA_HAN")).hasSize(1);

    mvc.perform(write("extend", task.getId(), idem, etag, req).with(tenantAdmin()))
        .andExpect(status().isOk());
    assertThat(outbox(tenant)).containsEntry("sys.audit.v1", 1L);
    assertThat(tasks.getTaskComments(task.getId(), "GIA_HAN")).hasSize(1);
  }

  @Test
  void giaHanVuotThamSoMauHoacMacDinhTra422() throws Exception {
    Instant due = Instant.parse("2027-03-01T03:00:00Z");
    // Không có ngày nghỉ: 01/03 → 05/03 là 4 ngày làm việc, vượt tham số mẫu 3 ngày.
    bindLimit(tenant, 3);
    ProcessInstance instance = startMonitored(tenant, current, null, due);
    String taskId = openTask(instance).getId();
    String etag = "\"" + rowVersion(taskId) + "\"";
    MvcResult over =
        mvc.perform(
                write(
                        "extend",
                        taskId,
                        UUID.randomUUID().toString(),
                        etag,
                        extendBody(Instant.parse("2027-03-05T03:00:00Z"), "Chờ minh chứng"))
                    .with(tenantAdmin()))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("COMMON-422-VALIDATION"))
            .andReturn();
    assertResponseConforms(over, "/workflow/tasks/{taskId}/extend", PathItem.HttpMethod.POST);

    // Tenant khác không có tham số: giới hạn mặc định 5 ngày làm việc của cấu hình.
    ProcessInstance other = startMonitored(otherTenant, current, null, due);
    String otherTask = openTask(other).getId();
    RequestPostProcessor otherAdmin = user(otherTenant, admin, null, "tenant_admin");
    mvc.perform(
            write(
                    "extend",
                    otherTask,
                    UUID.randomUUID().toString(),
                    "\"" + rowVersion(otherTask) + "\"",
                    extendBody(Instant.parse("2027-03-15T03:00:00Z"), "Quá dài"))
                .with(otherAdmin))
        .andExpect(status().isUnprocessableEntity());
    mvc.perform(
            write(
                    "extend",
                    otherTask,
                    UUID.randomUUID().toString(),
                    "\"" + rowVersion(otherTask) + "\"",
                    extendBody(Instant.parse("2027-03-05T03:00:00Z"), "Trong giới hạn"))
                .with(otherAdmin))
        .andExpect(status().isOk());

    mvc.perform(
            write(
                    "extend",
                    taskId,
                    UUID.randomUUID().toString(),
                    etag,
                    extendBody(due.minus(Duration.ofHours(1)), "Lùi hạn"))
                .with(tenantAdmin()))
        .andExpect(status().isUnprocessableEntity());
    mvc.perform(
            write(
                    "extend",
                    taskId,
                    UUID.randomUUID().toString(),
                    etag,
                    extendBody(Instant.parse("2027-03-02T03:00:00Z"), ""))
                .with(tenantAdmin()))
        .andExpect(status().isUnprocessableEntity());
    mvc.perform(
            write(
                    "extend",
                    taskId,
                    null,
                    etag,
                    extendBody(Instant.parse("2027-03-02T03:00:00Z"), "x"))
                .with(tenantAdmin()))
        .andExpect(status().isBadRequest());
    assertThat(tasks.createTaskQuery().taskId(taskId).singleResult().getDueDate().toInstant())
        .isEqualTo(due);
    assertThat(outbox(tenant)).doesNotContainKey("sys.audit.v1");
  }

  void bindLimit(UUID tenantId, int days) {
    UUID templateId = UUID.randomUUID();
    owner.update(
        "INSERT INTO flowable.wf_template (id, tenant_id, template_code, version_no, bpmn_xml,"
            + " bpmn_sha256, status, created_by, updated_by)"
            + " VALUES (?, ?, ?, 1, '<definitions/>', decode(repeat('ab', 32), 'hex'), 'HIEU_LUC',"
            + " ?, ?)",
        templateId,
        tenantId,
        KEY,
        admin,
        admin);
    owner.update(
        "INSERT INTO flowable.wf_binding (id, tenant_id, process_kind, wf_template_id, params_json,"
            + " created_by, updated_by) VALUES (?, ?, 'DANH_GIA', ?, ?::jsonb, ?, ?)",
        UUID.randomUUID(),
        tenantId,
        templateId,
        "{\"gia_han_toi_da_ngay_lam_viec\": " + days + "}",
        admin,
        admin);
  }

  Map<String, Instant> timers(ProcessInstance instance) {
    Map<String, Instant> byElement = new HashMap<>();
    for (Job job : management.createTimerJobQuery().processInstanceId(instance.getId()).list()) {
      byElement.put(job.getElementId(), job.getDuedate().toInstant());
    }
    return byElement;
  }

  // ------------------------------------------------------------------ không sửa biến, không nhảy
  // bước

  @Test
  void khongCoDuongSuaBienHayNhayBuoc() throws Exception {
    ProcessInstance instance =
        startMonitored(tenant, current, null, Instant.parse("2027-03-01T03:00:00Z"));
    String taskId = openTask(instance).getId();
    for (String path :
        List.of(
            "/api/v1/workflow/tasks/{taskId}/variables",
            "/api/v1/workflow/tasks/{taskId}/jump",
            "/api/v1/workflow/tasks/{taskId}/move",
            "/api/v1/workflow/tasks/{taskId}")) {
      int post =
          mvc.perform(
                  post(path, taskId)
                      .with(tenantAdmin())
                      .header("Idempotency-Key", UUID.randomUUID().toString())
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"activityId\":\"pheDuyet\",\"variables\":{\"x\":1}}"))
              .andReturn()
              .getResponse()
              .getStatus();
      int putStatus =
          mvc.perform(
                  put(path, taskId)
                      .with(tenantAdmin())
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{}"))
              .andReturn()
              .getResponse()
              .getStatus();
      int patchStatus =
          mvc.perform(
                  patch(path, taskId)
                      .with(tenantAdmin())
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{}"))
              .andReturn()
              .getResponse()
              .getStatus();
      assertThat(List.of(post, putStatus, patchStatus)).allMatch(s -> s == 404 || s == 405);
    }
    String docs =
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    JsonNode paths = json.readTree(docs).get("paths");
    List<String> taskPaths = new ArrayList<>();
    paths
        .fieldNames()
        .forEachRemaining(
            p -> {
              if (p.contains("/workflow/tasks/") || p.contains("/workflow/instances")) {
                taskPaths.add(p);
              }
            });
    assertThat(taskPaths)
        .noneMatch(p -> p.contains("variable") || p.contains("jump") || p.contains("move"));
    // Trường lạ trong thân gia hạn không chuyển thành biến tiến trình.
    Map<String, Object> varsBefore = runtime.getVariables(instance.getId());
    Map<String, Object> req = extendBody(Instant.parse("2027-03-02T03:00:00Z"), "Gia hạn");
    req.put("variables", Map.of("decision", "TRA_LAI"));
    req.put("activityId", "pheDuyet");
    mvc.perform(
            write(
                    "extend",
                    taskId,
                    UUID.randomUUID().toString(),
                    "\"" + rowVersion(taskId) + "\"",
                    req)
                .with(tenantAdmin()))
        .andExpect(status().isOk());
    assertThat(runtime.getVariables(instance.getId())).isEqualTo(varsBefore);
    assertThat(openTask(instance).getTaskDefinitionKey()).isEqualTo("thamDinh");
  }
}
