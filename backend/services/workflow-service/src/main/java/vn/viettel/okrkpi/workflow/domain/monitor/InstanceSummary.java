package vn.viettel.okrkpi.workflow.domain.monitor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.workflow.domain.BusinessKey;
import vn.viettel.okrkpi.workflow.domain.TaskProjection;

/**
 * Một dòng giám sát tiến trình ({@code WorkflowInstanceSummary}): bước đang chờ của một tiến trình,
 * dựng từ bảng chiếu {@code flowable.wf_task_projection}. Ở phạm vi cell, các trường mang nội dung
 * hồ sơ (tiêu đề, người xử lý) bị bỏ.
 *
 * @param businessKey đối tượng nghiệp vụ
 * @param title tiêu đề hồ sơ, {@code null} ở phạm vi cell
 * @param templateCode mã mẫu luồng
 * @param templateVersion phiên bản mẫu đang chạy, {@code null} khi chưa biết
 * @param currentActivityName tên bước hiện tại, {@code null} khi định nghĩa đặt tên bằng biểu thức
 * @param currentTaskId user task đang chờ
 * @param assigneeId người xử lý, {@code null} khi chưa nhận hoặc ở phạm vi cell
 * @param candidateRole vai trò ứng viên
 * @param startedAt thời điểm khởi tạo tiến trình
 * @param dueAt hạn của bước, có thể {@code null}
 * @param stuck tiến trình kẹt
 * @param stuckReason lý do kẹt bằng lời, {@code null} khi không kẹt
 * @param cellCode mã cell
 */
public record InstanceSummary(
    BusinessKey businessKey,
    String title,
    String templateCode,
    Integer templateVersion,
    String currentActivityName,
    String currentTaskId,
    UUID assigneeId,
    String candidateRole,
    Instant startedAt,
    Instant dueAt,
    boolean stuck,
    String stuckReason,
    String cellCode) {

  /** Ngày nghiệp vụ theo giờ Việt Nam để đếm số ngày quá hạn. */
  public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

  public InstanceSummary {
    Objects.requireNonNull(businessKey, "businessKey");
    Objects.requireNonNull(startedAt, "startedAt");
  }

  /**
   * Dựng dòng giám sát từ bảng chiếu.
   *
   * @param p dòng chiếu
   * @param cellCode mã cell của dịch vụ
   * @param now thời điểm xét quá hạn
   * @param cellScope {@code true} khi người xem chỉ được thấy số tổng hợp theo cell
   */
  public static InstanceSummary of(
      TaskProjection p, String cellCode, Instant now, boolean cellScope) {
    boolean overdue = p.overdueAt(now);
    boolean orphan = orphan(p);
    Instant started = p.process().startedAt() == null ? p.createdAt() : p.process().startedAt();
    return new InstanceSummary(
        p.businessKey(),
        cellScope ? null : p.title(),
        p.templateCode() == null ? "" : p.templateCode(),
        p.process().templateVersion(),
        p.process().activityName(),
        p.taskId(),
        cellScope ? null : p.assigneeId(),
        p.candidateRole(),
        started,
        p.dueAt(),
        overdue || orphan,
        reason(p, now, overdue, orphan),
        cellCode);
  }

  /** Không có người xử lý hợp lệ: chưa giao cho ai và cũng không có nhóm ứng viên. */
  static boolean orphan(TaskProjection p) {
    return p.assigneeId() == null && p.candidateRole() == null;
  }

  private static String reason(TaskProjection p, Instant now, boolean overdue, boolean orphan) {
    if (!overdue && !orphan) {
      return null;
    }
    StringBuilder text = new StringBuilder();
    if (overdue) {
      long days = overdueDays(p.dueAt(), now);
      text.append(days == 0 ? "Quá hạn trong ngày" : "Quá hạn " + days + " ngày");
      if (p.assigneeId() == null) {
        text.append(orphan ? ", không có người xử lý" : ", chưa có người nhận xử lý");
      }
    } else {
      text.append("Không có người xử lý");
    }
    return text.append('.').toString();
  }

  /** Số ngày lịch từ ngày hạn tới hôm nay theo giờ Việt Nam. */
  static long overdueDays(Instant dueAt, Instant now) {
    LocalDate due = LocalDate.ofInstant(dueAt, BUSINESS_ZONE);
    LocalDate today = LocalDate.ofInstant(now, BUSINESS_ZONE);
    return Math.max(0, ChronoUnit.DAYS.between(due, today));
  }
}
