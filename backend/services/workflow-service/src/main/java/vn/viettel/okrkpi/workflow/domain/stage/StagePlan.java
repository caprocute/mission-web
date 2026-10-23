package vn.viettel.okrkpi.workflow.domain.stage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalScope;

/**
 * Tham số của từng bước chụp lúc khởi tạo (mặc định của mẫu ghi đè bằng {@code wf_binding}), giữ
 * trong biến {@code keHoachBuoc} dạng chuỗi gọn để mỗi lần vào bước tính hạn, nhóm ứng viên mà
 * không phụ thuộc tham số đổi giữa chừng (TL-01 G2 quy tắc 5: biến nhỏ).
 *
 * <p>Khuôn: {@code elementId|role|SCOPE|ngày|nhắc|QUA_HAN|người thay} cách nhau bằng {@code ;}.
 */
public record StagePlan(Map<String, Planned> steps) {

  private static final String STEP_SEPARATOR = ";";
  private static final String FIELD_SEPARATOR = "\\|";

  public StagePlan {
    steps = Map.copyOf(steps);
  }

  /**
   * Tham số của một bước.
   *
   * @param elementId mã user task
   * @param role vai trò
   * @param scope phạm vi đơn vị
   * @param workingDays hạn (ngày làm việc)
   * @param remindBefore nhắc trước (ngày làm việc)
   * @param overdue hành vi khi quá hạn
   * @param substituteId người thay khi {@code CHUYEN_CAP_PHO_UY_QUYEN}, có thể {@code null}
   */
  public record Planned(
      String elementId,
      String role,
      ApprovalScope scope,
      int workingDays,
      int remindBefore,
      OverdueAction overdue,
      UUID substituteId) {

    public Planned {
      Objects.requireNonNull(elementId, "elementId");
      Objects.requireNonNull(role, "role");
      Objects.requireNonNull(scope, "scope");
      Objects.requireNonNull(overdue, "overdue");
      if (role.contains("|") || role.contains(";")) {
        throw new IllegalArgumentException("Mã vai trò không hợp lệ");
      }
    }
  }

  /** Tham số của bước. */
  public Optional<Planned> step(String elementId) {
    return Optional.ofNullable(steps.get(elementId));
  }

  /** Mã hoá gọn. */
  public String encode() {
    List<String> parts = new ArrayList<>(steps.size());
    for (Planned p : steps.values()) {
      parts.add(
          String.join(
              "|",
              p.elementId(),
              p.role(),
              p.scope().name(),
              Integer.toString(p.workingDays()),
              Integer.toString(p.remindBefore()),
              p.overdue().name(),
              p.substituteId() == null ? "" : p.substituteId().toString()));
    }
    parts.sort(String::compareTo);
    return String.join(STEP_SEPARATOR, parts);
  }

  /** Giải mã; chuỗi sai dạng ném {@link IllegalArgumentException}. */
  public static StagePlan decode(String encoded) {
    Map<String, Planned> steps = new LinkedHashMap<>();
    if (encoded == null || encoded.isBlank()) {
      return new StagePlan(steps);
    }
    for (String part : encoded.split(STEP_SEPARATOR, -1)) {
      String[] f = part.split(FIELD_SEPARATOR, -1);
      if (f.length != 7) {
        throw new IllegalArgumentException("Kế hoạch bước sai dạng");
      }
      Planned p =
          new Planned(
              f[0],
              f[1],
              ApprovalScope.parse(f[2])
                  .orElseThrow(() -> new IllegalArgumentException("Phạm vi bước sai")),
              Integer.parseInt(f[3]),
              Integer.parseInt(f[4]),
              OverdueAction.parse(f[5])
                  .orElseThrow(() -> new IllegalArgumentException("Hành vi quá hạn sai")),
              f[6].isEmpty() ? null : UUID.fromString(f[6]));
      steps.put(p.elementId(), p);
    }
    return new StagePlan(steps);
  }
}
