package vn.viettel.okrkpi.okr.application.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Cổng đọc KR cần nhắc check-in và khoá giữ chỗ chống gửi lặp (CORE-01-11). */
public interface OkrCheckinReminderRepository {

  /**
   * KR của mục tiêu {@code DA_DUYET} trong kỳ, chưa đóng.
   *
   * @param owner chủ sở hữu (người của mục tiêu cá nhân, hoặc người lập mục tiêu tập thể)
   * @param delegate người được giao check-in thay còn hiệu lực ngày nhắc, có thể {@code null}
   * @param activeSince thời điểm mục tiêu được duyệt, mốc bắt đầu tính chu kỳ quá hạn
   * @param lastSubmittedAt lần check-in {@code DA_GUI} gần nhất, có thể {@code null}
   */
  record DueKeyResult(
      UUID id,
      String code,
      String frequency,
      UUID objectiveId,
      UUID ownerUnitId,
      UUID owner,
      UUID delegate,
      Instant activeSince,
      Instant lastSubmittedAt,
      long rowVersion) {}

  /** Một trang theo {@code id} tăng dần, sau {@code afterId} (null là từ đầu). */
  List<DueKeyResult> dueKeyResults(
      TenantId tenant, UUID periodId, LocalDate today, UUID afterId, int limit);

  /**
   * Giữ chỗ khoá nhắc; {@code false} khi khoá đã có (bản sao khác hay lần chạy trước đã gửi). Ghi
   * trong cùng giao dịch với EVT-21 nên lỗi giữa chừng thì cả hai cùng huỷ.
   */
  boolean claimNotice(TenantId tenant, String key);
}
