package vn.viettel.okrkpi.integ.infrastructure.celltransfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import vn.viettel.okrkpi.integ.application.port.CellChannel;
import vn.viettel.okrkpi.integ.application.port.CellKeyDirectory.CellKeys;

/**
 * Kênh liên cell qua HTTP tới gateway của cell đối tác, địa chỉ lấy từ danh bạ khoá đã ký (không
 * nhận địa chỉ từ bên gọi). mTLS do lưới dịch vụ đặt ở cổng ra liên cell (suy luận, wiki trang 14);
 * lớp ứng dụng xác thực bằng chữ ký của cell trên phần đầu gói và trên kết quả nhập.
 */
public class HttpCellChannel implements CellChannel {

  static final String INBOUND = "/internal/v1/integ/cell-transfers/inbound";
  static final String OUTCOMES = "/internal/v1/integ/cell-transfers/outcomes";

  private final HttpClient http;
  private final ObjectMapper json;
  private final Duration timeout;

  public HttpCellChannel(HttpClient http, ObjectMapper json, Duration timeout) {
    this.http = http;
    this.json = json;
    this.timeout = timeout;
  }

  @Override
  public Optional<String> deliver(CellKeys peer, Envelope e) {
    Base64.Encoder b64 = Base64.getEncoder();
    HttpResponse<byte[]> res =
        post(
            peer,
            INBOUND,
            Map.of(
                "header", b64.encodeToString(e.header()),
                "signature", b64.encodeToString(e.signature()),
                "wrappedKey", b64.encodeToString(e.wrappedKey()),
                "content", b64.encodeToString(e.content())));
    if (res.statusCode() / 100 == 2) {
      return Optional.empty();
    }
    if (res.statusCode() == 422) {
      return Optional.of(reason(res.body()));
    }
    throw new ChannelUnavailableException("Cell đích trả mã " + res.statusCode(), null);
  }

  @Override
  public void report(CellKeys peer, Report r) {
    Base64.Encoder b64 = Base64.getEncoder();
    HttpResponse<byte[]> res =
        post(
            peer,
            OUTCOMES,
            Map.of(
                "payload", b64.encodeToString(r.payload()),
                "signature", b64.encodeToString(r.signature())));
    // 422: cell nguồn không nhận kết quả (gói không có, chữ ký sai); gửi lại cũng không khác.
    if (res.statusCode() / 100 != 2 && res.statusCode() != 422) {
      throw new ChannelUnavailableException("Cell nguồn trả mã " + res.statusCode(), null);
    }
  }

  private HttpResponse<byte[]> post(CellKeys peer, String path, Map<String, String> body) {
    try {
      return http.send(
          HttpRequest.newBuilder(URI.create(stripSlash(peer.endpoint().toString()) + path))
              .timeout(timeout)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)))
              .build(),
          HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException e) {
      throw new ChannelUnavailableException("Không kết nối được cell " + peer.cellCode(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ChannelUnavailableException("Bị ngắt khi gửi sang cell " + peer.cellCode(), e);
    }
  }

  private String reason(byte[] body) {
    try {
      JsonNode n = json.readTree(body);
      String r = n == null ? "" : n.path("reason").asText("");
      return r.isBlank() ? "GOI_KHONG_HOP_LE" : r;
    } catch (IOException e) {
      return "GOI_KHONG_HOP_LE";
    }
  }

  private static String stripSlash(String s) {
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }
}
