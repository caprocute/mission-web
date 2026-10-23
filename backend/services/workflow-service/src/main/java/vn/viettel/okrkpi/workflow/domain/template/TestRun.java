package vn.viettel.okrkpi.workflow.domain.template;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Một lần chạy bộ test của phiên bản mẫu, theo dõi như tác vụ dài ({@code TEST_RUN}).
 *
 * @param id mã lần chạy, cũng là mã tác vụ
 * @param tenantId tenant nền tảng
 * @param templateId phiên bản mẫu
 * @param bpmnSha256 băm nội dung được kiểm
 * @param status trạng thái
 * @param report kết quả khi đã chạy xong, rỗng khi chưa
 * @param requestedBy người yêu cầu
 * @param updatedAt thời điểm cập nhật
 */
public record TestRun(
    UUID id,
    UUID tenantId,
    UUID templateId,
    byte[] bpmnSha256,
    TestRunStatus status,
    TestReport report,
    UUID requestedBy,
    Instant updatedAt) {

  /** Loại tác vụ dài trong {@code Job.type}. */
  public static final String JOB_TYPE = "TEST_RUN";

  public TestRun {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(templateId, "templateId");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(requestedBy, "requestedBy");
    bpmnSha256 = bpmnSha256 == null ? null : bpmnSha256.clone();
  }

  @Override
  public byte[] bpmnSha256() {
    return bpmnSha256 == null ? null : bpmnSha256.clone();
  }

  /** Tiến độ phần trăm cho {@code Job.progress}. */
  public int progress() {
    return status.terminal() ? 100 : status == TestRunStatus.DANG_CHAY ? 50 : 0;
  }

  /** Thông điệp tiếng Việt cho {@code Job.message}. */
  public String message() {
    return switch (status) {
      case CHO -> "Đang chờ engine kiểm thử.";
      case DANG_CHAY -> "Đang chạy bộ test trên engine kiểm thử.";
      case HOAN_TAT, THAT_BAI -> report == null ? "Không chạy được bộ test." : report.summary();
    };
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof TestRun r && id.equals(r.id) && status == r.status;
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, status);
  }

  @Override
  public String toString() {
    return "TestRun[" + id + ", " + status + "]";
  }
}
