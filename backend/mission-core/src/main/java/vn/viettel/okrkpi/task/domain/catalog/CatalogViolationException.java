package vn.viettel.okrkpi.task.domain.catalog;

import java.io.Serial;
import java.util.List;

/** Dữ liệu danh mục vi phạm quy tắc nhập (BR-04-14, ràng buộc trường SCR-0401) hoặc tạo vòng. */
public class CatalogViolationException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Loại lỗi theo hợp đồng nhập Excel ({@code CatalogImportJob.errorGroups[].kind}). */
  public enum Kind {
    /** Thiếu trường bắt buộc. */
    THIEU,
    /** Tên chứa từ mơ hồ. */
    MO_HO,
    /** Lỗi khác: giá trị ngoài khoảng, sai định dạng, trùng mã. */
    KHAC
  }

  /** Một lỗi theo trường của {@code CatalogItemInput}. */
  public record Violation(String field, String message, Kind kind) {
    public Violation(String field, String message) {
      this(field, message, Kind.KHAC);
    }
  }

  private final transient List<Violation> violations;

  public CatalogViolationException(List<Violation> violations) {
    super(violations.isEmpty() ? "" : violations.get(0).message());
    this.violations = List.copyOf(violations);
  }

  public List<Violation> violations() {
    return violations;
  }
}
