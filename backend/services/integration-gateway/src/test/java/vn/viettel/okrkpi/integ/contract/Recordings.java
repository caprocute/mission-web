package vn.viettel.okrkpi.integ.contract;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.request;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Bản ghi tương tác đã che dữ liệu của một hệ ngoài (DV-13-11), ở {@code
 * data/golden/integ-recordings/<hệ ngoài>/*.json}: mỗi tệp có {@code provider}, {@code daChe: true}
 * và danh sách {@code exchanges} (yêu cầu, phản hồi). Khi đối tác chưa có môi trường thử, máy phát
 * lại các bản ghi này đóng vai đối tác để xác minh hợp đồng ngược lại dữ liệu đã thấy thật.
 */
public record Recordings(String provider, List<JsonNode> files) {

  public static final String DIR = "data/golden/integ-recordings";

  /** Thư mục gốc repo: đi lên từ thư mục chạy (module Maven) tới khi gặp {@code data/golden}. */
  public static Path repoRoot() {
    Path p = Path.of("").toAbsolutePath();
    while (p != null && !Files.isDirectory(p.resolve("data/golden"))) {
      p = p.getParent();
    }
    if (p == null) {
      throw new IllegalStateException("Không tìm thấy thư mục gốc repo (data/golden)");
    }
    return p;
  }

  public static Path dir() {
    return repoRoot().resolve(DIR);
  }

  public static Recordings load(String provider) {
    Path d = dir().resolve(provider);
    List<JsonNode> files = new ArrayList<>();
    try (Stream<Path> s = Files.list(d)) {
      for (Path f : s.filter(x -> x.toString().endsWith(".json")).sorted().toList()) {
        JsonNode n = Pact.JSON.readTree(f.toFile());
        if (!provider.equals(n.path("provider").asText()) || !n.path("daChe").asBoolean(false)) {
          throw new IllegalStateException(
              "Bản ghi " + f.getFileName() + " sai hệ ngoài hoặc chưa che dữ liệu");
        }
        files.add(n);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    if (files.isEmpty()) {
      throw new IllegalStateException("Chưa có bản ghi tương tác của " + provider);
    }
    return new Recordings(provider, List.copyOf(files));
  }

  public List<JsonNode> exchanges() {
    List<JsonNode> out = new ArrayList<>();
    files.forEach(f -> f.path("exchanges").forEach(out::add));
    return out;
  }

  /** Máy phát lại: trả đúng phản hồi đã ghi cho yêu cầu cùng phương thức, đường dẫn, truy vấn. */
  public WireMockServer server() {
    WireMockServer s = new WireMockServer(wireMockConfig().dynamicPort());
    s.start();
    for (JsonNode x : exchanges()) {
      JsonNode req = x.path("request");
      MappingBuilder b =
          request(req.path("method").asText("GET"), urlPathEqualTo(req.path("path").asText()));
      req.path("query")
          .fields()
          .forEachRemaining(
              q -> b.withQueryParam(q.getKey(), equalTo(q.getValue().path(0).asText())));
      JsonNode res = x.path("response");
      ResponseDefinitionBuilder r = aResponse().withStatus(res.path("status").asInt(200));
      res.path("headers")
          .fields()
          .forEachRemaining(h -> r.withHeader(h.getKey(), h.getValue().asText()));
      JsonNode body = res.path("body");
      if (body.isContainerNode()) {
        r.withBody(body.toString());
      } else if (body.isTextual()) {
        r.withBody(body.asText());
      }
      s.stubFor(b.willReturn(r));
    }
    return s;
  }

  /** Các phần tử JSON ở {@code pointer} trong thân phản hồi đã ghi, dùng làm tin nhắn thật. */
  public List<JsonNode> items(String pathPrefix, String pointer) {
    List<JsonNode> out = new ArrayList<>();
    for (JsonNode x : exchanges()) {
      if (x.path("request").path("path").asText().startsWith(pathPrefix)) {
        x.path("response").path("body").at(pointer).forEach(out::add);
      }
    }
    return out;
  }
}
