package vn.viettel.okrkpi.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.avro.Schema;
import org.apache.avro.util.ClassSecurityValidator;
import org.apache.avro.util.ClassSecurityValidator.ClassSecurityPredicate;

/**
 * NEN-007 · Điểm vào dùng chung của thư viện hợp đồng sự kiện.
 *
 * <p>Từ Avro 1.12.1, {@code SpecificData} chỉ nạp lớp sinh thuộc danh sách tin cậy
 * (ClassSecurityValidator, vá CVE-2024-47561); lớp ngoài danh sách bị từ chối bằng {@link
 * SecurityException} khi đọc. Service tuần tự hoá sự kiện (outbox NEN-011, consumer NEN-012) gọi
 * {@link #trustContractPackages()} một lần khi khởi động, thay cho việc đặt thuộc tính hệ thống
 * {@code org.apache.avro.SERIALIZABLE_PACKAGES}.
 */
public final class EventContracts {

  /** Gói gốc của mọi lớp sinh từ contracts/events/avro (namespace {@code vn.vtokr.<miền>.v1}). */
  public static final String CONTRACT_PACKAGE_PREFIX = "vn.vtokr.";

  /** Tin cậy đúng các lớp sinh từ hợp đồng sự kiện, không mở rộng sang gói khác. */
  static final ClassSecurityPredicate CONTRACT_CLASSES =
      c -> c.getName().startsWith(CONTRACT_PACKAGE_PREFIX);

  /**
   * Thuộc tính mà avro-maven-plugin ({@code stringType=String}) gắn vào mọi kiểu string của lớp
   * sinh. Tệp {@code .avsc} đăng ký vào Apicurio (Job apicurio-dang-ky, INF-10) không có thuộc tính
   * này, và dạng chuẩn hoá của Apicurio vẫn giữ nó, nên tra lược đồ theo nội dung bằng lược đồ của
   * lớp sinh không khớp.
   */
  static final String JAVA_STRING_PROP = "avro.java.string";

  private static final ObjectMapper JSON = new ObjectMapper();

  private static boolean installed;

  private EventContracts() {}

  /**
   * Thêm lớp hợp đồng sự kiện vào danh sách tin cậy toàn cục của Avro, giữ nguyên danh sách hiện
   * có. Gọi nhiều lần không có tác dụng phụ.
   */
  public static synchronized void trustContractPackages() {
    if (installed) {
      return;
    }
    ClassSecurityValidator.setGlobal(
        ClassSecurityValidator.composite(ClassSecurityValidator.getGlobal(), CONTRACT_CLASSES));
    installed = true;
  }

  /**
   * Lược đồ của lớp sinh ở dạng như tệp hợp đồng đã đăng ký: bỏ {@value #JAVA_STRING_PROP}, giữ
   * nguyên mọi phần khác. Mã hoá nhị phân không đổi vì thuộc tính này chỉ chọn kiểu Java khi đọc;
   * dùng để tra global id trong registry (outbox NEN-011, org-republish DV-04-08).
   */
  public static Schema registrySchema(Schema generated) {
    try {
      JsonNode tree = stripJavaProps(JSON.readTree(generated.toString()));
      return new Schema.Parser().setValidateDefaults(false).parse(JSON.writeValueAsString(tree));
    } catch (IOException e) {
      throw new IllegalStateException("Không chuẩn hoá được lược đồ " + generated.getFullName(), e);
    }
  }

  private static final Set<String> PRIMITIVES =
      Set.of("null", "boolean", "int", "long", "float", "double", "bytes", "string");

  private static JsonNode stripJavaProps(JsonNode node) {
    if (node instanceof ObjectNode obj) {
      obj.remove(JAVA_STRING_PROP);
      List<String> names = new ArrayList<>();
      obj.fieldNames().forEachRemaining(names::add);
      for (String name : names) {
        obj.set(name, stripJavaProps(obj.get(name)));
      }
      // {"type":"string"} còn lại sau khi bỏ thuộc tính trở về dạng nguyên thuỷ như tệp .avsc
      if (obj.size() == 1
          && obj.path("type").isTextual()
          && PRIMITIVES.contains(obj.get("type").asText())) {
        return obj.get("type");
      }
    } else if (node instanceof ArrayNode arr) {
      for (int i = 0; i < arr.size(); i++) {
        arr.set(i, stripJavaProps(arr.get(i)));
      }
    }
    return node;
  }
}
