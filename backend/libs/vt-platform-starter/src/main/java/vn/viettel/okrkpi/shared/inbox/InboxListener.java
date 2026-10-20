package vn.viettel.okrkpi.shared.inbox;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * NEN-012 · ADR-03 · Đánh dấu phương thức tiêu thụ sự kiện Kafka idempotent theo quy trình chuẩn
 * của TL-03 ("Inbox idempotent", L4261–4320):
 *
 * <ol>
 *   <li>Giải mã tiêu đề CloudEvents ({@code id}, {@code tenantid}, {@code aggregateversion}, {@code
 *       traceparent}, {@code actorid}, {@code policyversion}, {@code classification}) và thân Avro.
 *   <li>Mở giao dịch CSDL cục bộ và đặt ngữ cảnh RLS ({@code SET LOCAL app.tenant_id}).
 *   <li>Chèn {@code <schema>.inbox_processed ... ON CONFLICT (consumer, event_id) DO NOTHING
 *       RETURNING event_id}; nếu không có dòng trả về thì bỏ qua sự kiện trùng.
 *   <li>Khi {@link #aggregateType()} khác rỗng, kiểm và cập nhật {@code
 *       <schema>.inbox_aggregate_version}; nếu {@code aggregateVersion <= last_version} thì bỏ qua
 *       sự kiện cũ đến muộn (vẫn giữ dấu vết trong {@code inbox_processed}).
 *   <li>Gọi phương thức nghiệp vụ trong cùng giao dịch.
 *   <li>Commit giao dịch rồi mới xác nhận offset Kafka ở {@code afterCommit}.
 * </ol>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface InboxListener {

  /** Topic Kafka nguồn (ví dụ {@code EventTopics.EVT_05_TIEN_DO_DA_CAP_NHAT}). */
  String topic();

  /**
   * Định danh duy nhất của consumer (cột {@code consumer varchar(100)} trong {@code
   * inbox_processed} và {@code inbox_aggregate_version}, ví dụ {@code
   * "scoring.progress-projection"}).
   */
  String consumer();

  /**
   * Loại thực thể gốc để kiểm thứ tự phiên bản trong {@code inbox_aggregate_version} (ví dụ {@code
   * "Task"}, tối đa 64 ký tự). Để trống khi sự kiện không biểu diễn thay đổi phiên bản của một thực
   * thể có thứ tự đơn điệu.
   */
  String aggregateType() default "";
}
