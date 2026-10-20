/*
 * NEN-007 · Sinh lớp hằng vn.viettel.okrkpi.events.EventTopics từ contracts/events/catalog.yaml.
 *
 * Chạy như chương trình Java một tệp (JEP 330) ở pha generate-sources qua exec-maven-plugin:
 *   java -classpath <snakeyaml> GenerateEventTopics.java <catalog.yaml> <thư mục mã sinh>
 * Không biên dịch vào jar; lớp sinh ra nằm cạnh mã do avro-maven-plugin sinh.
 */

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public final class GenerateEventTopics {

  private GenerateEventTopics() {}

  public static void main(String[] args) throws IOException {
    if (args.length != 2) {
      throw new IllegalArgumentException("Cần 2 tham số: <catalog.yaml> <thư mục mã sinh>");
    }
    Path catalog = Path.of(args[0]);
    Path outDir = Path.of(args[1]);
    Map<String, Object> root;
    try (InputStream in = Files.newInputStream(catalog)) {
      root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
    }
    List<Map<String, Object>> events = list(root, "events");
    List<Map<String, Object>> technical = list(root, "technicalTopics");

    StringBuilder sb = new StringBuilder();
    sb.append("package vn.viettel.okrkpi.events;\n\n");
    sb.append("import java.util.List;\nimport java.util.Optional;\n\n");
    sb.append("/**\n");
    sb.append(" * Tên topic Kafka của hợp đồng sự kiện (baseline mục 7, TL-03 \"Lược đồ sự kiện\").\n");
    sb.append(" *\n");
    sb.append(" * <p>MÃ SINH TỰ ĐỘNG từ contracts/events/catalog.yaml, không sửa tay (NEN-007).\n");
    sb.append(" */\n");
    sb.append("public final class EventTopics {\n\n");
    sb.append("  private EventTopics() {}\n\n");

    StringBuilder business = new StringBuilder();
    StringBuilder byCode = new StringBuilder();
    for (Map<String, Object> e : events) {
      String code = str(e, "code");
      String name = str(e, "name");
      String topic = str(e, "topic");
      String constant = code.replace('-', '_') + "_" + upperSnake(name);
      sb.append("  /** ")
          .append(code)
          .append(" ")
          .append(name)
          .append(": phát bởi ")
          .append(str(e, "producer"))
          .append(", khoá ")
          .append(str(e, "key"))
          .append(". */\n");
      sb.append("  public static final String ")
          .append(constant)
          .append(" = \"")
          .append(topic)
          .append("\";\n\n");
      business.append("        ").append(constant).append(",\n");
      byCode
          .append("      case \"")
          .append(code)
          .append("\" -> Optional.of(")
          .append(constant)
          .append(");\n");
    }
    StringBuilder tech = new StringBuilder();
    for (Map<String, Object> t : technical) {
      String topic = str(t, "topic");
      String constant = topic.replace('.', '_').toUpperCase(Locale.ROOT);
      sb.append("  /** Topic kỹ thuật ")
          .append(topic)
          .append(" (")
          .append(str(t, "name"))
          .append("), không thuộc danh mục EVT. */\n");
      sb.append("  public static final String ")
          .append(constant)
          .append(" = \"")
          .append(topic)
          .append("\";\n\n");
      tech.append("        ").append(constant).append(",\n");
    }
    sb.append("  /** Topic của 26 sự kiện nghiệp vụ, theo thứ tự EVT-01..EVT-26. */\n");
    sb.append("  public static List<String> businessTopics() {\n");
    sb.append("    return List.of(\n").append(trimComma(business)).append(");\n  }\n\n");
    sb.append("  /** Topic kỹ thuật (tiền tố sys., iam., registry., org.bootstrap, period.). */\n");
    sb.append("  public static List<String> technicalTopics() {\n");
    sb.append("    return List.of(\n").append(trimComma(tech)).append(");\n  }\n\n");
    sb.append("  /** Tra topic theo mã EVT, ví dụ {@code \"EVT-05\"}. */\n");
    sb.append("  public static Optional<String> topicOf(String code) {\n");
    sb.append("    return switch (code) {\n").append(byCode);
    sb.append("      default -> Optional.empty();\n    };\n  }\n}\n");

    Path target = outDir.resolve("vn/viettel/okrkpi/events/EventTopics.java");
    Files.createDirectories(target.getParent());
    Files.writeString(target, sb.toString(), StandardCharsets.UTF_8);
    System.out.println(
        "Đã sinh "
            + target
            + " ("
            + events.size()
            + " topic nghiệp vụ, "
            + technical.size()
            + " topic kỹ thuật)");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> list(Map<String, Object> m, String key) {
    Object v = m.get(key);
    if (!(v instanceof List<?>)) {
      throw new IllegalStateException("catalog.yaml thiếu danh sách '" + key + "'");
    }
    return (List<Map<String, Object>>) v;
  }

  private static String str(Map<String, Object> m, String key) {
    Object v = m.get(key);
    if (v == null) {
      throw new IllegalStateException("catalog.yaml thiếu '" + key + "' ở " + m);
    }
    return v.toString();
  }

  /** TienDoDaCapNhat -> TIEN_DO_DA_CAP_NHAT. */
  static String upperSnake(String pascal) {
    return pascal.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
  }

  private static String trimComma(StringBuilder sb) {
    int i = sb.lastIndexOf(",");
    return i < 0 ? sb.toString() : sb.substring(0, i) + "\n        ";
  }
}
