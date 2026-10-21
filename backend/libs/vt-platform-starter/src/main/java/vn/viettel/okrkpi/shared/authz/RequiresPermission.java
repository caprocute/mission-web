package vn.viettel.okrkpi.shared.authz;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Khai báo kiểm quyền tập trung qua OPA ở tầng {@code application} (NEN-014, ADR-21).
 *
 * <p>Có thể chỉ định {@link #action()} (khoá chức năng ma trận dạng {@code SCR-qqnn.<khoá>}),
 * {@link #operationId()} (mã thao tác OpenAPI để tra cứu {@code x-mission.aal}, {@code module},
 * {@code method}), hoặc cả hai.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresPermission {

  /** Khoá chức năng trong ma trận phân quyền (ví dụ {@code SCR-0501.giao}). */
  String action() default "";

  /** Mã thao tác OpenAPI (ví dụ {@code approveTask}) để tra bảng {@link OperationPolicyCatalog}. */
  String operationId() default "";

  /** Module API (ví dụ {@code tasks}); nếu bỏ trống sẽ suy từ {@link #operationId()}. */
  String module() default "";

  /** Phương thức HTTP (ví dụ {@code POST}); nếu bỏ trống sẽ suy từ {@link #operationId()}. */
  String method() default "";

  /** Loại tài nguyên nghiệp vụ (ví dụ {@code task}, {@code plan}, {@code okr}). */
  String resourceType() default "";

  /** Mức phân loại dữ liệu mặc định của thao tác ({@code C0}..{@code C3}). */
  String classification() default "C1";

  /** Cưỡng bức xác thực bước lên AAL2 ngay cả khi không khai báo {@link #operationId()}. */
  boolean stepUp() default false;
}
