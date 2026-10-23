package vn.viettel.okrkpi.docgen.application.render;

import java.util.List;

/**
 * Mẫu 1A (tập thể), 1B, 1C, 1D (cá nhân theo nhóm đối tượng) của DV-11-02: bản kiểm điểm điền sẵn
 * từ snapshot đã quyết định (BR-11-02). Mọi trường đi theo kê khai; nhãn tiêu chí chung lấy bảng
 * {@code MAU_1} của mẫu (BR-11-04). Mẫu áp cho nhóm đối tượng nào do {@code subject_group_codes}
 * của {@code docgen.template} quyết định, không do bộ ánh xạ.
 */
public final class Mau1Renderer extends ManifestFormRenderer {

  public static final List<String> CODES = List.of("MAU_1A", "MAU_1B", "MAU_1C", "MAU_1D");

  public Mau1Renderer(String formCode) {
    super(formCode);
    if (!CODES.contains(formCode)) {
      throw new IllegalArgumentException("Không phải Mẫu 1: " + formCode);
    }
  }

  /** Bốn bộ ánh xạ của Mẫu 1A–1D. */
  public static List<FormRenderer> all() {
    return CODES.stream().<FormRenderer>map(Mau1Renderer::new).toList();
  }
}
