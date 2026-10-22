package vn.viettel.okrkpi.config.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Một bước saga chốt kỳ chiếu vào {@code config.period_lock_step}. Hợp đồng đánh số bước 0–5 còn
 * cột {@code step_no} có CHECK {@code >= 1}, nên {@code step_no = stepNo + 1} (wiki trang 14).
 */
public record LockStep(
    int stepNo,
    String name,
    String status,
    Instant startedAt,
    Instant finishedAt,
    Map<String, Object> resultSummary,
    String compensationAction,
    Instant compensatedAt) {

  /** Tên sáu bước theo TL-02a QT-08. */
  public static final List<String> NAMES =
      List.of(
          "Kiểm tra tiền điều kiện",
          "Khoá kỳ",
          "Tính điểm hàng loạt",
          "Sinh mẫu và bảng chấm điểm",
          "Ký số lô",
          "Hoàn tất");

  /** Hành động bù trừ mặc định của từng bước (bước 0 và 5 không có). */
  public static final List<String> COMPENSATIONS =
      java.util.Arrays.asList(
          null,
          "Trả kỳ về MO",
          "Đánh dấu lô điểm bị thay thế",
          "Thu hồi tệp đã sinh",
          "Huỷ yêu cầu ký đang chờ",
          null);

  /** Bước chưa chạy. */
  public static LockStep pending(int stepNo) {
    return new LockStep(
        stepNo, NAMES.get(stepNo), "CHO", null, null, Map.of(), COMPENSATIONS.get(stepNo), null);
  }

  public boolean done() {
    return "DAT".equals(status) || "DAT_CO_CANH_BAO".equals(status);
  }
}
