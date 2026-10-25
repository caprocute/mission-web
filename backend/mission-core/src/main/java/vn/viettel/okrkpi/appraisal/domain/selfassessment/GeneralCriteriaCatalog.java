package vn.viettel.okrkpi.appraisal.domain.selfassessment;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Danh mục tiêu chí chung mặc định của phiếu tự đánh giá (BR-09-05) khi hồ sơ chưa có dòng {@code
 * general_criteria_result} giai đoạn {@code TU_DANH_GIA}: chín tiêu chí TCC-01 đến TCC-09 giống dữ
 * liệu giả lập của web. TL-02b chưa liệt kê nội dung tiêu chí và Policy Pack chưa có tham số danh
 * mục, nên đây là mặc định (suy luận, wiki 14 mục QA-06).
 */
public final class GeneralCriteriaCatalog {

  private static final Map<String, String> DEFAULT = new LinkedHashMap<>();

  static {
    String[] labels = {
      "Bản lĩnh chính trị, tư tưởng",
      "Phẩm chất đạo đức, lối sống",
      "Ý thức tổ chức kỷ luật",
      "Tác phong, lề lối làm việc",
      "Trách nhiệm nêu gương",
      "Đoàn kết nội bộ",
      "Năng lực đổi mới, sáng tạo",
      "Thực hiện điều đảng viên không được làm",
      "Dám nghĩ, dám làm, dám chịu trách nhiệm"
    };
    for (int i = 0; i < labels.length; i++) {
      DEFAULT.put("TCC-0" + (i + 1), labels[i]);
    }
  }

  private GeneralCriteriaCatalog() {}

  /** Mã và tên tiêu chí theo thứ tự hiển thị. */
  public static Map<String, String> defaults() {
    return Collections.unmodifiableMap(DEFAULT);
  }

  /** Tên tiêu chí của mã {@code code}; mã ngoài danh mục trả {@code null}. */
  public static String label(String code) {
    return DEFAULT.get(code);
  }
}
