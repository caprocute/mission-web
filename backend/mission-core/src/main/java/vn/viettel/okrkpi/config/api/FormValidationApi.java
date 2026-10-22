package vn.viettel.okrkpi.config.api;

import java.util.List;
import java.util.Map;
import java.util.Set;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Kiểm dữ liệu biểu mẫu động tại máy chủ (CORE-08-14, ADR-05 lớp 3, BR-18-04, BR-18-11): API nghiệp
 * vụ của MOD-01..MOD-04 gọi cổng này trước khi lưu dữ liệu phiếu gửi từ trình duyệt. Kết quả gồm
 * lỗi theo trường (khớp {@code FieldError} của hợp đồng) và các khoá dữ liệu lớp C2 để module gọi
 * {@code FieldEncryptor} khi lưu. Kiểm ở trình duyệt chỉ để phản hồi nhanh; kết quả ở đây là kết
 * luận.
 */
public interface FormValidationApi {

  /**
   * @param formKey khoá biểu mẫu
   * @param schemaVersion phiên bản mẫu mà trình duyệt đã dùng để lập phiếu
   * @param payload dữ liệu phiếu (đối tượng JSON đã đọc thành {@code Map})
   */
  Result validate(
      TenantId tenant, String formKey, String schemaVersion, Map<String, Object> payload);

  /** Một lỗi theo trường: {@code field} là đường dẫn chấm trong dữ liệu phiếu. */
  record Violation(String field, String message, String rule) {}

  /**
   * @param errors lỗi theo trường, rỗng khi hợp lệ
   * @param c2Fields khoá dữ liệu khai báo {@code x-classification: C2} có trong biểu mẫu
   * @param schemaVersion phiên bản mẫu đã dùng để kiểm
   */
  record Result(List<Violation> errors, Set<String> c2Fields, String schemaVersion) {
    public boolean valid() {
      return errors.isEmpty();
    }
  }
}
