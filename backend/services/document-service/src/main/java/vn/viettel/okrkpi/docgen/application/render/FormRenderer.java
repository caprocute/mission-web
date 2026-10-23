package vn.viettel.okrkpi.docgen.application.render;

import java.util.Map;

/**
 * Bộ ánh xạ dữ liệu biểu mẫu sang trường của mẫu DOCX cho một mã mẫu và dải phiên bản. Mẫu nghiệp
 * vụ (DV-11-02, DV-11-03) chỉ cần thêm một bean hiện thực giao diện này cùng tệp mẫu; mẫu không có
 * bộ ánh xạ riêng dùng {@link PlainFieldRenderer}.
 *
 * <p>Mẫu có tệp kê khai (DL-05) đi qua {@link FormModelBuilder}; bộ ánh xạ chỉ chỉnh dữ liệu trước
 * ({@link #prepare}: sắp xếp, suy ra cột, kiểm phiên bản) và bổ sung mô hình sau ({@link #finish}).
 */
public interface FormRenderer {

  /** Mã mẫu ({@code DossierFormCode}), ví dụ {@code MAU_1A}. */
  String formCode();

  /** Bộ ánh xạ dùng được cho phiên bản mẫu này. */
  default boolean supports(int versionNo) {
    return true;
  }

  /**
   * Giá trị thay cho từng chỗ giữ {@code ${ten}} trong mẫu không có kê khai.
   *
   * @param data dữ liệu đã giải tuần tự từ JSON chuẩn tắc
   */
  Map<String, String> fields(Map<String, Object> data);

  /** Chỉnh dữ liệu trước khi ánh xạ theo kê khai; mặc định giữ nguyên. */
  default Map<String, Object> prepare(
      Map<String, Object> data, TemplateManifest manifest, RenderContext ctx) {
    return data;
  }

  /** Bổ sung mô hình đã dựng theo kê khai; mặc định giữ nguyên. */
  default MergeModel finish(
      MergeModel model, Map<String, Object> data, TemplateManifest manifest, RenderContext ctx) {
    return model;
  }
}
