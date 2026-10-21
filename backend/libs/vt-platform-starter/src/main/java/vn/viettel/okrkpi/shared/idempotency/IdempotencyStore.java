package vn.viettel.okrkpi.shared.idempotency;

import java.time.Duration;
import java.util.Optional;

/**
 * Kho khoá chống ghi trùng trên bảng {@code <schema>.idempotency_key} (TL-01 "Quản lý giao dịch":
 * {@code IdempotencyStore}; TL-03 "Bảng idempotency_key"). Mọi thao tác phải chạy trong giao dịch
 * của tác dụng nghiệp vụ, để khoá và tác dụng cùng được ghi hoặc cùng huỷ.
 *
 * <p>Dùng trực tiếp trong use case (mẫu {@code UpdateProgressUseCase} của TL-01) hoặc gián tiếp qua
 * {@link Idempotent} trên controller.
 */
public interface IdempotencyStore {

  /** Thời hạn giữ khoá (BS-02 HD-02: 48 giờ, TL-03 thắng TL-01). */
  Duration RETENTION = Duration.ofHours(48);

  /** Khoá còn hạn (48 giờ) của phạm vi, nếu có. */
  Optional<IdempotencyRecord> find(IdempotencyScope scope, String key);

  /**
   * Bắt đầu xử lý một khoá: chèn {@code DANG_XU_LY} nếu khoá mới, hoặc trả nhánh phát lại, lệch
   * băm, đang xử lý. Không chờ khi yêu cầu khác đang giữ cùng khoá.
   *
   * @param requestHash SHA-256 của yêu cầu ({@link RequestHasher})
   */
  BeginResult begin(IdempotencyScope scope, String key, byte[] requestHash);

  /**
   * Chuyển khoá sang {@code XONG} kèm phản hồi, trong cùng giao dịch với tác dụng.
   *
   * @param responseBody JSON đã trả; {@code null} khi không lưu thân (thân có trường C2)
   * @param resourceRef tài nguyên đã tạo, tối đa 128 ký tự, có thể {@code null}
   */
  void complete(
      BeginResult.Started started, int responseCode, String responseBody, String resourceRef);

  /** Bỏ khoá {@code DANG_XU_LY} khi thao tác không thành công, để client gửi lại được. */
  void abandon(BeginResult.Started started);
}
