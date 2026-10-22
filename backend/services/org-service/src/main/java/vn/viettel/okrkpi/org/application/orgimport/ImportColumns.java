package vn.viettel.okrkpi.org.application.orgimport;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Tiêu đề cột của mẫu nhập cây tổ chức ({@code data/seed/org/mau-nhap-cay-to-chuc.xlsx}) và quy đổi nhãn nghiệp vụ sang
 * giá trị lưu trữ. Tiêu đề so khớp không phân biệt hoa thường, khoảng trắng thừa và dấu tiếng Việt.
 */
public final class ImportColumns {

  public static final String CODE = "Mã đơn vị";
  public static final String NAME = "Tên đơn vị";
  public static final String SHORT_NAME = "Tên viết tắt";
  public static final String UNIT_TYPE = "Loại đơn vị";
  public static final String PARENT = "Mã đơn vị cấp trên";
  public static final String GROUP = "Khối đối tượng";
  public static final String FLOWS = "Luồng đánh giá";
  public static final String LEVEL = "Cấp";
  public static final String VALID_FROM = "Ngày hiệu lực";

  /** Thứ tự cột của mẫu. */
  public static final List<String> ALL = List.of(CODE, NAME, SHORT_NAME, UNIT_TYPE, PARENT, GROUP, FLOWS, LEVEL,
      VALID_FROM);

  public static final List<String> REQUIRED = List.of(CODE, NAME, UNIT_TYPE, GROUP, LEVEL);

  /** Nhãn loại đơn vị trong mẫu, theo bộ giá trị {@code ck_org_unit__unit_type}. */
  static final Map<String, String> UNIT_TYPES = new LinkedHashMap<>();
  static final Map<String, String> FLOW_VALUES = new LinkedHashMap<>();

  static {
    UNIT_TYPES.put("Tổ chức đảng", "TO_CHUC_DANG");
    UNIT_TYPES.put("Chi bộ", "CHI_BO");
    UNIT_TYPES.put("Cơ quan", "CO_QUAN");
    UNIT_TYPES.put("Đơn vị sự nghiệp", "DON_VI_SU_NGHIEP");
    UNIT_TYPES.put("Phòng, ban", "PHONG_BAN");
    UNIT_TYPES.put("Xã, phường", "XA_PHUONG");
    UNIT_TYPES.put("Doanh nghiệp", "DOANH_NGHIEP");
    FLOW_VALUES.put("Đảng", "DANG");
    FLOW_VALUES.put("Nhà nước", "NHA_NUOC");
  }

  private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);
  private static final DateTimeFormatter VN_DATE = DateTimeFormatter.ofPattern("d/M/uuuu")
      .withResolverStyle(java.time.format.ResolverStyle.STRICT);

  private ImportColumns() {
  }

  /** Khoá so khớp: bỏ dấu, chữ thường, gộp khoảng trắng. */
  static String key(String text) {
    if (text == null) {
      return "";
    }
    String n = Normalizer.normalize(text.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}", "")
        .replace('đ', 'd').replace('Đ', 'D');
    return n.toLowerCase(Locale.ROOT).replaceAll("[\\s,]+", " ").trim();
  }

  /** Vị trí từng cột chuẩn trong dòng tiêu đề; cột lạ bị bỏ qua. */
  static Map<String, Integer> headerIndex(List<String> header) {
    Map<String, Integer> index = new LinkedHashMap<>();
    for (int i = 0; i < header.size(); i++) {
      String k = key(header.get(i));
      for (String col : ALL) {
        if (key(col).equals(k) && !index.containsKey(col)) {
          index.put(col, i);
        }
      }
    }
    return index;
  }

  static Optional<String> unitType(String value) {
    return lookup(UNIT_TYPES, value);
  }

  static Optional<String> flow(String value) {
    return lookup(FLOW_VALUES, value);
  }

  private static Optional<String> lookup(Map<String, String> labels, String value) {
    String k = key(value);
    for (Map.Entry<String, String> e : labels.entrySet()) {
      if (key(e.getKey()).equals(k) || key(e.getValue().replace('_', ' ')).equals(k)) {
        return Optional.of(e.getValue());
      }
    }
    return Optional.empty();
  }

  /** Ngày dạng {@code yyyy-MM-dd}, {@code d/M/yyyy} hoặc số ngày kiểu Excel. */
  static Optional<LocalDate> date(String value) {
    String v = value.trim();
    try {
      if (v.matches("\\d{1,6}(\\.0+)?")) {
        return Optional.of(EXCEL_EPOCH.plusDays(Long.parseLong(v.replaceAll("\\..*", ""))));
      }
      if (v.contains("/")) {
        return Optional.of(LocalDate.parse(v, VN_DATE));
      }
      return Optional.of(LocalDate.parse(v));
    } catch (DateTimeParseException | NumberFormatException e) {
      return Optional.empty();
    }
  }
}
