package vn.viettel.okrkpi.product.domain.evidence;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Ô do AI điền sẵn trên minh chứng (SCR-0702, BR-07-09): ô chỉ có hiệu lực khi người nộp xác nhận;
 * lúc xác nhận ghi vết ô giữ nguyên và ô đã sửa. Tệp C3 không bao giờ đi qua SVC-14 (TL-02a "Quy
 * ước về nội dung do AI tạo"), nên phiên bản có tệp C3 không được mang ô do AI điền.
 */
public final class AiPrefillRules {

  /** Các ô SVC-14 điền được, theo tên trường của hợp đồng ({@code aiFieldsEdited}). */
  public static final List<String> AI_FIELDS =
      List.of("docNumber", "docDate", "docSummary", "taskId");

  private AiPrefillRules() {}

  /** Vi phạm kèm tên trường để tầng ứng dụng trả lỗi trường RFC 9457. */
  public static final class Violation extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    private final String field;

    Violation(String field, String message) {
      super(message);
      this.field = field;
    }

    public String field() {
      return field;
    }
  }

  /**
   * Kiểm nội dung nháp và trả danh sách ô đã sửa đã chuẩn hoá (không trùng, theo thứ tự {@link
   * #AI_FIELDS}); rỗng khi không có ô AI.
   */
  public static List<String> check(
      boolean aiPrefill, boolean hasSuggestion, List<String> edited, boolean hasE2ee) {
    List<String> given = edited == null ? List.of() : edited;
    if (!aiPrefill) {
      if (!given.isEmpty()) {
        throw new Violation("aiFieldsEdited", "Chỉ ghi ô đã sửa khi có ô do AI điền");
      }
      return List.of();
    }
    if (hasE2ee) {
      throw new Violation(
          "aiPrefill", "Tệp mã hoá đầu cuối không được dùng thông tin do AI đọc từ tệp");
    }
    if (!hasSuggestion) {
      throw new Violation("aiSuggestionId", "Thiếu mã gợi ý của thông tin do AI điền");
    }
    Set<String> set = new LinkedHashSet<>();
    for (String f : given) {
      if (!AI_FIELDS.contains(f)) {
        throw new Violation("aiFieldsEdited", "Tên ô không hợp lệ");
      }
      set.add(f);
    }
    return AI_FIELDS.stream().filter(set::contains).toList();
  }

  /** Ô do AI điền mà người nộp giữ nguyên. */
  public static List<String> kept(List<String> edited) {
    List<String> out = new ArrayList<>(AI_FIELDS);
    if (edited != null) {
      out.removeAll(edited);
    }
    return List.copyOf(out);
  }
}
