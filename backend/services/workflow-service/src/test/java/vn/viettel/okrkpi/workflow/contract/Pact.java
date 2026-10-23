package vn.viettel.okrkpi.workflow.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Tệp hợp đồng Pact đặc tả v3 (QA-02), cùng khuôn với hợp đồng của integration-gateway (DV-13-11):
 * các tương tác HTTP ({@code interactions}) hoặc tin nhắn ({@code messages}). Pact không dùng
 * Broker: tệp nằm trong repo cạnh bên tiêu dùng (web ở {@code frontend/pact/pacts}, mission-core ở
 * {@code src/test/resources/pacts}) và bên cung cấp đọc thẳng tệp khi xác minh.
 */
public record Pact(
    String consumer,
    String provider,
    List<Interaction> interactions,
    List<Message> messages,
    JsonNode raw) {

  public static final String SPEC = "3.0.0";
  static final ObjectMapper JSON = new ObjectMapper();

  /** Một tương tác HTTP: trạng thái cần có ở đối tác, yêu cầu SVC-13 gửi, phản hồi SVC-13 cần. */
  public record Interaction(
      String description, List<State> states, JsonNode request, JsonNode response) {

    public String method() {
      return request.path("method").asText("GET").toUpperCase(java.util.Locale.ROOT);
    }

    public String path() {
      return request.path("path").asText("/");
    }

    /** Luật so khớp của một phần ({@code body}, {@code header}, {@code query}, {@code path}). */
    public JsonNode requestRules(String part) {
      return request.path("matchingRules").path(part);
    }

    public JsonNode responseRules(String part) {
      return response.path("matchingRules").path(part);
    }
  }

  /** Tin nhắn đối tác đẩy vào (message pact): nội dung SVC-13 cần đọc được. */
  public record Message(
      String description,
      List<State> states,
      JsonNode contents,
      JsonNode metadata,
      JsonNode rules) {}

  /** Trạng thái đối tác ({@code providerStates}); bên xác minh dựng dữ liệu theo tên và tham số. */
  public record State(String name, JsonNode params) {}

  /** Đọc tệp hợp đồng theo đường dẫn tính từ thư mục mô-đun (thư mục chạy Maven). */
  public static Pact load(Path file) {
    try {
      return of(JSON.readTree(file.toFile()));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static Pact of(JsonNode root) {
    List<Interaction> interactions = new ArrayList<>();
    for (JsonNode i : root.path("interactions")) {
      interactions.add(
          new Interaction(
              i.path("description").asText(), states(i), i.path("request"), i.path("response")));
    }
    List<Message> messages = new ArrayList<>();
    for (JsonNode m : root.path("messages")) {
      messages.add(
          new Message(
              m.path("description").asText(),
              states(m),
              m.path("contents"),
              m.path("metaData"),
              m.path("matchingRules").path("body")));
    }
    return new Pact(
        root.path("consumer").path("name").asText(),
        root.path("provider").path("name").asText(),
        List.copyOf(interactions),
        List.copyOf(messages),
        root);
  }

  private static List<State> states(JsonNode n) {
    List<State> out = new ArrayList<>();
    for (JsonNode s : n.path("providerStates")) {
      out.add(new State(s.path("name").asText(), s.path("params")));
    }
    return List.copyOf(out);
  }

  public Interaction interaction(String description) {
    return interactions.stream()
        .filter(i -> i.description().equals(description))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Không có tương tác " + description));
  }

  /** Bản sao để sửa trong kiểm thử âm tính (đổi giả định của hợp đồng rồi xác minh lại). */
  public ObjectNode copyRaw() {
    return (ObjectNode) Objects.requireNonNullElse(raw, MissingNode.getInstance()).deepCopy();
  }
}
