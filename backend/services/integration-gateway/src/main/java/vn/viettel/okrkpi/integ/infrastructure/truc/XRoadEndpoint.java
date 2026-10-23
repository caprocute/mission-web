package vn.viettel.okrkpi.integ.infrastructure.truc;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import vn.viettel.okrkpi.integ.domain.Connector;

/**
 * Tham số X-Road của một kết nối, đọc từ {@code integ.connector.endpoint} dạng {@code
 * https://security-server/r1?client=VN/GOV/<mã thành viên>/<hệ thống con>&service=VN/GOV/<mã thành
 * viên>/<hệ thống con>/<mã dịch vụ>&from=<mã cơ quan gửi>&fromName=..&to=<mã cơ quan
 * nhận>&toName=..}: địa chỉ security server, mã thành viên, mã hệ thống con và mã định danh cơ quan
 * đều nằm trong bản ghi kết nối, đổi từ giả lập sang Trục thật chỉ đổi bản ghi này.
 */
record XRoadEndpoint(
    String base,
    String client,
    String service,
    String fromId,
    String fromName,
    String toId,
    String toName) {

  static XRoadEndpoint of(Connector connector) throws IOException {
    String endpoint = connector.endpoint();
    if (endpoint == null || !(endpoint.startsWith("https://") || endpoint.startsWith("http://"))) {
      throw new IOException("Địa chỉ X-Road của " + connector.code() + " không hợp lệ");
    }
    URI uri;
    try {
      uri = URI.create(endpoint);
    } catch (IllegalArgumentException e) {
      throw new IOException("Địa chỉ X-Road của " + connector.code() + " không hợp lệ", e);
    }
    Map<String, String> q = new HashMap<>();
    if (uri.getRawQuery() != null) {
      for (String part : uri.getRawQuery().split("&")) {
        int eq = part.indexOf('=');
        if (eq > 0) {
          q.put(
              part.substring(0, eq),
              URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
        }
      }
    }
    String client = q.get("client");
    String service = q.get("service");
    if (client == null
        || service == null
        || client.split("/").length != 4
        || service.split("/").length != 5) {
      throw new IOException(
          "Kết nối " + connector.code() + " thiếu mã thành viên hoặc mã dịch vụ X-Road");
    }
    String path = uri.getRawPath() == null ? "" : uri.getRawPath();
    if (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    String base = uri.getScheme() + "://" + uri.getRawAuthority() + (path.isEmpty() ? "/r1" : path);
    return new XRoadEndpoint(
        base, client, service, q.get("from"), q.get("fromName"), q.get("to"), q.get("toName"));
  }

  /** Địa chỉ một tài nguyên của dịch vụ: {@code <base>/<service>/<path>}. */
  String url(String path) {
    return base + "/" + service + path;
  }
}
