package vn.viettel.okrkpi.shared.job;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.springframework.http.ProblemDetail;

/**
 * Tác vụ dài đúng lược đồ {@code Job} của {@code common.yaml} (BS-02 HD-06), trả trong thân 202 và
 * ở {@code GET /api/v1/jobs/{id}} ({@code getJob}). Thuộc tính không bắt buộc bỏ khỏi JSON khi
 * {@code null}.
 *
 * @param id mã tác vụ
 * @param type loại tác vụ, ví dụ {@code PERIOD_LOCK}, {@code EXPORT}
 * @param status trạng thái
 * @param progress phần trăm 0..100, có thể {@code null}
 * @param message mô tả bước đang chạy, tiếng Việt
 * @param resultLocation đường dẫn kết quả khi {@code HOAN_TAT}
 * @param problem lỗi khi {@code THAT_BAI}
 * @param updatedAt thời điểm cập nhật
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Job(
    String id,
    String type,
    JobStatus status,
    Integer progress,
    String message,
    String resultLocation,
    ProblemDetail problem,
    OffsetDateTime updatedAt) {

  public Job {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(status, "status");
    if (id.isBlank() || type.isBlank()) {
      throw new IllegalArgumentException("id và type không rỗng");
    }
    if (progress != null && (progress < 0 || progress > 100)) {
      throw new IllegalArgumentException("progress ngoài 0..100");
    }
  }

  /** Tác vụ vừa nhận ({@code CHO}, tiến độ 0), như ví dụ {@code moi-nhan} của hợp đồng. */
  public static Job accepted(String id, String type, OffsetDateTime at) {
    return new Job(id, type, JobStatus.CHO, 0, null, null, null, at);
  }
}
