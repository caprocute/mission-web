package vn.viettel.okrkpi.task.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Tóm tắt bất biến của một nhiệm vụ cho mô-đun khác đọc qua {@link TaskApi} (CORE-02-12).
 *
 * @param id nhiệm vụ
 * @param periodId kỳ
 * @param periodBucket khoá phân vùng của kỳ, để mô-đun khác ghi bảng phân vùng cùng kỳ
 * @param code mã nhiệm vụ hiển thị
 * @param title tên nhiệm vụ
 * @param status trạng thái chuẩn ({@code DANG_THUC_HIEN}, {@code HOAN_THANH}…)
 * @param terminal {@code true} khi {@code HOAN_THANH} hoặc {@code HUY}
 * @param overdue cờ quá hạn
 * @param assignerId người giao
 * @param acceptorId người nghiệm thu, có thể {@code null}
 * @param leadAssigneeId người chủ trì đang hiệu lực, có thể {@code null}
 * @param dueDate hạn hiệu lực (hạn gia hạn nếu có, ngược lại hạn gốc)
 * @param plannedQuantity khối lượng kế hoạch
 * @param doneQuantity khối lượng đã làm
 * @param rowVersion {@code row_version} hiện tại
 */
public record TaskSummary(
    UUID id,
    UUID periodId,
    int periodBucket,
    String code,
    String title,
    String status,
    boolean terminal,
    boolean overdue,
    UUID assignerId,
    UUID acceptorId,
    UUID leadAssigneeId,
    LocalDate dueDate,
    BigDecimal plannedQuantity,
    BigDecimal doneQuantity,
    long rowVersion) {

  public TaskSummary {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(status, "status");
  }
}
