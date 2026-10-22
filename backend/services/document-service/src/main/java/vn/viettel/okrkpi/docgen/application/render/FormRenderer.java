package vn.viettel.okrkpi.docgen.application.render;

import java.util.Map;

/**
 * Bộ ánh xạ dữ liệu biểu mẫu sang trường của mẫu DOCX cho một mã mẫu và dải phiên bản. Mẫu nghiệp
 * vụ (DV-11-02, DV-11-03) chỉ cần thêm một bean hiện thực giao diện này cùng tệp mẫu; mẫu không có
 * bộ ánh xạ riêng dùng {@link PlainFieldRenderer}.
 */
public interface FormRenderer {

  /** Mã mẫu ({@code DossierFormCode}), ví dụ {@code MAU_1A}. */
  String formCode();

  /** Bộ ánh xạ dùng được cho phiên bản mẫu này. */
  default boolean supports(int versionNo) {
    return true;
  }

  /**
   * Giá trị thay cho từng chỗ giữ {@code ${ten}} trong mẫu.
   *
   * @param data dữ liệu đã giải tuần tự từ JSON chuẩn tắc
   */
  Map<String, String> fields(Map<String, Object> data);
}
