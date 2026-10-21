package vn.viettel.okrkpi.shared.kernel;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Gắn mức phân loại dữ liệu (ADR-06) cho trường, thành phần record, tham số hoặc kiểu. Thư viện nền
 * đọc annotation này lúc chạy để che nhật ký và mã hoá trường (NEN-015, NEN-016); bản thân
 * annotation không làm gì.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({
  ElementType.FIELD,
  ElementType.RECORD_COMPONENT,
  ElementType.PARAMETER,
  ElementType.METHOD,
  ElementType.TYPE
})
public @interface Classified {

  /** Mức phân loại. */
  Classification value();
}
