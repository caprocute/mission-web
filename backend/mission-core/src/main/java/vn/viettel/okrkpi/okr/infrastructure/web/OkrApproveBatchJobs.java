package vn.viettel.okrkpi.okr.infrastructure.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.okr.application.OkrComposeService;
import vn.viettel.okrkpi.okr.application.OkrReviewService;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobSource;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.job.TrackedJob;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Tác vụ {@code APPROVE_BATCH} của {@code approveOkrObjectivesBatch} (CORE-01-06, SCR-0203 "Duyệt
 * hàng loạt"): mỗi bộ một giao dịch riêng qua {@link OkrReviewService#approve}, mục lỗi không làm
 * hỏng mục khác. Kết quả từng mục ({@code BatchItemResult}) nằm ở thuộc tính {@code items} của
 * {@code problem} khi có mục lỗi; hợp đồng chưa có đường dẫn đọc kết quả lô của OKR nên {@code
 * resultLocation} để trống (suy luận). Trạng thái tác vụ giữ trong bộ nhớ pod 24 giờ như {@link
 * OkrExportJobs}.
 */
public class OkrApproveBatchJobs implements JobSource {

  private static final Logger log = LoggerFactory.getLogger(OkrApproveBatchJobs.class);

  static final String TYPE = "APPROVE_BATCH";

  /** Một mục của lô. */
  public record Item(UUID id, long rowVersion) {}

  private record Key(UUID tenant, String jobId) {}

  private final Cache<Key, TrackedJob> jobs =
      Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(24)).maximumSize(100_000).build();

  private final OkrReviewService reviews;
  private final Executor executor;

  public OkrApproveBatchJobs(OkrReviewService reviews, Executor executor) {
    this.reviews = Objects.requireNonNull(reviews, "reviews");
    this.executor = Objects.requireNonNull(executor, "executor");
  }

  @Override
  public String name() {
    return "okr.approve-batch";
  }

  @Override
  public Optional<TrackedJob> find(TenantId tenant, String jobId) {
    return Optional.ofNullable(jobs.getIfPresent(new Key(tenant.value(), jobId)));
  }

  /**
   * Nhận lô: trả bản {@code CHO} cho phản hồi 202 rồi chạy nền. {@code scope} kiểm phạm vi đơn vị
   * của người duyệt với từng mục tiêu, ném lỗi khi ngoài phạm vi.
   */
  public Job start(
      TenantId tenant, UUID actor, List<Item> items, String comment, Consumer<UUID> scope) {
    String jobId = UUID.randomUUID().toString();
    Job accepted = Job.accepted(jobId, TYPE, now());
    Key key = new Key(tenant.value(), jobId);
    jobs.put(key, new TrackedJob(accepted, ActorId.of(actor), false));
    List<Item> copy = List.copyOf(items);
    executor.execute(
        () -> TenantContext.run(tenant, () -> run(key, tenant, actor, copy, comment, scope)));
    return accepted;
  }

  private void run(
      Key key,
      TenantId tenant,
      UUID actor,
      List<Item> items,
      String comment,
      Consumer<UUID> scope) {
    List<Map<String, Object>> results = new ArrayList<>(items.size());
    int done = 0;
    int ok = 0;
    for (Item item : items) {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("id", item.id().toString());
      try {
        scope.accept(item.id());
        reviews.approve(tenant, actor, item.id(), item.rowVersion(), comment, true);
        result.put("status", 200);
        ok++;
      } catch (RuntimeException e) {
        Map<String, Object> problem = problem(e);
        result.put("status", problem.get("status"));
        result.put("problem", problem);
      }
      results.add(result);
      done++;
      if (done < items.size()) {
        progress(key, actor, JobStatus.DANG_CHAY, done * 100 / items.size(), null, null);
      }
    }
    ProblemDetail summary = null;
    if (ok < items.size()) {
      int status =
          results.stream()
              .map(r -> (Integer) r.get("status"))
              .filter(s -> s != 200)
              .findFirst()
              .orElse(409);
      summary = ProblemDetail.forStatus(status);
      summary.setType(URI.create("https://okrkpi.vn/problems/batch-partial"));
      summary.setTitle("Một số bộ OKR chưa được phê duyệt");
      summary.setDetail(
          "Đã phê duyệt " + ok + "/" + items.size() + " bộ OKR; các bộ còn lại cần xem lại.");
      summary.setProperty("items", List.copyOf(results));
    }
    log.info("Duyệt hàng loạt {}: {}/{} bộ thành công", key.jobId(), ok, items.size());
    progress(
        key,
        actor,
        JobStatus.HOAN_TAT,
        100,
        "Đã phê duyệt " + ok + "/" + items.size() + " bộ OKR",
        summary);
  }

  private void progress(
      Key key, UUID actor, JobStatus status, int pct, String message, ProblemDetail problem) {
    jobs.put(
        key,
        new TrackedJob(
            new Job(key.jobId(), TYPE, status, pct, message, null, problem, now()),
            ActorId.of(actor),
            false));
  }

  /** Lỗi của một mục dưới dạng RFC 9457 rút gọn. */
  static Map<String, Object> problem(RuntimeException e) {
    Map<String, Object> p = new LinkedHashMap<>();
    if (e instanceof VersionConflictException v) {
      p.put("status", 409);
      p.put("code", "OKR-409-VERSION-CONFLICT");
      p.put("title", "Dữ liệu đã được người khác cập nhật");
      p.put("detail", "Bộ OKR đã thay đổi so với lúc đồng chí chọn, vui lòng tải lại.");
    } else if (e instanceof BusinessException b) {
      p.put("status", b.errorCode().status());
      p.put("code", b.errorCode().code());
      p.put("title", b.errorCode().title());
      p.put("detail", b.detail());
      if (b.msg() != null) {
        p.put("msg", b.msg());
      }
    } else if (e instanceof NoSuchElementException || e instanceof OkrComposeService.NotFound) {
      p.put("status", 404);
      p.put("title", "Không tìm thấy bộ OKR");
    } else if (e instanceof ResponseStatusException r) {
      p.put("status", r.getStatusCode().value());
      p.put("title", r.getReason() == null ? "Không thực hiện được" : r.getReason());
    } else {
      log.warn("Lỗi khi duyệt một mục của lô: {}", e.toString());
      p.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
      p.put("title", "Lỗi hệ thống, vui lòng thử lại sau");
    }
    return p;
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }
}
