package vn.viettel.okrkpi.integ.infrastructure.celltransfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import vn.viettel.okrkpi.integ.infrastructure.crypto.JcaPackageCrypto;
import vn.viettel.okrkpi.integ.infrastructure.hrm.ServiceToken;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Chữ ký tổ chức của cell qua thao tác ký tổ chức của SVC-10 ({@code POST
 * /internal/v1/esign/org-signatures}, DV-13-10): gửi băm SHA-256 của phần đầu gói hoặc của kết quả
 * nhập, không gửi nội dung. Tenant của gói đi ở {@code X-Tenant-Id} dưới token dịch vụ uỷ quyền.
 * Lỗi kết nối ném {@link IllegalStateException}, worker thử lại theo lịch như lỗi kênh.
 */
public class EsignOrgSigner implements JcaPackageCrypto.Signer {

  static final String PATH = "/internal/v1/esign/org-signatures";
  static final String PURPOSE = "GOI_CHUYEN_LIEN_CELL";

  private final HttpClient http;
  private final ObjectMapper json;
  private final URI endpoint;
  private final ServiceToken token;
  private final Duration timeout;

  public EsignOrgSigner(
      HttpClient http, ObjectMapper json, String baseUrl, ServiceToken token, Duration timeout) {
    this.http = Objects.requireNonNull(http, "http");
    this.json = Objects.requireNonNull(json, "json");
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalStateException("Thiếu địa chỉ esign-service cho chữ ký tổ chức");
    }
    this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + PATH);
    this.token = token;
    this.timeout = timeout;
  }

  @Override
  public byte[] sign(byte[] data) {
    try {
      byte[] sha = MessageDigest.getInstance("SHA-256").digest(data);
      HttpRequest.Builder req =
          HttpRequest.newBuilder(endpoint)
              .timeout(timeout)
              .header("Content-Type", "application/json")
              .POST(
                  HttpRequest.BodyPublishers.ofByteArray(
                      json.writeValueAsBytes(
                          Map.of(
                              "purpose",
                              PURPOSE,
                              "sha256",
                              Base64.getEncoder().encodeToString(sha)))));
      TenantContext.current().ifPresent(t -> req.header("X-Tenant-Id", t.value().toString()));
      if (token != null && token.enabled()) {
        req.header("Authorization", "Bearer " + token.bearer());
      }
      HttpResponse<byte[]> res = http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
      if (res.statusCode() != 200) {
        throw new IllegalStateException("esign-service không ký tổ chức, mã " + res.statusCode());
      }
      JsonNode body = json.readTree(res.body());
      byte[] signature = Base64.getDecoder().decode(body.path("signature").asText(""));
      if (signature.length == 0) {
        throw new IllegalStateException("esign-service không trả chữ ký tổ chức");
      }
      return signature;
    } catch (IOException | IllegalArgumentException e) {
      throw new IllegalStateException("Không gọi được ký tổ chức", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Bị ngắt khi gọi ký tổ chức", e);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
