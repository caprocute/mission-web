package vn.viettel.okrkpi.task.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.PathItem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
import vn.viettel.okrkpi.product.application.e2ee.port.EvidenceKeyWraps.NewWrap;
import vn.viettel.okrkpi.product.application.e2ee.port.EvidenceKeyWraps.StoredWrap;
import vn.viettel.okrkpi.product.infrastructure.persistence.JdbcEvidenceKeyWraps;
import vn.viettel.okrkpi.shared.audit.AuditEvent;
import vn.viettel.okrkpi.shared.audit.AuditLogger;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;

/**
 * Nội dung nhiệm vụ mật C3 (CORE-07-06) trên PostgreSQL thật: lưu thiếu bản bọc cho khoá phục hồi
 * 422, người nhận ngoài nhiệm vụ 403, người không giữ bản bọc không đọc được, chia sẻ cho người
 * nghiệm thu rồi thu hồi thì người đó mất quyền tải bản mã, {@code If-Match} lệch 409. Bản mã mang
 * dấu đánh dấu chỉ nằm ở cột {@code ciphertext}: không có trong outbox, sự kiện kiểm toán hay nhật
 * ký. Kho {@code product.e2ee_key_wrap} giữ đúng một bản bọc còn hiệu lực cho mỗi người nhận.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(TaskSecretContentApiIT.SecretTestConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class TaskSecretContentApiIT extends ContractConformanceTest {

  static final UUID ASSIGNER = UUID.fromString("01927a02-0000-7000-8000-00000000c706");
  static final UUID LEAD = UUID.fromString("01927a02-0000-7000-8000-00000000d706");
  static final UUID ACCEPTOR = UUID.fromString("01927a02-0000-7000-8000-00000000f706");
  static final UUID OUTSIDER = UUID.fromString("01927a02-0000-7000-8000-00000000a706");
  static final UUID RECOVERY = UUID.fromString("01927a02-0000-7000-8000-00000000e706");
  static final UUID UNIT = UUID.fromString("01927a02-0000-7000-8000-000000001706");
  static final int BUCKET = 20294;
  static final String MARKER = "DAU-DANH-DAU-C3-ROI-RANH-GIOI";

  static final List<AuditEvent> AUDITS = new CopyOnWriteArrayList<>();

  /** Ghi lại sự kiện kiểm toán để quét dấu đánh dấu. */
  static class SecretTestConfig {

    @Bean
    @Primary
    AuditLogger secretTestAudit() {
      return AUDITS::add;
    }
  }

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;

  @Autowired
  @Qualifier("taskDataSource") private DataSource taskDataSource;

  @Autowired
  @Qualifier("taskTransactionManager") private PlatformTransactionManager taskTm;

  @Autowired
  @Qualifier("configDataSource") private DataSource configDataSource;

  @Autowired
  @Qualifier("configTransactionManager") private PlatformTransactionManager configTm;

  @Autowired
  @Qualifier("productDataSource") private DataSource productDataSource;

  @Autowired
  @Qualifier("productTransactionManager") private PlatformTransactionManager productTm;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID period = UUID.randomUUID();
  private final String recoveryDevice = device("phuc-hoi");

  @Override
  protected ContractModule contractModule() {
    return ContractModule.TASKS;
  }

  @BeforeAll
  void seed() throws Exception {
    TenantContext.call(
        tenant,
        () ->
            new TransactionTemplate(configTm)
                .execute(
                    s -> {
                      JdbcClient c = JdbcClient.create(configDataSource);
                      c.sql(
                              "INSERT INTO config.period (id, code, name, kind, flow, start_date,"
                                  + " end_date, period_bucket, status, created_by, updated_by)"
                                  + " VALUES (?, 'Q2-2032-MAT', 'Quý II năm 2032', 'QUY',"
                                  + " 'NHA_NUOC', DATE '2032-04-01', DATE '2032-06-30', ?, 'MO',"
                                  + " ?, ?)")
                          .params(period, BUCKET, ASSIGNER, ASSIGNER)
                          .update();
                      c.sql(
                              "INSERT INTO config.tenant_setting (id, created_by, updated_by,"
                                  + " setting_key, value, value_schema) VALUES (?, ?, ?,"
                                  + " 'e2ee.recovery-key', ?::jsonb, 'json.object')")
                          .params(
                              UUID.randomUUID(),
                              ASSIGNER,
                              ASSIGNER,
                              "{\"fingerprints\":[\""
                                  + recoveryDevice
                                  + "\"],\"recipientId\":\""
                                  + RECOVERY
                                  + "\"}")
                          .update();
                      return null;
                    }));
    inTask(
        jdbc -> {
          for (String table : List.of("task", "task_assignee", "task_secret_content")) {
            jdbc.sql("SELECT task.fn_create_period_partitions(?::regclass, ?)")
                .params("task." + table, BUCKET)
                .query()
                .listOfRows();
          }
          return null;
        });
  }

  @AfterEach
  void resetCaller() {
    TestTaskCallers.reset();
  }

  @Test
  void luuDocChiaSeThuHoiVaKhongLoC3(CapturedOutput output) throws Exception {
    UUID task = task(true);
    byte[] ciphertext = ("ban-ma:" + MARKER).getBytes(StandardCharsets.UTF_8);

    as(LEAD);
    // Thiếu bản bọc cho khoá phục hồi tổ chức.
    MvcResult noRecovery =
        perform(save(task, "\"0\"", ciphertext, List.of(wrap(LEAD, device("chu-tri")))));
    assertThat(noRecovery.getResponse().getStatus()).isEqualTo(422);
    // Người nhận không tham gia nhiệm vụ.
    MvcResult outsider =
        perform(
            save(
                task,
                "\"0\"",
                ciphertext,
                List.of(
                    wrap(LEAD, device("chu-tri")),
                    wrap(OUTSIDER, device("ngoai")),
                    wrap(RECOVERY, recoveryDevice))));
    assertThat(outsider.getResponse().getStatus()).isEqualTo(403);
    // Băm khai báo không khớp bản mã.
    MvcResult badHash =
        perform(
            save(
                    task,
                    "\"0\"",
                    ciphertext,
                    List.of(wrap(LEAD, device("chu-tri")), wrap(RECOVERY, recoveryDevice)))
                .content(
                    body(
                        "ban-ma-khac".getBytes(StandardCharsets.UTF_8),
                        sha256Hex(ciphertext),
                        List.of(wrap(LEAD, device("chu-tri")), wrap(RECOVERY, recoveryDevice)))));
    assertThat(badHash.getResponse().getStatus()).isEqualTo(422);

    MvcResult saved =
        perform(
            save(
                task,
                "\"0\"",
                ciphertext,
                List.of(wrap(LEAD, device("chu-tri")), wrap(RECOVERY, recoveryDevice))));
    assertThat(saved.getResponse().getStatus()).isEqualTo(200);
    assertThat(saved.getResponse().getHeader("ETag")).isEqualTo("\"0\"");
    assertThat(body(saved).get("recipientCount").asInt()).isEqualTo(2);
    assertResponseConforms(saved, "/tasks/{id}/secret-content", PathItem.HttpMethod.PUT);

    MvcResult read = perform(get("/api/v1/tasks/" + task + "/secret-content"));
    assertThat(read.getResponse().getStatus()).isEqualTo(200);
    assertThat(Base64.getDecoder().decode(body(read).get("ciphertext").asText()))
        .isEqualTo(ciphertext);
    assertThat(body(read).get("keyWrap").get("deviceId").asText()).isEqualTo(device("chu-tri"));
    assertResponseConforms(read, "/tasks/{id}/secret-content", PathItem.HttpMethod.GET);

    as(ACCEPTOR);
    assertThat(status(get("/api/v1/tasks/" + task + "/secret-content"))).isEqualTo(403);
    // Người không giữ bản bọc không tự cấp khoá cho mình được.
    assertThat(status(share(task, List.of(wrap(ACCEPTOR, device("nghiem-thu")))))).isEqualTo(403);

    as(LEAD);
    MvcResult shared = perform(share(task, List.of(wrap(ACCEPTOR, device("nghiem-thu")))));
    assertThat(shared.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(shared).get("recipientCount").asInt()).isEqualTo(3);
    assertResponseConforms(shared, "/tasks/{id}/secret-content/shares", PathItem.HttpMethod.POST);
    assertThat(status(share(task, List.of(wrap(OUTSIDER, device("ngoai")))))).isEqualTo(403);

    as(ACCEPTOR);
    assertThat(status(get("/api/v1/tasks/" + task + "/secret-content"))).isEqualTo(200);

    as(ASSIGNER);
    assertThat(status(revoke(task, RECOVERY))).isEqualTo(422);
    assertThat(status(revoke(task, ACCEPTOR))).isEqualTo(204);
    assertThat(status(revoke(task, ACCEPTOR))).isEqualTo(204);
    as(ACCEPTOR);
    assertThat(status(get("/api/v1/tasks/" + task + "/secret-content"))).isEqualTo(403);

    as(LEAD);
    // Chia sẻ và thu hồi tăng phiên bản dòng; If-Match cũ bị từ chối.
    assertThat(
            status(
                save(
                    task,
                    "\"0\"",
                    ciphertext,
                    List.of(wrap(LEAD, device("chu-tri")), wrap(RECOVERY, recoveryDevice)))))
        .isEqualTo(409);

    // Dấu đánh dấu chỉ nằm ở cột bản mã.
    String b64 = Base64.getEncoder().encodeToString(ciphertext);
    assertThat(
            this.<Long>inTask(
                jdbc ->
                    jdbc.sql(
                            "SELECT count(*) FROM task.outbox_event WHERE"
                                + " position(convert_to(?, 'UTF8') in payload) > 0"
                                + " OR position(convert_to(?, 'UTF8') in payload) > 0")
                        .params(MARKER, b64)
                        .query(Long.class)
                        .single()))
        .isZero();
    assertThat(AUDITS).isNotEmpty();
    assertThat(AUDITS)
        .allSatisfy(e -> assertThat(e.toString()).doesNotContain(MARKER).doesNotContain(b64));
    assertThat(output.getAll()).doesNotContain(MARKER).doesNotContain(b64);
  }

  @Test
  void nhiemVuKhongMatKhongLuuNoiDungMat() throws Exception {
    UUID task = task(false);
    as(LEAD);
    byte[] ciphertext = "ban-ma".getBytes(StandardCharsets.UTF_8);
    assertThat(
            status(
                save(
                    task,
                    "\"0\"",
                    ciphertext,
                    List.of(wrap(LEAD, device("chu-tri")), wrap(RECOVERY, recoveryDevice)))))
        .isEqualTo(409);
    assertThat(status(get("/api/v1/tasks/" + UUID.randomUUID() + "/secret-content")))
        .isEqualTo(404);
  }

  @Test
  void khoBanBocMinhChungMoiNguoiMotBanConHieuLuc() throws Exception {
    JdbcEvidenceKeyWraps store = new JdbcEvidenceKeyWraps(JdbcClient.create(productDataSource));
    UUID version = UUID.randomUUID();
    byte[] fp = MessageDigest.getInstance("SHA-256").digest(LEAD.toString().getBytes());
    Instant t0 = Instant.parse("2032-05-01T01:00:00Z");
    inProductRun(
        () ->
            store.upsert(
                tenant,
                version,
                List.of(new NewWrap(LEAD, fp, "RSA-OAEP-256", new byte[] {1, 2, 3})),
                ASSIGNER,
                t0));
    inProductRun(
        () ->
            store.upsert(
                tenant,
                version,
                List.of(new NewWrap(LEAD, fp, "RSA-OAEP-256", new byte[] {4, 5, 6})),
                ASSIGNER,
                t0.plusSeconds(60)));
    var all = inProduct(() -> store.of(tenant, version));
    assertThat(all).hasSize(1);
    assertThat(all.get(0).wrappedKey()).containsExactly(4, 5, 6);
    assertThat(inProduct(() -> store.activeFor(tenant, version, LEAD))).isPresent();
    assertThat(inProduct(() -> store.activeFor(tenant, version, ACCEPTOR))).isEmpty();
    TenantId other = TenantId.of(UUID.randomUUID());
    Optional<StoredWrap> leaked =
        TenantContext.call(
            other,
            () ->
                new TransactionTemplate(productTm)
                    .execute(s -> store.activeFor(other, version, LEAD)));
    assertThat(leaked).isEmpty();
  }

  private static void as(UUID person) {
    TestTaskCallers.current = new TaskActor(person, Set.of("manager"));
  }

  private static String device(String label) {
    return sha256Hex(label.getBytes(StandardCharsets.UTF_8));
  }

  private static String sha256Hex(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static Map<String, Object> wrap(UUID recipient, String deviceId) {
    Map<String, Object> w = new LinkedHashMap<>();
    w.put("recipientPersonId", recipient);
    w.put("deviceId", deviceId);
    w.put("algorithm", "ECDH-ES+A256KW");
    w.put("ephemeralPublicKey", Base64.getEncoder().encodeToString(new byte[] {4, 9, 9}));
    w.put("wrappedKey", Base64.getEncoder().encodeToString(deviceId.getBytes()));
    return w;
  }

  private String body(byte[] ciphertext, String sha256, List<Map<String, Object>> wraps)
      throws Exception {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("ciphertext", Base64.getEncoder().encodeToString(ciphertext));
    b.put("nonce", Base64.getEncoder().encodeToString(new byte[12]));
    b.put("aadHash", sha256Hex(("aad:" + period).getBytes(StandardCharsets.UTF_8)));
    b.put("sha256", sha256);
    b.put("keyWraps", new ArrayList<>(wraps));
    return json.writeValueAsString(b);
  }

  private MockHttpServletRequestBuilder save(
      UUID task, String ifMatch, byte[] ciphertext, List<Map<String, Object>> wraps)
      throws Exception {
    return put("/api/v1/tasks/" + task + "/secret-content")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .header("If-Match", ifMatch)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body(ciphertext, sha256Hex(ciphertext), wraps));
  }

  private MockHttpServletRequestBuilder share(UUID task, List<Map<String, Object>> wraps)
      throws Exception {
    return post("/api/v1/tasks/" + task + "/secret-content/shares")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(Map.of("keyWraps", wraps)));
  }

  private MockHttpServletRequestBuilder revoke(UUID task, UUID person) {
    return delete("/api/v1/tasks/" + task + "/secret-content/shares/" + person)
        .header("Idempotency-Key", UUID.randomUUID().toString());
  }

  private UUID task(boolean restricted) throws Exception {
    UUID id = UUID.randomUUID();
    inTask(
        jdbc -> {
          jdbc.sql(
                  "INSERT INTO task.task (id, created_by, updated_by, period_id, period_bucket,"
                      + " code, title, task_group, source, assigner_id, acceptor_id, org_unit_id,"
                      + " due_date, unit, dup_fingerprint, state_machine_version, status,"
                      + " is_restricted) VALUES (?, ?, ?, ?, ?, ?, 'Nhiệm vụ hạn chế',"
                      + " 'THUONG_XUYEN', 'KE_HOACH', ?, ?, ?, DATE '2032-06-15', 'báo cáo', ?,"
                      + " 'mac-dinh@2.1.0', 'DANG_THUC_HIEN', ?)")
              .params(
                  id,
                  ASSIGNER,
                  ASSIGNER,
                  period,
                  BUCKET,
                  "NV-2032-" + id.toString().substring(0, 8),
                  ASSIGNER,
                  ACCEPTOR,
                  UNIT,
                  id.toString().getBytes(StandardCharsets.UTF_8),
                  restricted)
              .update();
          jdbc.sql(
                  "INSERT INTO task.task_assignee (id, created_by, updated_by, period_id,"
                      + " period_bucket, task_id, assignee_type, person_id, role, valid_from)"
                      + " VALUES (?, ?, ?, ?, ?, ?, 'CA_NHAN', ?, 'CHU_TRI', DATE '2032-04-01')")
              .params(UUID.randomUUID(), ASSIGNER, ASSIGNER, period, BUCKET, id, LEAD)
              .update();
          return null;
        });
    return id;
  }

  private <T> T inTask(Function<JdbcClient, T> work) throws Exception {
    JdbcClient jdbc = JdbcClient.create(taskDataSource);
    return TenantContext.call(
        tenant, () -> new TransactionTemplate(taskTm).execute(s -> work.apply(jdbc)));
  }

  private <T> T inProduct(java.util.function.Supplier<T> work) throws Exception {
    return TenantContext.call(
        tenant, () -> new TransactionTemplate(productTm).execute(s -> work.get()));
  }

  private void inProductRun(Runnable work) throws Exception {
    inProduct(
        () -> {
          work.run();
          return null;
        });
  }

  private int status(MockHttpServletRequestBuilder request) throws Exception {
    return perform(request).getResponse().getStatus();
  }

  private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
    return TenantContext.call(
        tenant,
        () ->
            mvc.perform(request.principal(TestTaskCallers.current.personId()::toString))
                .andReturn());
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }
}
