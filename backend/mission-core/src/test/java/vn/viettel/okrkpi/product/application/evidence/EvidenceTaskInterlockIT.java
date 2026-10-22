package vn.viettel.okrkpi.product.application.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.MissionCoreApplication;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.product.domain.evidence.TaskInterlock;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * CORE-03-02 trên PostgreSQL thật: hai máy trạng thái độc lập (BR-07-10). Nộp đủ sản phẩm đưa nhiệm
 * vụ sang {@code CHO_NGHIEM_THU}; từ chối một phiên bản đưa nhiệm vụ về {@code DANG_THUC_HIEN} mà
 * không đổi phiên bản khác; xác nhận đủ đưa nhiệm vụ sang {@code HOAN_THANH}; {@code
 * deliverable.quantity_accepted} chỉ đếm phiên bản {@code DA_XAC_NHAN}; lệch {@code row_version}
 * trả 409. Bean {@code TaskStatusEvents} giả (không cần registry) đến từ cấu hình của {@code
 * TaskTransitionApiIT}, vốn lọt vào mọi ngữ cảnh do quét thành phần của {@code
 * MissionCoreApplication}.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EvidenceTaskInterlockIT {

  static final int BUCKET = 20274;
  static final UUID ASSIGNER = UUID.fromString("01927a03-0000-7000-8000-00000000d302");

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private EvidenceLifecycleService service;

  @Autowired
  @Qualifier("taskDataSource") private DataSource taskDataSource;

  @Autowired
  @Qualifier("taskTransactionManager") private PlatformTransactionManager taskTm;

  @Autowired
  @Qualifier("productDataSource") private DataSource productDataSource;

  @Autowired
  @Qualifier("productTransactionManager") private PlatformTransactionManager productTm;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID period = UUID.randomUUID();
  private final UUID lead = UUID.randomUUID();
  private final EvidenceActor member = new EvidenceActor(lead, Set.of("member"));
  private final EvidenceActor reviewer = new EvidenceActor(ASSIGNER, Set.of("manager"));

  @BeforeAll
  void seed() throws Exception {
    in(
        taskTm,
        taskDataSource,
        jdbc -> {
          for (String t : List.of("task", "task_assignee", "task_state_log")) {
            jdbc.sql("SELECT task.fn_create_period_partitions(?::regclass, ?)")
                .params("task." + t, BUCKET)
                .query()
                .listOfRows();
          }
          return null;
        });
    in(
        productTm,
        productDataSource,
        jdbc -> {
          for (String t : List.of("deliverable", "evidence", "evidence_version")) {
            jdbc.sql("SELECT product.fn_create_period_partitions(?::regclass, ?)")
                .params("product." + t, BUCKET)
                .query()
                .listOfRows();
          }
          return null;
        });
  }

  @Test
  void haiMayTrangThaiDocLapVaLienDong() throws Exception {
    UUID task = task();
    UUID v1 = evidenceVersion(task, deliverable(task));
    UUID v2 = evidenceVersion(task, deliverable(task));

    var r1 = call(() -> service.submitVersion(tenant, v1, 0, member, false));
    assertThat(r1.taskRequest()).isEmpty();
    assertThat(taskStatus(task)).isEqualTo("DANG_THUC_HIEN");

    var r2 = call(() -> service.submitVersion(tenant, v2, 0, member, false));
    assertThat(r2.taskRequest()).contains(TaskInterlock.TaskRequest.SUBMIT);
    assertThat(taskStatus(task)).isEqualTo("CHO_NGHIEM_THU");

    call(() -> service.openReview(tenant, v1, 1, reviewer));
    call(() -> service.openReview(tenant, v2, 1, reviewer));
    call(() -> service.acceptVersion(tenant, v1, 2, reviewer));
    assertThat(taskStatus(task)).isEqualTo("CHO_NGHIEM_THU");

    // Từ chối v2: nhiệm vụ về DANG_THUC_HIEN, v1 giữ DA_XAC_NHAN.
    var rejected =
        call(() -> service.rejectVersion(tenant, v2, 2, "Không đúng mẫu báo cáo", reviewer));
    assertThat(rejected.taskRequest()).contains(TaskInterlock.TaskRequest.REWORK);
    assertThat(taskStatus(task)).isEqualTo("DANG_THUC_HIEN");
    assertThat(versionStatus(v1)).isEqualTo("DA_XAC_NHAN");
    assertThat(versionStatus(v2)).isEqualTo("TU_CHOI");
    Map<String, Object> d =
        in(
            productTm,
            productDataSource,
            jdbc ->
                jdbc.sql(
                        "SELECT count(*) FILTER (WHERE status = 'DA_XAC_NHAN') AS du,"
                            + " sum(quantity_accepted) AS sl FROM product.deliverable WHERE task_id = ?")
                    .param(task)
                    .query()
                    .singleRow());
    assertThat(((Number) d.get("du")).intValue()).isEqualTo(1);
    assertThat(((Number) d.get("sl")).intValue()).isEqualTo(1);

    // Phiên bản 2 của minh chứng bị từ chối: nộp, mở, xác nhận → nhiệm vụ hoàn thành.
    UUID v2b = nextVersion(v2);
    call(() -> service.submitVersion(tenant, v2b, 0, member, false));
    assertThat(taskStatus(task)).isEqualTo("CHO_NGHIEM_THU");
    call(() -> service.openReview(tenant, v2b, 1, reviewer));
    var done = call(() -> service.acceptVersion(tenant, v2b, 2, reviewer));
    assertThat(done.taskRequest()).contains(TaskInterlock.TaskRequest.APPROVE);
    assertThat(taskStatus(task)).isEqualTo("HOAN_THANH");
    List<String> log =
        in(
            taskTm,
            taskDataSource,
            jdbc ->
                jdbc.sql(
                        "SELECT transition FROM task.task_state_log WHERE task_id = ? ORDER BY occurred_at")
                    .param(task)
                    .query(String.class)
                    .list());
    assertThat(log).containsExactly("SUBMIT", "REWORK", "SUBMIT", "APPROVE");

    assertThatThrownBy(() -> call(() -> service.openReview(tenant, v1, 0, reviewer)))
        .isInstanceOf(VersionConflictException.class);
  }

  private UUID task() throws Exception {
    UUID id = UUID.randomUUID();
    in(
        taskTm,
        taskDataSource,
        jdbc -> {
          jdbc.sql(
                  "INSERT INTO task.task (id, created_by, updated_by, period_id, period_bucket, code,"
                      + " title, task_group, source, assigner_id, acceptor_id, due_date, unit,"
                      + " planned_quantity, dup_fingerprint, state_machine_version, status) VALUES (?, ?,"
                      + " ?, ?, ?, ?, 'Báo cáo tổng kết công tác quý', 'THUONG_XUYEN', 'KE_HOACH', ?, ?,"
                      + " DATE '2027-03-20', 'báo cáo', 2, ?, 'mac-dinh@2.1.0', 'DANG_THUC_HIEN')")
              .params(
                  id,
                  ASSIGNER,
                  ASSIGNER,
                  period,
                  BUCKET,
                  "NV-" + id.toString().substring(0, 8),
                  ASSIGNER,
                  ASSIGNER,
                  id.toString().getBytes(StandardCharsets.UTF_8))
              .update();
          jdbc.sql(
                  "INSERT INTO task.task_assignee (id, created_by, updated_by, period_id, period_bucket,"
                      + " task_id, assignee_type, person_id, role, valid_from) VALUES (?, ?, ?, ?, ?, ?,"
                      + " 'CA_NHAN', ?, 'CHU_TRI', DATE '2027-01-01')")
              .params(UUID.randomUUID(), ASSIGNER, ASSIGNER, period, BUCKET, id, lead)
              .update();
          return null;
        });
    return id;
  }

  private UUID deliverable(UUID task) throws Exception {
    UUID id = UUID.randomUUID();
    in(
        productTm,
        productDataSource,
        jdbc ->
            jdbc.sql(
                    "INSERT INTO product.deliverable (id, created_by, updated_by, period_id, period_bucket,"
                        + " task_id, name, deliverable_type, unit, quantity_planned) VALUES (?, ?, ?, ?, ?,"
                        + " ?, 'Báo cáo quý', 'BAO_CAO', 'báo cáo', 1)")
                .params(id, lead, lead, period, BUCKET, task)
                .update());
    return id;
  }

  /** Minh chứng mới kèm phiên bản 1 ở NHAP, đã có tệp quét sạch. */
  private UUID evidenceVersion(UUID task, UUID deliverable) throws Exception {
    UUID evidence = UUID.randomUUID();
    in(
        productTm,
        productDataSource,
        jdbc ->
            jdbc.sql(
                    "INSERT INTO product.evidence (id, created_by, updated_by, period_id, period_bucket,"
                        + " deliverable_id, task_id, title, source_type) VALUES (?, ?, ?, ?, ?, ?, ?,"
                        + " 'Báo cáo tổng kết', 'TAI_LEN')")
                .params(evidence, lead, lead, period, BUCKET, deliverable, task)
                .update());
    return version(evidence, task, 1);
  }

  private UUID nextVersion(UUID previous) throws Exception {
    Map<String, Object> row =
        in(
            productTm,
            productDataSource,
            jdbc ->
                jdbc.sql(
                        "SELECT evidence_id, task_id, version_no FROM product.evidence_version WHERE id = ?")
                    .param(previous)
                    .query()
                    .singleRow());
    return version(
        (UUID) row.get("evidence_id"),
        (UUID) row.get("task_id"),
        ((Number) row.get("version_no")).intValue() + 1);
  }

  private UUID version(UUID evidence, UUID task, int no) throws Exception {
    UUID id = UUID.randomUUID();
    byte[] sha = new byte[32];
    Arrays.fill(sha, (byte) no);
    in(
        productTm,
        productDataSource,
        jdbc ->
            jdbc.sql(
                    "INSERT INTO product.evidence_version (id, created_by, updated_by, period_id,"
                        + " period_bucket, evidence_id, task_id, version_no, object_id, content_sha256,"
                        + " source, scan_status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'TAI_LEN', 'SACH')")
                .params(id, lead, lead, period, BUCKET, evidence, task, no, UUID.randomUUID(), sha)
                .update());
    return id;
  }

  private String taskStatus(UUID task) throws Exception {
    return in(
        taskTm,
        taskDataSource,
        jdbc ->
            jdbc.sql("SELECT status FROM task.task WHERE id = ?")
                .param(task)
                .query(String.class)
                .single());
  }

  private String versionStatus(UUID v) throws Exception {
    return in(
        productTm,
        productDataSource,
        jdbc ->
            jdbc.sql("SELECT status FROM product.evidence_version WHERE id = ?")
                .param(v)
                .query(String.class)
                .single());
  }

  private <T> T call(java.util.concurrent.Callable<T> work) throws Exception {
    return TenantContext.call(tenant, work);
  }

  private <T> T in(PlatformTransactionManager tm, DataSource ds, Function<JdbcClient, T> work)
      throws Exception {
    return TenantContext.call(
        tenant, () -> new TransactionTemplate(tm).execute(s -> work.apply(JdbcClient.create(ds))));
  }
}
