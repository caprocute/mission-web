package vn.viettel.okrkpi.integ.infrastructure.celltransfer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import vn.viettel.okrkpi.integ.application.SourceUnavailableException;
import vn.viettel.okrkpi.integ.application.port.TransferCodec.Part;
import vn.viettel.okrkpi.integ.application.port.TransferContents;
import vn.viettel.okrkpi.integ.application.port.TransferImporter;
import vn.viettel.okrkpi.integ.infrastructure.hrm.ServiceToken;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng nội bộ thu thập và nhập phần hồ sơ (MOD-04, MOD-06, SVC-04) theo cấu hình {@code
 * okrkpi.integ.cell-transfer.parts}: mỗi loại phần có địa chỉ đọc ở cell nguồn ({@code {personId}}
 * được thay) và địa chỉ nhập ở cell đích. Gọi bằng token dịch vụ của gateway, tenant ở {@code
 * X-Tenant-Id}. Đọc trả 404 thì cán bộ không có phần đó. Nhập gửi {@code Idempotency-Key} theo gói
 * và tên phần; 4xx (trừ 408, 429) là bị từ chối, còn lại là lỗi tạm thời.
 */
public class HttpTransferParts implements TransferContents, TransferImporter {

  /** Một loại phần: tên tệp trong gói, kiểu nội dung, địa chỉ đọc và địa chỉ nhập. */
  public record Kind(String name, String mediaType, String collectUrl, String importUrl) {}

  private final HttpClient http;
  private final ServiceToken token;
  private final Map<String, Kind> kinds;
  private final Duration timeout;

  public HttpTransferParts(
      HttpClient http, ServiceToken token, Map<String, Kind> kinds, Duration timeout) {
    this.http = http;
    this.token = token;
    this.kinds = Map.copyOf(kinds);
    this.timeout = timeout;
  }

  @Override
  public List<Part> collect(TenantId tenant, UUID personId) {
    List<Part> parts = new ArrayList<>();
    for (Map.Entry<String, Kind> e : new java.util.TreeMap<>(kinds).entrySet()) {
      Kind k = e.getValue();
      if (k.collectUrl() == null || k.collectUrl().isBlank()) {
        continue;
      }
      HttpRequest.Builder req =
          request(tenant, k.collectUrl().replace("{personId}", personId.toString())).GET();
      HttpResponse<byte[]> res = send(req);
      if (res.statusCode() == 404) {
        continue;
      }
      if (res.statusCode() / 100 != 2) {
        throw unavailable("Nguồn " + e.getKey() + " trả mã " + res.statusCode(), null);
      }
      parts.add(
          new Part(
              k.name(),
              e.getKey(),
              res.headers().firstValue("Content-Type").orElse(k.mediaType()),
              res.body()));
    }
    return parts;
  }

  @Override
  public void importPart(
      TenantId tenant, UUID packageId, UUID personId, String sourceTenantCode, Part part) {
    Kind k = kinds.get(part.kind());
    if (k == null || k.importUrl() == null || k.importUrl().isBlank()) {
      throw new IllegalArgumentException("Chưa có cổng nhập cho loại phần " + part.kind());
    }
    HttpRequest.Builder req =
        request(tenant, k.importUrl().replace("{personId}", personId.toString()))
            .header("Content-Type", part.mediaType())
            .header("Idempotency-Key", packageId + ":" + part.name())
            .header("X-Transfer-Package", packageId.toString())
            .header("X-Source-Tenant", sourceTenantCode)
            .POST(HttpRequest.BodyPublishers.ofByteArray(part.content()));
    int status = send(req).statusCode();
    if (status / 100 == 2) {
      return;
    }
    if (status / 100 == 4 && status != 408 && status != 429) {
      throw new IllegalArgumentException("Cổng nhập " + part.kind() + " từ chối, mã " + status);
    }
    throw unavailable("Cổng nhập " + part.kind() + " trả mã " + status, null);
  }

  private HttpRequest.Builder request(TenantId tenant, String url) {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout)
            .header("X-Tenant-Id", tenant.value().toString());
    if (token.enabled()) {
      try {
        b.header("Authorization", "Bearer " + token.bearer());
      } catch (IOException e) {
        throw unavailable("Không lấy được token dịch vụ", e);
      }
    }
    return b;
  }

  private HttpResponse<byte[]> send(HttpRequest.Builder req) {
    try {
      return http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException e) {
      throw unavailable("Không gọi được cổng nội bộ", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw unavailable("Bị ngắt khi gọi cổng nội bộ", e);
    }
  }

  private static SourceUnavailableException unavailable(String message, Throwable cause) {
    return new SourceUnavailableException(SourceUnavailableException.Reason.ERROR, message, cause);
  }
}
