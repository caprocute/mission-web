package vn.viettel.okrkpi.task.domain.catalog;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Đọc một dòng của tệp nhập danh mục theo mẫu PL 1 Bảng 3 (CORE-02-04): nhận diện cột theo tiêu đề
 * (không phân biệt dấu, chữ hoa), chuyển ô thành {@link CatalogItemDraft}. Lỗi chuyển kiểu là loại
 * {@code KHAC}; kiểm nghiệp vụ dùng chung {@link CatalogRules#validate}.
 */
public final class CatalogSheet {

  /** Cột của mẫu nhập. */
  public enum Column {
    CODE("Mã công việc", false, "ma", "ma cong viec", "ma cv"),
    NAME("Tên công việc", true, "ten cong viec", "cong viec", "ten"),
    PRODUCT("Sản phẩm", true, "san pham", "san pham dau ra"),
    UNIT("Đơn vị tính", true, "don vi tinh", "dvt"),
    DIFFICULTY("Mức khó", true, "muc kho", "muc do kho"),
    SCORE("Điểm chuẩn", true, "diem chuan", "diem"),
    QUALITY("Tiêu chuẩn chất lượng", true, "tieu chuan chat luong", "tieu chuan"),
    EVIDENCE("Minh chứng yêu cầu", true, "minh chung yeu cau", "minh chung"),
    GROUP("Nhóm mặc định", false, "nhom mac dinh", "nhom"),
    DAYS("Thời hạn chuẩn", false, "thoi han chuan", "thoi han", "so ngay");

    private final String label;
    private final boolean required;
    private final List<String> aliases;

    Column(String label, boolean required, String... aliases) {
      this.label = label;
      this.required = required;
      this.aliases = List.of(aliases);
    }

    public String label() {
      return label;
    }
  }

  /** Vị trí cột trong tệp theo tiêu đề. */
  public record Columns(Map<Column, Integer> index) {

    /** Tiêu đề bắt buộc mà tệp thiếu. */
    public List<Column> missingRequired() {
      List<Column> out = new ArrayList<>();
      for (Column c : Column.values()) {
        if (c.required && !index.containsKey(c)) {
          out.add(c);
        }
      }
      return out;
    }

    String cell(List<String> cells, Column c) {
      Integer at = index.get(c);
      if (at == null || at >= cells.size()) {
        return null;
      }
      String v = cells.get(at);
      return v == null || v.isBlank() ? null : v.strip();
    }
  }

  /** Kết quả chuyển một dòng: bản nháp hoặc lỗi chuyển kiểu. */
  public record Parsed(
      CatalogItemDraft draft, List<CatalogViolationException.Violation> violations) {}

  private CatalogSheet() {}

  /** Nhận diện cột từ dòng tiêu đề; phần trong ngoặc ("Mức khó (1-5)") được bỏ qua. */
  public static Columns columns(List<String> header) {
    Map<Column, Integer> index = new EnumMap<>(Column.class);
    for (int i = 0; i < header.size(); i++) {
      String h = normalizeHeader(header.get(i));
      for (Column c : Column.values()) {
        if (!index.containsKey(c) && c.aliases.contains(h)) {
          index.put(c, i);
          break;
        }
      }
    }
    return new Columns(index);
  }

  /** Dòng không có ô nào có giá trị. */
  public static boolean isBlank(List<String> cells) {
    return cells.stream().allMatch(c -> c == null || c.isBlank());
  }

  /**
   * Chuyển ô thành bản nháp; ô trống để {@code null} cho {@link CatalogRules#validate} báo thiếu.
   */
  public static Parsed parse(List<String> cells, Columns c) {
    List<CatalogViolationException.Violation> errors = new ArrayList<>();
    Integer difficulty =
        integer(c.cell(cells, Column.DIFFICULTY))
            .orElseGet(
                () -> {
                  if (c.cell(cells, Column.DIFFICULTY) != null) {
                    errors.add(v("difficulty", "Mức khó phải là số nguyên từ 1 đến 5."));
                  }
                  return null;
                });
    BigDecimal score = null;
    String rawScore = c.cell(cells, Column.SCORE);
    if (rawScore != null) {
      try {
        score = new BigDecimal(rawScore.replace(',', '.'));
      } catch (NumberFormatException e) {
        errors.add(v("baseScore", "Điểm chuẩn phải là số từ 0 đến 100."));
      }
    }
    Integer days = null;
    String rawDays = c.cell(cells, Column.DAYS);
    if (rawDays != null) {
      days = integer(rawDays).orElse(null);
      if (days == null) {
        errors.add(v("standardDays", "Thời hạn chuẩn phải là số ngày làm việc."));
      }
    }
    String group = "THUONG_XUYEN";
    String rawGroup = c.cell(cells, Column.GROUP);
    if (rawGroup != null) {
      String g = AccentFold.fold(rawGroup).replaceAll("[^a-z]+", "");
      if (g.equals("thuongxuyen")) {
        group = "THUONG_XUYEN";
      } else if (g.equals("thenchot")) {
        group = "THEN_CHOT";
      } else {
        group = null;
        errors.add(v("defaultGroup", "Nhóm mặc định là Thường xuyên hoặc Then chốt."));
      }
    }
    String code = c.cell(cells, Column.CODE);
    CatalogItemDraft draft =
        new CatalogItemDraft(
            code == null ? null : code.toUpperCase(java.util.Locale.ROOT),
            null,
            c.cell(cells, Column.NAME),
            c.cell(cells, Column.PRODUCT),
            c.cell(cells, Column.UNIT),
            difficulty,
            score,
            c.cell(cells, Column.QUALITY),
            c.cell(cells, Column.EVIDENCE),
            group == null ? "THUONG_XUYEN" : group,
            days,
            null);
    return new Parsed(draft, errors);
  }

  private static Optional<Integer> integer(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    try {
      BigDecimal d = new BigDecimal(raw.replace(',', '.'));
      return Optional.of(d.intValueExact());
    } catch (NumberFormatException | ArithmeticException e) {
      return Optional.empty();
    }
  }

  static String normalizeHeader(String header) {
    if (header == null) {
      return "";
    }
    return AccentFold.fold(header.replaceAll("\\(.*?\\)", " "))
        .replaceAll("[^a-z0-9]+", " ")
        .strip();
  }

  private static CatalogViolationException.Violation v(String field, String message) {
    return new CatalogViolationException.Violation(field, message);
  }
}
