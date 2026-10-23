package vn.viettel.okrkpi.docgen.application.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.RejectedDocument;

/**
 * Tệp kê khai trường của một mẫu biểu (DL-05, {@code data/seed/docgen/README.md}): mỗi trường là
 * một content control trong DOCX có {@code tag} trùng tên trường, kèm kiểu, mức dữ liệu và cờ khoá
 * số liệu (BR-11-02). Tệp lưu cạnh tệp DOCX trên bucket {@code templates} và mang thêm khoá {@code
 * nhan}: bảng nhãn in ra biểu mẫu ({@code nhan-dung-chung.json}) được gộp vào khi đăng ký mẫu, để
 * nhãn đi theo phiên bản mẫu chứ không viết cứng trong mã (BR-11-04, suy luận về cách lưu).
 *
 * @param formCode mã biểu mẫu ({@code MAU_1B})
 * @param templateVersion phiên bản mẫu ghi trong kê khai ({@code 0.9.0-gia-lap})
 * @param criteriaLabelSet bảng nhãn tiêu chí chung mẫu dùng ({@code MAU_1}, {@code MAU_2}); có thể
 *     rỗng
 * @param fields các trường theo thứ tự kê khai
 * @param labels bảng nhãn: tên bảng ({@code muc_xep_loai}…) → giá trị lưu → chữ in
 */
public record TemplateManifest(
    String formCode,
    String templateVersion,
    String criteriaLabelSet,
    List<Field> fields,
    Map<String, Map<String, String>> labels) {

  /** Kiểu trường dùng trong kê khai DL-05. */
  public enum Kind {
    VAN_BAN,
    VAN_BAN_DAI,
    SO,
    NGAY,
    NHAN_TIEU_CHI,
    NHAN_MUC,
    NHAN_TRANG_THAI,
    NHAN_NHOM,
    CO,
    BANG;

    static Kind of(String code) {
      try {
        return Kind.valueOf(code.toUpperCase(java.util.Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw new RejectedDocument("Tệp kê khai mẫu có kiểu trường không hỗ trợ: " + code);
      }
    }
  }

  /**
   * Một trường hoặc một cột của trường bảng.
   *
   * @param locked vùng số liệu khoá chỉnh sửa trong DOCX (BR-11-02)
   * @param level mức dữ liệu {@code C1}, {@code C2}
   * @param decimals số chữ số thập phân khai báo riêng ({@code so_le}); {@code null} theo quy ước
   * @param columns các cột khi {@link Kind#BANG}
   */
  public record Field(
      String tag, Kind kind, boolean locked, String level, Integer decimals, List<Field> columns) {
    public Field {
      Objects.requireNonNull(tag, "tag");
      Objects.requireNonNull(kind, "kind");
      level = level == null ? "C1" : level;
      columns = columns == null ? List.of() : List.copyOf(columns);
    }

    public boolean sealed() {
      return "C2".equals(level);
    }

    public Optional<Field> column(String columnTag) {
      return columns.stream().filter(c -> c.tag().equals(columnTag)).findFirst();
    }
  }

  public TemplateManifest {
    Objects.requireNonNull(formCode, "formCode");
    fields = List.copyOf(fields);
    labels = Map.copyOf(labels);
  }

  public Optional<Field> field(String tag) {
    return fields.stream().filter(f -> f.tag().equals(tag)).findFirst();
  }

  /** Bảng nhãn theo tên; rỗng khi kê khai không mang bảng đó. */
  public Map<String, String> labelTable(String name) {
    return labels.getOrDefault(name, Map.of());
  }

  /** Đọc tệp kê khai; tệp hỏng hay thiếu khoá bắt buộc làm tác vụ chuyển lỗi, không thử lại. */
  public static TemplateManifest parse(byte[] json, ObjectMapper mapper) {
    JsonNode root;
    try {
      root = mapper.readTree(json);
    } catch (IOException e) {
      throw new RejectedDocument("Tệp kê khai mẫu không phải JSON hợp lệ.");
    }
    if (root == null || !root.hasNonNull("form_code") || !root.path("truong").isArray()) {
      throw new RejectedDocument("Tệp kê khai mẫu thiếu mã biểu mẫu hoặc danh sách trường.");
    }
    List<Field> fields = new ArrayList<>();
    root.path("truong").forEach(n -> fields.add(field(n)));
    Map<String, Map<String, String>> labels = new LinkedHashMap<>();
    root.path("nhan")
        .properties()
        .forEach(
            table -> {
              if (table.getValue().isObject()) {
                labels.put(table.getKey(), flatTable(table.getValue()));
              }
            });
    JsonNode criteria = root.path("nhan").path("nhan_tieu_chi_chung");
    String set = root.path("nhan_tieu_chi_chung").asText(null);
    if (set != null && criteria.path(set).isObject()) {
      labels.put("nhan_tieu_chi_chung", flatTable(criteria.path(set)));
    }
    return new TemplateManifest(
        root.path("form_code").asText(),
        root.path("template_version").asText(null),
        set,
        fields,
        labels);
  }

  private static Field field(JsonNode n) {
    if (!n.hasNonNull("tag") || !n.hasNonNull("kieu")) {
      throw new RejectedDocument("Tệp kê khai mẫu có trường thiếu tag hoặc kiểu.");
    }
    List<Field> columns = new ArrayList<>();
    n.path("cot").forEach(c -> columns.add(field(c)));
    return new Field(
        n.path("tag").asText(),
        Kind.of(n.path("kieu").asText()),
        n.path("khoa_so_lieu").asBoolean(false),
        n.path("muc_du_lieu").asText("C1"),
        n.hasNonNull("so_le") ? n.path("so_le").asInt() : null,
        columns);
  }

  private static Map<String, String> flatTable(JsonNode table) {
    Map<String, String> out = new LinkedHashMap<>();
    table
        .properties()
        .forEach(
            e -> {
              if (e.getValue().isValueNode()) {
                out.put(e.getKey(), e.getValue().asText());
              }
            });
    return out;
  }
}
