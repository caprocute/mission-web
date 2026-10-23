package vn.viettel.okrkpi.workflow.domain.stage;

import java.util.Objects;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalScope;

/**
 * Một bước có người làm của mẫu phần 2 với giá trị mặc định của khối (TL-02b QT-09 bước 3 đến 12,
 * QT-12, QT-13, QT-15); tham số {@code wf_binding} của tenant ghi đè vai trò, phạm vi, hạn, nhắc và
 * hành vi quá hạn.
 *
 * @param elementId mã user task trong BPMN
 * @param code mã bước nghiệp vụ (khoá {@code buoc} của {@code wf_binding})
 * @param role vai trò baseline của người làm
 * @param target cách xác định người làm
 * @param scope phạm vi phân giải đơn vị khi {@code target = NHOM}
 * @param workingDays hạn của bước tính bằng ngày làm việc
 * @param remindBefore nhắc trước hạn bao nhiêu ngày làm việc
 * @param overdue hành vi khi quá hạn
 * @param signPurpose mốc ký số phải nhận EVT-18 trước khi đi tiếp ({@code purpose}), có thể {@code
 *     null}
 * @param skippable bước được bỏ qua theo điều kiện CEL {@code bo_qua_khi}
 * @param fromStart hạn tính từ lúc khởi tạo tiến trình (tổng hạn), không từ lúc vào bước
 */
public record StageStep(
    String elementId,
    String code,
    String role,
    Target target,
    ApprovalScope scope,
    int workingDays,
    int remindBefore,
    OverdueAction overdue,
    String signPurpose,
    boolean skippable,
    boolean fromStart) {

  /** Số ngày làm việc tối đa của một bước (SCR-1504 bảng trường dòng 5). */
  public static final int MAX_DAYS = 15;

  public StageStep {
    Objects.requireNonNull(elementId, "elementId");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(overdue, "overdue");
    if (workingDays < 1 || workingDays > MAX_DAYS) {
      throw new IllegalArgumentException("Hạn của bước phải từ 1 đến 15 ngày làm việc");
    }
    if (remindBefore < 0 || remindBefore >= workingDays) {
      throw new IllegalArgumentException("Số ngày nhắc trước phải nhỏ hơn hạn của bước");
    }
  }

  /** Bước có mốc ký số. */
  public boolean signs() {
    return signPurpose != null;
  }

  /** Cách xác định người làm bước. */
  public enum Target {
    /** Chính đối tượng được đánh giá (người tự đánh giá). */
    NGUOI,
    /** Nhóm ứng viên vai trò tại đơn vị đã phân giải. */
    NHOM,
    /** Người được chọn bằng tách vai trò và ít việc nhất (worker chọn người). */
    CHON
  }
}
