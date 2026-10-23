package vn.viettel.okrkpi.contract.pact;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Máy giả lập bên cung cấp cho kiểm thử bên tiêu dùng service với service (QA-02): chỉ trả phản hồi
 * của một tương tác khi yêu cầu khớp phương thức, đường dẫn, tham số truy vấn, header và thân (theo
 * luật so khớp của phần yêu cầu, không cho trường thừa như đặc tả Pact); lệch thì trả 500 và ghi
 * lỗi vào {@link #mismatches()}.
 */
final class PactMockProvider implements AutoCloseable {

  private final Pact.Interaction interaction;
  private final HttpServer server;
  private final List<String> mismatches = new ArrayList<>();

  PactMockProvider(Pact.Interaction interaction) throws IOException {
    this.interaction = interaction;
    this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::respond);
    server.start();
  }

  String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /** Token trong header {@code Authorization} mẫu của hợp đồng (bỏ tiền tố {@code Bearer }). */
  String token() {
    return interaction
        .request()
        .at("/headers/Authorization")
        .asText()
        .substring("Bearer ".length());
  }

  List<String> mismatches() {
    return List.copyOf(mismatches);
  }

  @Override
  public void close() {
    server.stop(0);
  }

  private void respond(HttpExchange ex) throws IOException {
    JsonNode req = interaction.request();
    if (!ex.getRequestMethod().equals(interaction.method())) {
      mismatches.add("phương thức " + ex.getRequestMethod());
    }
    if (!ex.getRequestURI().getPath().equals(interaction.path())) {
      mismatches.add("đường dẫn " + ex.getRequestURI().getPath());
    }
    Map<String, List<String>> query = query(ex.getRequestURI().getRawQuery());
    Map<String, List<String>> expectedQuery = new LinkedHashMap<>();
    req.path("query")
        .fields()
        .forEachRemaining(
            q -> {
              List<String> values = new ArrayList<>();
              q.getValue().forEach(v -> values.add(v.asText()));
              expectedQuery.put(q.getKey(), values);
            });
    if (!query.equals(expectedQuery)) {
      mismatches.add("tham số truy vấn " + query);
    }
    for (Iterator<Map.Entry<String, JsonNode>> it = req.path("headers").fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> h = it.next();
      String actual = ex.getRequestHeaders().getFirst(h.getKey());
      boolean ok =
          actual != null
              && ("content-type".equalsIgnoreCase(h.getKey())
                  ? actual.split(";", 2)[0].strip().equalsIgnoreCase(h.getValue().asText())
                  : PactMatcher.value(
                      h.getKey(),
                      h.getValue().asText(),
                      actual,
                      interaction.requestRules("header")));
      if (!ok) {
        mismatches.add("header " + h.getKey() + " = " + actual);
      }
    }
    byte[] sent = ex.getRequestBody().readAllBytes();
    if (req.has("body")) {
      JsonNode actual = sent.length == 0 ? null : Pact.JSON.readTree(sent);
      PactMatcher.body(req.get("body"), actual, interaction.requestRules("body"), false)
          .forEach(e -> mismatches.add("thân yêu cầu " + e));
    }
    JsonNode res = interaction.response();
    byte[] body =
        res.has("body") ? res.get("body").toString().getBytes(StandardCharsets.UTF_8) : new byte[0];
    res.path("headers")
        .fields()
        .forEachRemaining(h -> ex.getResponseHeaders().add(h.getKey(), h.getValue().asText()));
    int status = mismatches.isEmpty() ? res.path("status").asInt(200) : 500;
    ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
    if (body.length > 0) {
      ex.getResponseBody().write(body);
    }
    ex.close();
  }

  private static Map<String, List<String>> query(String raw) {
    Map<String, List<String>> out = new LinkedHashMap<>();
    if (raw == null || raw.isEmpty()) {
      return out;
    }
    for (String pair : raw.split("&")) {
      String[] kv = pair.split("=", 2);
      out.computeIfAbsent(decode(kv[0]), k -> new ArrayList<>())
          .add(kv.length > 1 ? decode(kv[1]) : "");
    }
    return out;
  }

  private static String decode(String s) {
    return URLDecoder.decode(s, StandardCharsets.UTF_8);
  }
}
