package vn.viettel.okrkpi.integ.contract;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.request;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.QueryParameter;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Đối tác giả lập dựng từ tệp Pact cho kiểm thử phía tiêu dùng (DV-13-11): mỗi tương tác là một
 * stub WireMock trả đúng phản hồi của hợp đồng. Sau khi mã bộ kết nối chạy, {@link #verify} kiểm
 * mọi yêu cầu đã gửi khớp một tương tác (phương thức, đường dẫn, đủ và không thừa tham số truy vấn,
 * header của hợp đồng, thân) và mọi tương tác được nêu đều đã được gọi. Mã gửi sai hợp đồng thì
 * kiểm thử đỏ, giống mock server của pact-jvm.
 */
public final class PactMockProvider implements AutoCloseable {

  private final Pact pact;
  private final WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort());
  private final Map<UUID, Pact.Interaction> byStub = new HashMap<>();

  public PactMockProvider(Pact pact) {
    this.pact = pact;
    server.start();
    for (Pact.Interaction i : pact.interactions()) {
      UUID id = UUID.randomUUID();
      byStub.put(id, i);
      server.stubFor(stub(i).withId(id));
    }
  }

  public static PactMockProvider of(String pactFile) {
    return new PactMockProvider(Pact.load(pactFile));
  }

  public Pact pact() {
    return pact;
  }

  public String baseUrl() {
    return server.baseUrl();
  }

  /** Xoá nhật ký yêu cầu giữa các kiểm thử. */
  public void reset() {
    server.resetRequests();
  }

  private static MappingBuilder stub(Pact.Interaction i) {
    JsonNode pathRule = i.requestRules("path").path("matchers").path(0);
    MappingBuilder b =
        request(
            i.method(),
            "regex".equals(pathRule.path("match").asText())
                ? urlPathMatching(pathRule.path("regex").asText())
                : urlPathEqualTo(i.path()));
    JsonNode queryRules = i.requestRules("query");
    for (Iterator<Map.Entry<String, JsonNode>> it = i.request().path("query").fields();
        it.hasNext(); ) {
      Map.Entry<String, JsonNode> q = it.next();
      String regex = regex(queryRules.path(q.getKey()));
      b.withQueryParam(
          q.getKey(), regex != null ? matching(regex) : equalTo(q.getValue().path(0).asText()));
    }
    JsonNode headerRules = i.requestRules("header");
    for (Iterator<Map.Entry<String, JsonNode>> it = i.request().path("headers").fields();
        it.hasNext(); ) {
      Map.Entry<String, JsonNode> h = it.next();
      String regex = regex(headerRules.path(h.getKey()));
      b.withHeader(h.getKey(), regex != null ? matching(regex) : equalTo(h.getValue().asText()));
    }
    JsonNode res = i.response();
    ResponseDefinitionBuilder r = aResponse().withStatus(res.path("status").asInt(200));
    res.path("headers")
        .fields()
        .forEachRemaining(h -> r.withHeader(h.getKey(), h.getValue().asText()));
    JsonNode body = res.path("body");
    if (body.isContainerNode()) {
      r.withBody(body.toString());
    } else if (!body.isMissingNode() && !body.isNull()) {
      r.withBody(body.asText());
    }
    return b.willReturn(r);
  }

  private static String regex(JsonNode rule) {
    JsonNode m = rule.path("matchers").path(0);
    return "regex".equals(m.path("match").asText()) ? m.path("regex").asText() : null;
  }

  /** Kiểm mọi tương tác của tệp đều đã được gọi. */
  public void verify() {
    verify(pact.interactions().stream().map(Pact.Interaction::description).toList());
  }

  /** Kiểm yêu cầu đã gửi khớp hợp đồng và các tương tác được nêu đều đã được gọi. */
  public void verify(Collection<String> expected) {
    List<String> errors = new ArrayList<>();
    Set<String> called = new LinkedHashSet<>();
    for (ServeEvent e : server.getAllServeEvents()) {
      LoggedRequest req = e.getRequest();
      if (!e.getWasMatched() || e.getStubMapping() == null) {
        errors.add(
            "yêu cầu không có trong hợp đồng "
                + pact.provider()
                + ": "
                + req.getMethod()
                + " "
                + req.getUrl());
        continue;
      }
      Pact.Interaction i = byStub.get(e.getStubMapping().getId());
      called.add(i.description());
      errors.addAll(requestErrors(i, req).stream().map(s -> i.description() + ": " + s).toList());
    }
    for (String d : expected) {
      pact.interaction(d);
      if (!called.contains(d)) {
        errors.add("tương tác chưa được gọi: " + d);
      }
    }
    if (!errors.isEmpty()) {
      throw new AssertionError(
          "Lệch hợp đồng " + pact.provider() + ":\n  " + String.join("\n  ", errors));
    }
  }

  static List<String> requestErrors(Pact.Interaction i, LoggedRequest req) {
    List<String> errors = new ArrayList<>();
    JsonNode query = i.request().path("query");
    for (QueryParameter q : req.getQueryParams().values()) {
      if (!query.has(q.key())) {
        errors.add("tham số truy vấn " + q.key() + " không có trong hợp đồng");
      } else if (q.values().size() != query.path(q.key()).size()) {
        errors.add("tham số truy vấn " + q.key() + " sai số giá trị");
      }
    }
    query
        .fieldNames()
        .forEachRemaining(
            k -> {
              if (!req.getQueryParams().containsKey(k)) {
                errors.add("thiếu tham số truy vấn " + k);
              }
            });
    JsonNode body = i.request().path("body");
    if (!body.isMissingNode()) {
      try {
        errors.addAll(
            PactMatcher.body(
                body, Pact.JSON.readTree(req.getBody()), i.requestRules("body"), false));
      } catch (IOException e) {
        errors.add("thân yêu cầu không phải JSON");
      }
    }
    return errors;
  }

  @Override
  public void close() {
    server.stop();
  }
}
