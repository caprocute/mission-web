package vn.viettel.okrkpi.integ.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * Xác minh phía đối tác (DV-13-11): phát lại yêu cầu của từng tương tác trong tệp Pact tới một đối
 * tác (bản giả lập, máy phát lại bản ghi tương tác đã che, hay môi trường thử thật của đối tác) rồi
 * so phản hồi với hợp đồng: mã trạng thái, header của hợp đồng (Content-Type so phần kiểu), thân
 * theo luật so khớp, cho phép trường thừa. Trước mỗi tương tác, nếu có địa chỉ đổi trạng thái thì
 * gửi {@code POST {"state", "params", "action": "setup"}} như quy ước của Pact.
 *
 * <p>Header {@code Authorization} của hợp đồng là giá trị giả lập; khi xác minh với môi trường
 * thật, mã thông báo thật (đọc từ tệp do OpenBao gắn) thay vào, không ghi ra nhật ký.
 */
public final class PactVerifier {

  /** Kết quả một tương tác; {@code errors} rỗng là đạt. */
  public record Result(String description, List<String> errors) {
    public boolean ok() {
      return errors.isEmpty();
    }
  }

  private final HttpClient http;
  private final URI base;
  private final Optional<String> token;
  private final Optional<URI> stateChange;
  private final Duration timeout;

  public PactVerifier(URI base, Optional<String> token, Optional<URI> stateChange) {
    this(HttpClient.newHttpClient(), base, token, stateChange, Duration.ofSeconds(15));
  }

  PactVerifier(
      HttpClient http,
      URI base,
      Optional<String> token,
      Optional<URI> stateChange,
      Duration timeout) {
    this.http = http;
    this.base = base;
    this.token = token;
    this.stateChange = stateChange;
    this.timeout = timeout;
  }

  public static PactVerifier of(String baseUrl) {
    return new PactVerifier(URI.create(baseUrl), Optional.empty(), Optional.empty());
  }

  public List<Result> verify(Pact pact) {
    List<Result> out = new ArrayList<>();
    for (Pact.Interaction i : pact.interactions()) {
      out.add(new Result(i.description(), verify(i)));
    }
    return out;
  }

  /** Lỗi của mọi tương tác, mỗi dòng mở đầu bằng mô tả tương tác; rỗng là đạt. */
  public List<String> failures(Pact pact) {
    return verify(pact).stream()
        .flatMap(r -> r.errors().stream().map(e -> r.description() + ": " + e))
        .toList();
  }

  List<String> verify(Pact.Interaction i) {
    List<String> errors = new ArrayList<>();
    try {
      for (Pact.State s : i.states()) {
        setup(s);
      }
      HttpResponse<byte[]> res = http.send(build(i), HttpResponse.BodyHandlers.ofByteArray());
      JsonNode expected = i.response();
      int status = expected.path("status").asInt(200);
      if (res.statusCode() != status) {
        errors.add("mã trạng thái " + res.statusCode() + ", hợp đồng cần " + status);
        return errors;
      }
      for (Iterator<Map.Entry<String, JsonNode>> it = expected.path("headers").fields();
          it.hasNext(); ) {
        Map.Entry<String, JsonNode> h = it.next();
        String actual = res.headers().firstValue(h.getKey()).orElse(null);
        String want = h.getValue().asText();
        if (actual == null) {
          errors.add("thiếu header " + h.getKey());
        } else if ("content-type".equalsIgnoreCase(h.getKey())) {
          if (!mediaType(actual).equals(mediaType(want))) {
            errors.add("Content-Type " + mediaType(actual) + ", hợp đồng cần " + mediaType(want));
          }
        } else if (!PactMatcher.value(h.getKey(), want, actual, i.responseRules("header"))) {
          errors.add("header " + h.getKey() + " khác hợp đồng");
        }
      }
      JsonNode body = expected.path("body");
      if (body.isContainerNode()) {
        JsonNode actual;
        try {
          actual = Pact.JSON.readTree(res.body());
        } catch (IOException e) {
          errors.add("thân phản hồi không phải JSON");
          return errors;
        }
        errors.addAll(PactMatcher.body(body, actual, i.responseRules("body"), true));
      } else if (body.isTextual()) {
        String actual = new String(res.body(), StandardCharsets.UTF_8);
        if (!PactMatcher.value("$", body.asText(), actual, i.responseRules("body"))) {
          errors.add("thân phản hồi khác hợp đồng");
        }
      }
    } catch (IOException e) {
      errors.add("không gọi được đối tác: " + e.getClass().getSimpleName());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      errors.add("bị ngắt");
    }
    return errors;
  }

  private HttpRequest build(Pact.Interaction i) {
    StringJoiner qs = new StringJoiner("&");
    i.request()
        .path("query")
        .fields()
        .forEachRemaining(
            q -> q.getValue().forEach(v -> qs.add(enc(q.getKey()) + "=" + enc(v.asText()))));
    String root = base.toString().replaceAll("/+$", "");
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(root + i.path() + (qs.length() == 0 ? "" : "?" + qs)))
            .timeout(timeout);
    i.request()
        .path("headers")
        .fields()
        .forEachRemaining(
            h -> {
              if ("authorization".equalsIgnoreCase(h.getKey()) && token.isPresent()) {
                b.header(h.getKey(), "Bearer " + token.get());
              } else if (!"host".equalsIgnoreCase(h.getKey())) {
                b.header(h.getKey(), h.getValue().asText());
              }
            });
    JsonNode body = i.request().path("body");
    b.method(
        i.method(),
        body.isMissingNode()
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(
                body.isTextual() ? body.asText() : body.toString()));
    return b.build();
  }

  private void setup(Pact.State s) throws IOException, InterruptedException {
    if (stateChange.isEmpty()) {
      return;
    }
    ObjectNode body = Pact.JSON.createObjectNode().put("state", s.name()).put("action", "setup");
    body.set("params", s.params().isMissingNode() ? Pact.JSON.createObjectNode() : s.params());
    HttpResponse<Void> r =
        http.send(
            HttpRequest.newBuilder(stateChange.get())
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build(),
            HttpResponse.BodyHandlers.discarding());
    if (r.statusCode() / 100 != 2) {
      throw new IOException("Không dựng được trạng thái " + s.name() + ": " + r.statusCode());
    }
  }

  /**
   * Xác minh tin nhắn (message pact): một tin nhắn thật hoặc ghi lại của đối tác phải thoả nội dung
   * của tin nhắn trong hợp đồng theo luật so khớp, cho phép trường thừa.
   */
  public static List<String> verifyMessage(Pact.Message message, JsonNode actual) {
    return PactMatcher.body(message.contents(), actual, message.rules(), true);
  }

  private static String mediaType(String contentType) {
    return contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
