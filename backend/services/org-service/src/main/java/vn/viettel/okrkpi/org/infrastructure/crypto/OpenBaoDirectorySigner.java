package vn.viettel.okrkpi.org.infrastructure.crypto;

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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import vn.viettel.okrkpi.org.application.port.DirectorySigner;
import vn.viettel.okrkpi.org.application.port.IntegrationUnavailableException;

/**
 * Ký danh bạ khoá bằng OpenBao Transit (ADR-27, TL-04 SEC-25): khoá {@code ecdsa-p256} riêng cho từng tenant tên
 * {@code <keyPrefix><tenantId>}, ký trên băm SHA-256 với chữ ký DER ({@code marshaling_algorithm=asn1}). Khoá riêng
 * không rời OpenBao. Token đọc từ tệp do OpenBao Agent hoặc External Secrets cấp (không đặt trong cấu hình) và đọc lại
 * mỗi lần gọi để theo kịp xoay vòng. Khoá ký do vận hành tạo khi cấp tenant; {@code autoCreate} chỉ bật ở môi trường dev.
 */
public class OpenBaoDirectorySigner implements DirectorySigner {

  private static final String UNAVAILABLE = "Chưa ký được bản ghi danh bạ khoá, vui lòng thử lại sau.";

  private final HttpClient http;
  private final ObjectMapper json;
  private final URI base;
  private final String mount;
  private final String keyPrefix;
  private final Path tokenFile;
  private final boolean autoCreate;

  public OpenBaoDirectorySigner(HttpClient http, ObjectMapper json, URI base, String mount, String keyPrefix,
      Path tokenFile, boolean autoCreate) {
    this.http = http;
    this.json = json;
    this.base = base;
    this.mount = mount;
    this.keyPrefix = keyPrefix;
    this.tokenFile = tokenFile;
    this.autoCreate = autoCreate;
  }

  @Override
  public Signed sign(UUID tenantId, byte[] payload) {
    Map<String, Object> body = Map.of("input", Base64.getEncoder().encodeToString(payload),
        "marshaling_algorithm", "asn1");
    HttpResponse<String> res = call("POST", "/v1/" + mount + "/sign/" + keyName(tenantId) + "/sha2-256", body);
    if (res.statusCode() >= 400 && autoCreate && missingKey(res)) {
      create(tenantId);
      res = call("POST", "/v1/" + mount + "/sign/" + keyName(tenantId) + "/sha2-256", body);
    }
    String signature = data(res).path("signature").asText("");
    String[] parts = signature.split(":");
    if (parts.length != 3 || !"vault".equals(parts[0]) || !parts[1].startsWith("v")) {
      throw new IntegrationUnavailableException(UNAVAILABLE, null);
    }
    try {
      return new Signed(Integer.parseInt(parts[1].substring(1)), Base64.getDecoder().decode(parts[2]));
    } catch (IllegalArgumentException e) {
      throw new IntegrationUnavailableException(UNAVAILABLE, e);
    }
  }

  @Override
  public List<VerificationKey> verificationKeys(UUID tenantId) {
    JsonNode keys = data(call("GET", "/v1/" + mount + "/keys/" + keyName(tenantId), null)).path("keys");
    List<VerificationKey> out = new ArrayList<>();
    for (Iterator<Map.Entry<String, JsonNode>> it = keys.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> e = it.next();
      String pem = e.getValue().path("public_key").asText("");
      if (!pem.isEmpty()) {
        out.add(new VerificationKey(Integer.parseInt(e.getKey()), pem));
      }
    }
    out.sort(Comparator.comparingInt(VerificationKey::keyVersion));
    return out;
  }

  String keyName(UUID tenantId) {
    return keyPrefix + tenantId;
  }

  private void create(UUID tenantId) {
    HttpResponse<String> res = call("POST", "/v1/" + mount + "/keys/" + keyName(tenantId),
        Map.of("type", "ecdsa-p256", "exportable", false));
    if (res.statusCode() >= 300) {
      throw new IntegrationUnavailableException(UNAVAILABLE, null);
    }
  }

  private static boolean missingKey(HttpResponse<String> res) {
    return res.body() != null && res.body().contains("not found");
  }

  private JsonNode data(HttpResponse<String> res) {
    if (res.statusCode() != 200) {
      throw new IntegrationUnavailableException(UNAVAILABLE, null);
    }
    try {
      return json.readTree(res.body()).path("data");
    } catch (IOException e) {
      throw new IntegrationUnavailableException(UNAVAILABLE, e);
    }
  }

  private HttpResponse<String> call(String method, String path, Object body) {
    try {
      String token = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
      HttpRequest.Builder req = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(5))
          .header("X-Vault-Token", token).header("Content-Type", "application/json");
      req.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
          : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
      return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new IntegrationUnavailableException(UNAVAILABLE, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IntegrationUnavailableException(UNAVAILABLE, e);
    }
  }
}
