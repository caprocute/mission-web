package vn.viettel.okrkpi.task.domain.plan;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import vn.viettel.okrkpi.task.domain.lifecycle.RejectionCode;

/**
 * Máy trạng thái {@code task.plan_version} (TL-02a QT-04 "Trạng thái đối tượng"): (chưa có) →
 * {@code NHAP} → {@code CHO_DUYET} → {@code TRA_LAI} → {@code CHO_DUYET} → {@code DA_DUYET}. Lưu
 * nháp và sao chép giữ nguyên trạng thái và chỉ hợp lệ ở {@code NHAP}, {@code TRA_LAI}; {@code
 * DA_DUYET} bất biến. Kỳ đã khoá từ chối mọi lệnh trước khi xét bảng.
 */
public final class PlanVersionStateMachine {

  private static final Map<PlanVersionStatus, Map<PlanCommand, PlanVersionStatus>> TABLE =
      new EnumMap<>(PlanVersionStatus.class);

  static {
    for (PlanVersionStatus editable :
        new PlanVersionStatus[] {PlanVersionStatus.NHAP, PlanVersionStatus.TRA_LAI}) {
      Map<PlanCommand, PlanVersionStatus> row = new EnumMap<>(PlanCommand.class);
      row.put(PlanCommand.SAVE, editable);
      row.put(PlanCommand.COPY_FROM_PREVIOUS, editable);
      row.put(PlanCommand.SUBMIT, PlanVersionStatus.CHO_DUYET);
      TABLE.put(editable, row);
    }
    Map<PlanCommand, PlanVersionStatus> pending = new EnumMap<>(PlanCommand.class);
    pending.put(PlanCommand.RETURN, PlanVersionStatus.TRA_LAI);
    pending.put(PlanCommand.APPROVE, PlanVersionStatus.DA_DUYET);
    TABLE.put(PlanVersionStatus.CHO_DUYET, pending);
    TABLE.put(PlanVersionStatus.DA_DUYET, new EnumMap<>(PlanCommand.class));
  }

  private PlanVersionStateMachine() {}

  /** Trạng thái của phiên bản 1 khi tạo kế hoạch. */
  public static PlanVersionStatus initial() {
    return PlanVersionStatus.NHAP;
  }

  public static PlanTransition apply(
      PlanVersionStatus current, PlanCommand command, boolean periodWritable) {
    if (!periodWritable) {
      return new PlanTransition.Rejected(
          RejectionCode.PERIOD_LOCKED, "Kỳ đánh giá đã khoá, không thể thay đổi kế hoạch");
    }
    PlanVersionStatus to = TABLE.get(current).get(command);
    if (to == null) {
      return new PlanTransition.Rejected(
          RejectionCode.TRANSITION_NOT_ALLOWED,
          current == PlanVersionStatus.DA_DUYET
              ? "Phiên bản kế hoạch đã được duyệt, không thể thay đổi"
              : "Thao tác không phù hợp với trạng thái hiện tại của kế hoạch");
    }
    return new PlanTransition.Accepted(current, to, command);
  }

  /** Số phiên bản hiệu lực: phiên bản {@code DA_DUYET} có số lớn nhất ({@code is_current}). */
  public static Optional<Integer> effectiveVersion(Collection<VersionState> versions) {
    return versions.stream()
        .filter(v -> v.status() == PlanVersionStatus.DA_DUYET)
        .map(VersionState::versionNo)
        .max(Integer::compare);
  }

  /** Số và trạng thái của một phiên bản. */
  public record VersionState(int versionNo, PlanVersionStatus status) {}
}
