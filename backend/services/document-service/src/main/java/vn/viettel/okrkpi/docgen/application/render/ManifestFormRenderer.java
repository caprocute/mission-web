package vn.viettel.okrkpi.docgen.application.render;

import java.util.Map;
import java.util.Objects;

/**
 * Nền chung của bộ ánh xạ cho mẫu có tệp kê khai: mẫu phiên bản cũ không có kê khai vẫn trộn được
 * theo chỗ giữ {@code ${ten}} bằng cách trải phẳng dữ liệu như {@link PlainFieldRenderer}.
 */
public abstract class ManifestFormRenderer implements FormRenderer {

  private final String formCode;
  private final PlainFieldRenderer plain = new PlainFieldRenderer();

  protected ManifestFormRenderer(String formCode) {
    this.formCode = Objects.requireNonNull(formCode, "formCode");
  }

  @Override
  public final String formCode() {
    return formCode;
  }

  @Override
  public Map<String, String> fields(Map<String, Object> data) {
    return plain.fields(data);
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "[" + formCode + "]";
  }
}
