package vn.viettel.okrkpi.platform.keyrotation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import vn.viettel.okrkpi.shared.crypto.CryptoException;
import vn.viettel.okrkpi.shared.crypto.CryptoProperties;

/**
 * Gọi OpenBao Transit để đọc phiên bản KEK mới nhất và bọc lại DEK ({@code transit/rewrap}) theo lô
 * (TL-01 "Xoay vòng khoá"). Rewrap chỉ đổi bản bọc sang phiên bản KEK mới bên trong OpenBao, nên
 * DEK rõ không bao giờ đi qua ứng dụng. Xác thực bằng token đọc từ tệp do CSI/ESO gắn ({@link
 * CryptoProperties#getTokenFile()}), cùng cấu hình với {@code OpenBaoTransitClient} của NEN-015.
 */
public class TransitRewrapClient {

  private final CryptoProperties properties;
  private final ObjectMapper mapper;
  private final HttpClient http;

  public TransitRewrapClient(CryptoProperties properties, ObjectMapper mapper) {
    this(
        properties,
        mapper,
        HttpClient.newBuilder()
            .connectTimeout(properties.getConnectTimeout())
            .version(HttpClient.Version.HTTP_1_1)
            .build());
  }

  public TransitRewrapClient(CryptoProperties properties, ObjectMapper mapper, HttpClient http) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.mapper = mapper == null ? new ObjectMapper() : mapper;
    this.http = Objects.requireNonNull(http, "http");
  }

  /** Phiên bản mới nhất của KEK ({@code data.latest_version}). */
  public int latestVersion(KekRef kek) {
    JsonNode root = send("GET", "/v1/" + kek.mount() + "/keys/" + kek.keyName(), null);
    int v = root.path("data").path("latest_version").asInt(0);
    if (v <= 0) {
      throw new CryptoException("Phản hồi OpenBao thiếu latest_version của KEK " + kek.keyPath());
    }
    return v;
  }

  /**
   * Bọc lại một lô bản bọc DEK bằng phiên bản KEK mới nhất; trả bản bọc mới cùng thứ tự đầu vào.
   */
  public List<byte[]> rewrap(KekRef kek, List<byte[]> wrapped) {
    if (wrapped.isEmpty()) {
      return List.of();
    }
    List<Map<String, String>> batch = new ArrayList<>(wrapped.size());
    for (byte[] w : wrapped) {
      batch.add(Map.of("ciphertext", new String(w, StandardCharsets.UTF_8)));
    }
    JsonNode root =
        send(
            "POST",
            "/v1/" + kek.mount() + "/rewrap/" + kek.keyName(),
            Map.of("batch_input", batch));
    JsonNode results = root.path("data").path("batch_results");
    if (!results.isArray() || results.size() != wrapped.size()) {
      throw new CryptoException("OpenBao rewrap trả số kết quả không khớp lô gửi đi");
    }
    List<byte[]> out = new ArrayList<>(wrapped.size());
    for (JsonNode r : results) {
      String c = r.path("ciphertext").asText("");
      if (!r.path("error").asText("").isEmpty() || c.isEmpty()) {
        throw new CryptoException("OpenBao rewrap báo lỗi trên một bản bọc của " + kek.keyPath());
      }
      out.add(c.getBytes(StandardCharsets.UTF_8));
    }
    return out;
  }

  private JsonNode send(String method, String path, Object body) {
    try {
      HttpRequest.Builder b =
          HttpRequest.newBuilder(resolve(path))
              .timeout(properties.getReadTimeout())
              .header("X-Vault-Request", "true")
              .header("X-Vault-Token", token());
      if (body == null) {
        b.GET();
      } else {
        b.header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)));
      }
      HttpResponse<byte[]> res = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
      if (res.statusCode() != 200) {
        throw new CryptoException("OpenBao Transit trả HTTP " + res.statusCode() + " cho " + path);
      }
      return mapper.readTree(res.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CryptoException("Luồng bị ngắt khi gọi OpenBao Transit", e);
    } catch (IOException e) {
      throw new CryptoException("Lỗi kết nối khi gọi OpenBao Transit", e);
    }
  }

  private String token() throws IOException {
    Path f = properties.getTokenFile();
    if (f == null) {
      throw new CryptoException("Job xoay vòng cần okrkpi.crypto.token-file để gọi OpenBao");
    }
    String t = Files.readString(f, StandardCharsets.UTF_8).trim();
    if (t.isEmpty()) {
      throw new CryptoException("Tệp token OpenBao rỗng");
    }
    return t;
  }

  private URI resolve(String path) {
    String base = properties.getOpenBaoBaseUrl().toString();
    return URI.create(
        base.endsWith("/") ? base.substring(0, base.length() - 1) + path : base + path);
  }
}
