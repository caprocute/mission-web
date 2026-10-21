package vn.viettel.okrkpi.shared.job;

import java.net.URI;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Phản hồi tác vụ dài (baseline mục 13; TL-01 "San phẳng đỉnh bằng hàng đợi"; {@code common.yaml}
 * response {@code Accepted}): 202, thân {@link Job}, {@code Location: /api/v1/jobs/{id}} và {@code
 * Retry-After} gợi ý giãn cách thăm dò.
 */
public final class Jobs {

  /** Tiền tố đường dẫn theo dõi tác vụ ({@code jobs.yaml}). */
  public static final String JOBS_PATH = "/api/v1/jobs/";

  /** Giãn cách thăm dò mặc định (giây). */
  public static final long DEFAULT_RETRY_AFTER_SECONDS = 2;

  private Jobs() {}

  /** Đường dẫn theo dõi {@code /api/v1/jobs/{id}} (mã được mã hoá an toàn cho đường dẫn). */
  public static URI location(String jobId) {
    Objects.requireNonNull(jobId, "jobId");
    return URI.create(
        UriComponentsBuilder.fromPath(JOBS_PATH + "{id}")
            .encode()
            .buildAndExpand(jobId)
            .toUriString());
  }

  /** 202 kèm {@code Location} và {@code Retry-After} mặc định. */
  public static ResponseEntity<Job> accepted(Job job) {
    return accepted(job, DEFAULT_RETRY_AFTER_SECONDS);
  }

  /** 202 kèm {@code Location} và {@code Retry-After}. */
  public static ResponseEntity<Job> accepted(Job job, long retryAfterSeconds) {
    Objects.requireNonNull(job, "job");
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .location(location(job.id()))
        .header(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(0, retryAfterSeconds)))
        .body(job);
  }

  /** 200 cho {@code getJob}; tác vụ chưa kết thúc kèm {@code Retry-After}. */
  public static ResponseEntity<Job> status(Job job) {
    Objects.requireNonNull(job, "job");
    ResponseEntity.BodyBuilder b = ResponseEntity.ok();
    if (!job.status().isTerminal()) {
      b.header(HttpHeaders.RETRY_AFTER, Long.toString(DEFAULT_RETRY_AFTER_SECONDS));
    }
    return b.body(job);
  }
}
