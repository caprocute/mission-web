package vn.viettel.okrkpi.shared.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * CORE-00-05 · BR-12-10 · Đánh dấu use case trả dữ liệu C2 không đi qua {@code FieldEncryptor} (mở
 * minh chứng, tải biểu mẫu): mỗi lần phương thức trả về thành công ghi một đối tượng vào nhật ký
 * truy cập C2 ({@code sys.audit.v1}), cùng cơ chế gom với giải mã trường.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface C2Access {

  /** Loại đối tượng, ví dụ {@code MINH_CHUNG}, {@code BIEU_MAU}. */
  String objectType();

  /** Vị trí tham số mang mã đối tượng (mặc định tham số đầu). */
  int idArg() default 0;

  /** Toạ độ ghi vào nhật ký thay cho {@code schema.bảng.cột}. */
  String column() default C2AccessAuditor.FILE_COLUMN;
}
