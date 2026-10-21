package vn.viettel.okrkpi.org.infrastructure.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.org.application.IdempotencyRequest;

/** SHA-256 của phương thức, đường dẫn và thân yêu cầu chuẩn hoá (khoá sắp theo thứ tự chữ cái). */
@Component
public class RequestHasher {

  private final ObjectMapper canonical;

  public RequestHasher(ObjectMapper mapper) {
    this.canonical = mapper.copy()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
        .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
  }

  public IdempotencyRequest of(String key, String method, String path, Object body) {
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      sha.update((method + " " + path + "\n").getBytes(StandardCharsets.UTF_8));
      sha.update(canonical.writeValueAsBytes(body));
      return new IdempotencyRequest(key, sha.digest());
    } catch (NoSuchAlgorithmException | JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
