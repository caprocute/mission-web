package vn.viettel.okrkpi.task.domain.catalog;

import java.io.Serial;
import java.util.List;

/** Dữ liệu danh mục vi phạm quy tắc nhập (BR-04-14, ràng buộc trường SCR-0401) hoặc tạo vòng. */
public class CatalogViolationException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Một lỗi theo trường của {@code CatalogItemInput}. */
  public record Violation(String field, String message) {}

  private final transient List<Violation> violations;

  public CatalogViolationException(List<Violation> violations) {
    super(violations.isEmpty() ? "" : violations.get(0).message());
    this.violations = List.copyOf(violations);
  }

  public List<Violation> violations() {
    return violations;
  }
}
