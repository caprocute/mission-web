package vn.viettel.okrkpi.workflow.domain;

import java.time.Instant;

/**
 * Thông tin tiến trình của một user task được chép vào bảng chiếu để giám sát không phải đọc {@code
 * ACT_*} (bổ sung DV-06-10, BS-01 SCR-1506). Mọi trường có thể {@code null} với dòng chiếu cũ.
 *
 * @param processInstanceId mã tiến trình
 * @param activityId mã bước BPMN của user task ({@code TASK_DEF_KEY_})
 * @param activityName tên bước trong định nghĩa BPMN khi là hằng, {@code null} khi là biểu thức
 * @param templateVersion phiên bản định nghĩa mẫu luồng đang chạy
 * @param startedAt thời điểm khởi tạo tiến trình
 */
public record ProcessRef(
    String processInstanceId,
    String activityId,
    String activityName,
    Integer templateVersion,
    Instant startedAt) {

  /** Không có thông tin tiến trình (dòng chiếu viết trước DV-06-10 hoặc tiến trình kỹ thuật). */
  public static final ProcessRef NONE = new ProcessRef(null, null, null, null, null);
}
