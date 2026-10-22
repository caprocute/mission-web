package vn.viettel.okrkpi.platform.db.migration.lint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;

/**
 * Chú thích phân loại của một cột (TL-03 "Phân loại dữ liệu C0 đến C3 trên từng cột"), ví dụ {@code
 * {"cls":"C2","pii":true,"desc":"Số định danh cá nhân, mã hoá trường"}}. Khoá tuỳ chọn {@code
 * type_exception} ghi lý do dùng kiểu ngoài danh sách chuẩn (suy luận: TL-03 yêu cầu "ghi lý do"
 * nhưng không quy định nơi ghi).
 *
 * @param cls mức phân loại C0..C3
 * @param pii có phải dữ liệu cá nhân hay không
 * @param typeException lý do dùng kiểu ngoài danh sách chuẩn, rỗng nếu không có
 * @param plaintextJustification lý do để cột C2 ở dạng rõ có kiểm soát ({@code "enc":false} kèm
 *     {@code "justification"}, TL-03 appraisal.prospect_group.group_code), rỗng nếu không có
 */
public record ColumnClassification(
    String cls,
    boolean pii,
    Optional<String> typeException,
    Optional<String> plaintextJustification) {

  /** Các mức hợp lệ theo ADR-06. */
  public static final Set<String> LEVELS = Set.of("C0", "C1", "C2", "C3");

  private static final ObjectMapper JSON = new ObjectMapper();

  /** Phân tích chú thích; rỗng khi không có chú thích, không phải JSON hay {@code cls} sai. */
  public static Optional<ColumnClassification> parse(String comment) {
    if (comment == null || comment.isBlank()) {
      return Optional.empty();
    }
    try {
      JsonNode node = JSON.readTree(comment);
      if (node == null || !node.isObject() || !node.path("cls").isTextual()) {
        return Optional.empty();
      }
      String cls = node.get("cls").asText();
      if (!LEVELS.contains(cls)) {
        return Optional.empty();
      }
      JsonNode exception = node.path("type_exception");
      JsonNode justification = node.path("justification");
      boolean plaintext =
          node.path("enc").isBoolean()
              && !node.path("enc").asBoolean()
              && justification.isTextual()
              && !justification.asText().isBlank();
      return Optional.of(
          new ColumnClassification(
              cls,
              node.path("pii").asBoolean(false),
              exception.isTextual() && !exception.asText().isBlank()
                  ? Optional.of(exception.asText())
                  : Optional.empty(),
              plaintext ? Optional.of(justification.asText()) : Optional.empty()));
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  /** Cột không được đi sang kho phân tích qua CDC (C2 giả danh hoá riêng, C3 không bao giờ). */
  public boolean excludedFromAnalyticsCdc() {
    return "C2".equals(cls) || "C3".equals(cls);
  }
}
