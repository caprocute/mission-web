package vn.viettel.okrkpi.docgen.infrastructure.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Bộ dữ liệu vàng của DV-11-02 ({@code data/golden/docgen/mau-1a-1d}) và DV-11-03 ({@code
 * data/golden/docgen/mau-2-3-bang-diem}) cùng mẫu giả lập của DL-05 ({@code
 * data/policy-pack/khoi-dang/quy-dinh-moi/templates}).
 *
 * <p>Tệp kê khai đăng ký vào kho là kê khai DL-05 gộp thêm bảng nhãn {@code
 * data/seed/docgen/nhan-dung-chung.json} dưới khoá {@code nhan}, đúng như bước đăng ký mẫu làm. Dữ
 * liệu đầu vào được niêm phong trường C2 (theo kê khai) bằng phong bì giả lập của {@link
 * DocgenTestBeans} với AAD {@code [formInstanceId, tag]}.
 */
final class GoldenForms {

  static final List<String> CODES =
      List.of("MAU_1A", "MAU_1B", "MAU_1C", "MAU_1D", "MAU_2", "MAU_3", "BANG_CHAM_DIEM");

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Path REPO = repo();

  private GoldenForms() {}

  private static Path repo() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null && !Files.isDirectory(dir.resolve("data/golden/docgen"))) {
      dir = dir.getParent();
    }
    if (dir == null) {
      throw new IllegalStateException("Không tìm thấy thư mục data/golden/docgen");
    }
    return dir;
  }

  /** Thư mục mẫu DL-05: {@code MAU_1A} → {@code mau-1a}. */
  static String templateDir(String code) {
    return code.toLowerCase(Locale.ROOT).replace("mau_", "mau-").replace('_', '-');
  }

  static Path goldenDir(String code) {
    String group = code.startsWith("MAU_1") ? "mau-1a-1d" : "mau-2-3-bang-diem";
    return REPO.resolve("data/golden/docgen").resolve(group).resolve(templateDir(code));
  }

  static byte[] docx(String code) {
    String dir = templateDir(code);
    return read(templates().resolve(dir).resolve(dir + ".docx"));
  }

  static Path templates() {
    return REPO.resolve("data/policy-pack/khoi-dang/quy-dinh-moi/templates");
  }

  /** Kê khai DL-05 gộp bảng nhãn dùng chung. */
  static byte[] manifest(String code) {
    try {
      ObjectNode manifest =
          (ObjectNode)
              JSON.readTree(read(templates().resolve(templateDir(code)).resolve("manifest.json")));
      manifest.set(
          "nhan", JSON.readTree(read(REPO.resolve("data/seed/docgen/nhan-dung-chung.json"))));
      return JSON.writeValueAsBytes(manifest);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Dữ liệu vàng thô (chưa niêm phong). */
  static ObjectNode input(String code) {
    try {
      return (ObjectNode) JSON.readTree(read(goldenDir(code).resolve("input.json")));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Dữ liệu vàng đã niêm phong trường C2, dạng byte JSON như MOD-06 gửi. */
  static byte[] sealedInput(String code, UUID formInstanceId) {
    return sealed(code, input(code), formInstanceId);
  }

  static byte[] sealed(String code, ObjectNode data, UUID formInstanceId) {
    try {
      JsonNode manifest = JSON.readTree(manifest(code));
      for (JsonNode field : manifest.path("truong")) {
        String tag = field.path("tag").asText();
        if ("C2".equals(field.path("muc_du_lieu").asText()) && data.path(tag).isTextual()) {
          data.set(tag, envelope(formInstanceId, tag, data.path(tag).asText()));
        }
        if (field.path("cot").isArray() && data.path(tag).isArray()) {
          for (JsonNode column : field.path("cot")) {
            String col = column.path("tag").asText();
            if ("C2".equals(column.path("muc_du_lieu").asText())) {
              for (JsonNode row : (ArrayNode) data.path(tag)) {
                if (row.path(col).isTextual()) {
                  ((ObjectNode) row)
                      .set(col, envelope(formInstanceId, col, row.path(col).asText()));
                }
              }
            }
          }
        }
      }
      return JSON.writeValueAsBytes(data);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Các giá trị C2 rõ dạng văn bản của dữ liệu vàng, để kiểm không lọt vào nhật ký. */
  static List<String> plainSealedValues(String code) {
    List<String> out = new ArrayList<>();
    try {
      JsonNode manifest = JSON.readTree(manifest(code));
      ObjectNode data = input(code);
      for (JsonNode field : manifest.path("truong")) {
        String tag = field.path("tag").asText();
        if (text(field) && data.path(tag).isTextual()) {
          out.add(data.path(tag).asText());
        }
        for (JsonNode column : field.path("cot")) {
          if (text(column)) {
            data.path(tag)
                .forEach(
                    r -> {
                      if (r.path(column.path("tag").asText()).isTextual()) {
                        out.add(r.path(column.path("tag").asText()).asText());
                      }
                    });
          }
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out;
  }

  private static boolean text(JsonNode field) {
    return "C2".equals(field.path("muc_du_lieu").asText())
        && field.path("kieu").asText().startsWith("van_ban");
  }

  private static ObjectNode envelope(UUID formInstanceId, String tag, String value) {
    ObjectNode node = JSON.createObjectNode();
    node.put("c2", DocgenTestBeans.seal(List.of(formInstanceId.toString(), tag), value));
    return node;
  }

  /** Các dòng phải xuất hiện theo thứ tự trong văn bản trích từ PDF. */
  static List<String> expected(String code) {
    return new String(read(goldenDir(code).resolve("expected.txt")), StandardCharsets.UTF_8)
        .lines()
        .map(String::strip)
        .filter(l -> !l.isEmpty() && !l.startsWith("#"))
        .toList();
  }

  static Map<String, Object> asMap(byte[] json) {
    try {
      return JSON.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static byte[] read(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
