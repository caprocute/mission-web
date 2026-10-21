package vn.viettel.okrkpi.task.web;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.idempotency.Idempotent;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobQueryService;
import vn.viettel.okrkpi.shared.job.Jobs;
import vn.viettel.okrkpi.shared.job.TrackedJob;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.Classified;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.paging.CursorCodec;
import vn.viettel.okrkpi.shared.paging.CursorKey;
import vn.viettel.okrkpi.shared.paging.CursorPage;
import vn.viettel.okrkpi.shared.paging.PageRequest;

/** Controller thử dùng bốn quy ước của NEN-013 như controller nghiệp vụ thật. */
@RestController
@RequestMapping("/api/v1")
public class TaskProbeController {

  /** Cho kiểm thử đồng thời: yêu cầu có header giữ giao dịch tới khi được thả. */
  public static volatile CountDownLatch entered = new CountDownLatch(1);

  public static volatile CountDownLatch release = new CountDownLatch(1);

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final CursorCodec cursors;
  private final JobQueryService jobs;
  private final ApiConventionsTestApplication.MemoryJobs memoryJobs;
  private final Map<UUID, Long> versions = new ConcurrentHashMap<>();

  public TaskProbeController(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      CursorCodec cursors,
      JobQueryService jobs,
      ApiConventionsTestApplication.MemoryJobs memoryJobs) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(transactionManager);
    this.cursors = cursors;
    this.jobs = jobs;
    this.memoryJobs = memoryJobs;
  }

  public record ProgressInput(int percent, String note) {}

  public record ProgressView(UUID taskId, int percent, String message) {}

  public record CommentInput(@Classified(Classification.C2) String content) {}

  public record CommentView(UUID taskId, @Classified(Classification.C2) String content) {}

  public record EventRow(UUID id, Instant createdAt) {}

  public record DocView(UUID id, long rowVersion, String title) {}

  /** Ghi tiến độ: tác dụng là một dòng outbox trong cùng giao dịch với khoá. */
  @PostMapping("/tasks/{id}/progress")
  @Idempotent
  public ResponseEntity<ProgressView> progress(
      @PathVariable UUID id,
      @RequestBody ProgressInput in,
      @RequestHeader(value = "X-Test-Hold", required = false) String hold)
      throws InterruptedException {
    insertOutbox(id);
    if (hold != null) {
      entered.countDown();
      if (!release.await(20, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Hết thời gian chờ thả");
      }
    }
    return ResponseEntity.created(URI.create("/api/v1/tasks/" + id))
        .body(new ProgressView(id, in.percent(), "Đã ghi nhận tiến độ"));
  }

  /** Nhận xét C2: thân phản hồi không được lưu vào idempotency_key. */
  @PostMapping("/tasks/{id}/comments")
  @Idempotent
  public ResponseEntity<CommentView> comment(@PathVariable UUID id, @RequestBody CommentInput in) {
    insertOutbox(id);
    return ResponseEntity.created(URI.create("/api/v1/tasks/" + id + "/comments/1"))
        .body(new CommentView(id, in.content()));
  }

  /** Lỗi nghiệp vụ sau khi đã ghi tác dụng: giao dịch huỷ, khoá không được lưu. */
  @PostMapping("/tasks/{id}/locked")
  @Idempotent
  public ProgressView locked(@PathVariable UUID id, @RequestBody ProgressInput in) {
    insertOutbox(id);
    throw new BusinessException("TASK-409-PERIOD-LOCKED", "Kỳ đánh giá đã khoá.");
  }

  @GetMapping("/tasks/{id}/doc")
  public ResponseEntity<DocView> readDoc(
      @PathVariable UUID id,
      @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
    long v = versions.getOrDefault(id, 0L);
    return ETags.conditional(ifNoneMatch, v, () -> new DocView(id, v, "Kế hoạch quý IV"));
  }

  @PutMapping("/tasks/{id}/doc")
  public ResponseEntity<DocView> updateDoc(
      @PathVariable UUID id,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody Map<String, Object> body) {
    long current = versions.getOrDefault(id, 0L);
    ETags.requireMatch(ifMatch, current, null);
    long next = current + 1;
    versions.put(id, next);
    return ETags.ok(new DocView(id, next, String.valueOf(body.get("title"))), next);
  }

  /** Danh sách sự kiện outbox của một nhiệm vụ, phân trang keyset theo (created_at, id). */
  @GetMapping("/tasks/{id}/events")
  public CursorPage<EventRow> events(
      @PathVariable UUID id,
      @RequestHeader(ApiConventionsTestApplication.TENANT_HEADER) String tenant,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    PageRequest page = cursors.pageRequest(limit, cursor, "task.events:" + id);
    List<EventRow> rows =
        tx.execute(
            s -> {
              jdbc.queryForObject(
                  "SELECT set_config('app.tenant_id', ?, true)", String.class, tenant);
              StringBuilder sql =
                  new StringBuilder(
                      "SELECT id, created_at FROM task.outbox_event WHERE aggregate_id = ?");
              Object[] args;
              if (page.firstPage()) {
                args = new Object[] {id, page.fetchSize()};
              } else {
                CursorKey k = page.after();
                sql.append(" AND (created_at, id) > (?, ?)");
                args =
                    new Object[] {
                      id, k.getInstant(0).atOffset(ZoneOffset.UTC), k.getUuid(1), page.fetchSize()
                    };
              }
              sql.append(" ORDER BY created_at, id LIMIT ?");
              return jdbc.query(
                  sql.toString(),
                  (rs, i) ->
                      new EventRow(
                          rs.getObject(1, UUID.class),
                          rs.getObject(2, OffsetDateTime.class).toInstant()),
                  args);
            });
    return cursors.page(rows, page, r -> CursorKey.of(r.createdAt(), r.id()));
  }

  /** Xuất báo cáo: tác vụ dài trả 202 + Location. */
  @PostMapping("/tasks/exports")
  @Idempotent
  public ResponseEntity<Job> export(
      @RequestHeader(ApiConventionsTestApplication.TENANT_HEADER) String tenant,
      @RequestHeader(ApiConventionsTestApplication.ACTOR_HEADER) String actor,
      @RequestBody Map<String, Object> body) {
    Job job =
        Job.accepted(Ids.newUuidV7().toString(), "EXPORT", OffsetDateTime.now(ZoneOffset.UTC));
    memoryJobs.put(TenantId.parse(tenant), new TrackedJob(job, ActorId.parse(actor), true));
    return Jobs.accepted(job);
  }

  @GetMapping("/jobs/{id}")
  public ResponseEntity<Job> getJob(
      @PathVariable String id,
      @RequestHeader(ApiConventionsTestApplication.TENANT_HEADER) String tenant,
      @RequestHeader(ApiConventionsTestApplication.ACTOR_HEADER) String actor) {
    return Jobs.status(jobs.get(TenantId.parse(tenant), ActorId.parse(actor), id));
  }

  @DeleteMapping("/jobs/{id}")
  public ResponseEntity<Void> cancelJob(
      @PathVariable String id,
      @RequestHeader(ApiConventionsTestApplication.TENANT_HEADER) String tenant,
      @RequestHeader(ApiConventionsTestApplication.ACTOR_HEADER) String actor) {
    jobs.cancel(TenantId.parse(tenant), ActorId.parse(actor), id);
    return ResponseEntity.accepted().build();
  }

  private void insertOutbox(UUID taskId) {
    jdbc.update(
        "INSERT INTO task.outbox_event (id, tenant_id, aggregate_type, aggregate_id,"
            + " aggregate_version, event_type, topic, event_key, payload, schema_version)"
            + " VALUES (?, current_setting('app.tenant_id')::uuid, 'Task', ?, 1,"
            + " 'TienDoDaCapNhat', 'task.task.progressed.v1', ?, ?, 1)",
        Ids.newUuidV7(),
        taskId,
        taskId.toString(),
        new byte[] {0});
  }
}
