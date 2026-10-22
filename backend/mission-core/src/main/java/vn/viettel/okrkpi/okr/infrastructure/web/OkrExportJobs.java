package vn.viettel.okrkpi.okr.infrastructure.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobSource;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.job.TrackedJob;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Tác vụ {@code EXPORT} của {@code exportOkrObjectives} (SCR-0201 "Xuất mẫu"), giữ trong bộ nhớ của
 * pod 24 giờ và trả qua {@code GET /api/v1/jobs/{id}}. Tệp DOCX/PDF/XLSX do SVC-11 sinh (ngoài phạm
 * vi CORE-01-02); khi SVC-11 chưa nối, tác vụ kết thúc {@code THAT_BAI} với lỗi RFC 9457 {@code
 * OKR-503-EXPORT-UNAVAILABLE} để giao diện báo người dùng thử lại sau (mã lỗi là suy luận).
 */
public class OkrExportJobs implements JobSource {

  static final String TYPE = "EXPORT";
  static final String UNAVAILABLE = "OKR-503-EXPORT-UNAVAILABLE";

  private record Key(UUID tenant, String jobId) {}

  private final Cache<Key, TrackedJob> jobs =
      Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(24)).maximumSize(100_000).build();

  @Override
  public String name() {
    return "okr.export";
  }

  @Override
  public Optional<TrackedJob> find(TenantId tenant, String jobId) {
    return Optional.ofNullable(jobs.getIfPresent(new Key(tenant.value(), jobId)));
  }

  /** Nhận yêu cầu xuất: trả bản {@code CHO} cho phản hồi 202, ghi kết quả vào bộ nhớ. */
  public Job start(TenantId tenant, UUID initiator) {
    String jobId = UUID.randomUUID().toString();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    Job accepted = Job.accepted(jobId, TYPE, now);
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Dịch vụ sinh văn bản chưa sẵn sàng, vui lòng thử lại sau.");
    problem.setType(URI.create("https://okrkpi.vn/problems/export-unavailable"));
    problem.setTitle("Chưa xuất được mẫu báo cáo");
    problem.setProperty("code", UNAVAILABLE);
    jobs.put(
        new Key(tenant.value(), jobId),
        new TrackedJob(
            new Job(
                jobId,
                TYPE,
                JobStatus.THAT_BAI,
                100,
                "Chưa xuất được mẫu báo cáo",
                null,
                problem,
                now),
            ActorId.of(initiator),
            false));
    return accepted;
  }
}
