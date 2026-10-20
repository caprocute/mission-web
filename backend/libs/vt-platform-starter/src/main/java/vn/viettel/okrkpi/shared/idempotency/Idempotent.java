package vn.viettel.okrkpi.shared.idempotency;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Đánh dấu phương thức controller của thao tác ghi nhận header {@code Idempotency-Key} (baseline
 * mục 13; {@code common.yaml} tham số {@code IdempotencyKey}; BS-02 HD-02; TL-03 "Bảng
 * idempotency_key").
 *
 * <p>Thư viện nền chạy phương thức trong một giao dịch (tham gia giao dịch sẵn có nếu có), chèn
 * khoá {@code DANG_XU_LY} vào {@code <schema>.idempotency_key} trước khi gọi, và chuyển {@code
 * XONG} kèm phản hồi trong cùng giao dịch đó, nên không có trường hợp đã thực hiện mà chưa lưu
 * khoá:
 *
 * <ul>
 *   <li>cùng khoá, cùng băm yêu cầu, đã {@code XONG} trong 48 giờ: trả lại phản hồi đã lưu, không
 *       chạy lại phương thức;
 *   <li>cùng khoá, khác băm: 422 {@code COMMON-422-IDEMPOTENCY-MISMATCH};
 *   <li>cùng khoá đang được một yêu cầu khác xử lý: 409 kèm {@code Retry-After} (suy luận, hợp đồng
 *       không ghi);
 *   <li>thiếu header khi {@link #required()}: 400.
 * </ul>
 *
 * <p>Phương thức ném lỗi hoặc trả mã khác 2xx thì khoá không được lưu (giao dịch huỷ hoặc dòng
 * {@code DANG_XU_LY} bị xoá) để client sửa rồi gửi lại.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Idempotent {

  /** Thiếu header thì trả 400; {@code false} cho thao tác chỉ khuyến nghị gửi khoá. */
  boolean required() default true;

  /**
   * Schema chứa bảng {@code idempotency_key}. Rỗng: lấy từ gói của controller ({@code
   * vn.viettel.okrkpi.<module>..} → {@code <module>}), rồi tới {@code okrkpi.idempotency.schema}.
   */
  String schema() default "";

  /**
   * Lưu thân phản hồi để phát lại. Thân có thuộc tính {@code @Classified} mức C2 trở lên không bao
   * giờ được lưu (TL-03: chỉ lưu {@code resource_ref}); đặt {@code false} để không lưu thân cả khi
   * không có đánh dấu.
   */
  boolean storeBody() default true;
}
