package vn.viettel.okrkpi.analytics.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Tác vụ xuất của SVC-15 ({@code Job} của {@code common.yaml}, loại {@code EXPORT}): chuỗi bằng
 * chứng hoặc báo cáo bảng điều hành. Chỉ người khởi tạo xem được ({@code jobs.yaml}).
 */
public record ExportJob(
    String id,
    UUID tenant,
    UUID initiator,
    Status status,
    int progress,
    String message,
    String resultLocation,
    String errorCode,
    Instant updatedAt) {

  public static final String TYPE = "EXPORT";

  /** Tiền tố mã tác vụ của SVC-15: cổng cell định tuyến {@code /api/v1/jobs/ana-…} về đây. */
  public static final String JOB_PREFIX = "ana";

  /** Trùng tên {@code JobStatus} của hợp đồng. */
  public enum Status {
    CHO,
    DANG_CHAY,
    HOAN_TAT,
    THAT_BAI
  }

  public ExportJob {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(tenant, "tenant");
    Objects.requireNonNull(initiator, "initiator");
    Objects.requireNonNull(status, "status");
  }

  ExportJob next(Status s, int p, String msg, String location, String error, Instant at) {
    return new ExportJob(id, tenant, initiator, s, p, msg, location, error, at);
  }
}
