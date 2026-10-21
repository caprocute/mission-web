package vn.viettel.okrkpi.shared.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Băm yêu cầu cho cột {@code request_hash}: SHA-256 của phương thức, đường dẫn (kèm chuỗi truy vấn)
 * và thân chuẩn hoá (TL-03 "Bảng idempotency_key").
 *
 * <p>Thân chuẩn hoá là JSON gọn, khoá đối tượng sắp theo thứ tự từ điển ở mọi mức, nên cùng nội
 * dung gửi với thứ tự trường hay khoảng trắng khác vẫn cùng băm. Thân không phải JSON băm nguyên
 * byte.
 */
public final class RequestHasher {

  private static final ObjectMapper CANONICAL = new ObjectMapper();

  private final ObjectMapper mapper;

  /**
   * @param mapper ObjectMapper của ứng dụng, dùng để chuyển đối tượng thân thành JSON đúng như khi
   *     đọc (tên trường, định dạng ngày)
   */
  public RequestHasher(ObjectMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  /** Băm từ đối tượng thân đã đọc ({@code @RequestBody}), {@code null} khi không có thân. */
  public byte[] hash(String method, String pathAndQuery, Object body) {
    return hash(method, pathAndQuery, canonicalBody(body));
  }

  /** Băm từ thân đã chuẩn hoá. */
  public static byte[] hash(String method, String pathAndQuery, byte[] canonicalBody) {
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(pathAndQuery, "pathAndQuery");
    MessageDigest sha = sha256();
    sha.update(method.toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    sha.update((byte) '\n');
    sha.update(pathAndQuery.getBytes(StandardCharsets.UTF_8));
    sha.update((byte) '\n');
    if (canonicalBody != null) {
      sha.update(canonicalBody);
    }
    return sha.digest();
  }

  /**
   * Thân chuẩn hoá của một đối tượng ({@code byte[]}/{@code String} không phải JSON giữ nguyên).
   */
  public byte[] canonicalBody(Object body) {
    if (body == null) {
      return new byte[0];
    }
    try {
      JsonNode tree;
      if (body instanceof byte[] raw) {
        tree = tryParse(raw);
        if (tree == null) {
          return raw.clone();
        }
      } else if (body instanceof String s) {
        tree = tryParse(s.getBytes(StandardCharsets.UTF_8));
        if (tree == null) {
          return s.getBytes(StandardCharsets.UTF_8);
        }
      } else {
        tree = mapper.valueToTree(body);
      }
      return CANONICAL.writeValueAsBytes(sorted(tree));
    } catch (JsonProcessingException | IllegalArgumentException e) {
      throw new IllegalStateException("Không chuẩn hoá được thân yêu cầu", e);
    }
  }

  private JsonNode tryParse(byte[] raw) {
    try {
      return mapper.readTree(raw);
    } catch (java.io.IOException e) {
      return null;
    }
  }

  /** Bản sao cây JSON với khoá đối tượng sắp xếp ở mọi mức. */
  static JsonNode sorted(JsonNode node) {
    if (node == null) {
      return JsonNodeFactory.instance.nullNode();
    }
    if (node.isObject()) {
      List<String> names = new ArrayList<>();
      node.fieldNames().forEachRemaining(names::add);
      names.sort(null);
      ObjectNode out = JsonNodeFactory.instance.objectNode();
      for (String name : names) {
        out.set(name, sorted(node.get(name)));
      }
      return out;
    }
    if (node.isArray()) {
      ArrayNode out = JsonNodeFactory.instance.arrayNode();
      node.forEach(child -> out.add(sorted(child)));
      return out;
    }
    return node;
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("JVM thiếu SHA-256", e);
    }
  }
}
