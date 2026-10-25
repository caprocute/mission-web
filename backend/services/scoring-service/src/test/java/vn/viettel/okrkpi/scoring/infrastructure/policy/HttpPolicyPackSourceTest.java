package vn.viettel.okrkpi.scoring.infrastructure.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.client.RestClient;
import vn.viettel.okrkpi.scoring.domain.PinnedPolicy;
import vn.viettel.okrkpi.scoring.domain.PolicyBindingKey;
import vn.viettel.okrkpi.scoring.domain.PolicyContent;
import vn.viettel.okrkpi.shared.resilience.ResilientClientFactory;

/** Client MOD-05 trên máy chủ HTTP giả trả đúng lược đồ {@code policies.yaml}. */
class HttpPolicyPackSourceTest {

  static final UUID TENANT = UUID.fromString("0192f1a2-0000-7000-8000-00000000a001");
  static final UUID PERIOD = UUID.fromString("0192f1a2-0000-7000-8000-0000000000bb");
  static final UUID PACK = UUID.fromString("0192a1b0-3c4d-7e5f-8a9b-0c1d2e3f4a01");
  static final UUID VERSION = UUID.fromString("0192a1b0-3c4d-7e5f-8a9b-0c1d2e3f4b20");
  static final String HASH = "9f".repeat(32);

  static final Map<String, String> ROUTES = new ConcurrentHashMap<>();
  static final Set<String> TENANTS = ConcurrentHashMap.newKeySet();
  private static HttpServer server;
  private static ResilientClientFactory factory;
  private static HttpPolicyPackSource source;

  @BeforeAll
  static void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        ex -> {
          TENANTS.add(String.valueOf(ex.getRequestHeaders().getFirst("X-Tenant-Id")));
          String key = ex.getRequestURI().toString();
          String body = ROUTES.get(key);
          byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders()
              .add("Content-Type", key.endsWith("/dmn") ? "application/xml" : "application/json");
          ex.sendResponseHeaders(body == null ? 404 : 200, body == null ? -1 : bytes.length);
          if (body != null) {
            ex.getResponseBody().write(bytes);
          }
          ex.close();
        });
    server.start();
    factory =
        new ResilientClientFactory(
            new StaticListableBeanFactory().getBeanProvider(RestClient.Builder.class),
            CircuitBreakerRegistry.ofDefaults(),
            RetryRegistry.of(
                RetryConfig.custom()
                    .retryExceptions(IOException.class, TimeoutException.class)
                    .build()),
            TimeLimiterRegistry.of(
                TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(3)).build()),
            BulkheadRegistry.ofDefaults());
    source =
        new HttpPolicyPackSource(
            factory.create("policy-pack", "http://127.0.0.1:" + server.getAddress().getPort()));
    String v = "/api/v1/policies/packs/" + PACK + "/versions";
    ROUTES.put(
        "/api/v1/policies/resolve?tenant=" + TENANT + "&period=" + PERIOD + "&flow=DANG",
        """
        {"policyPackId":"%s","code":"khoi-dang/quy-dinh-moi","versionId":"%s","version":"2.1.0",
         "contentHash":"%s","bindingId":"%s","frozen":true,"customerProfileVersionId":null}
        """
            .formatted(PACK, VERSION, HASH.toUpperCase(), PERIOD));
    ROUTES.put(
        v + "?limit=200",
        """
        {"items":[{"id":"%s","policyPackId":"%s","version":"2.2.0","status":"NHAP","rowVersion":1}],
         "nextCursor":"c2"}
        """
            .formatted(UUID.randomUUID(), PACK));
    ROUTES.put(
        v + "?limit=200&cursor=c2",
        """
        {"items":[{"id":"%s","policyPackId":"%s","version":"2.1.0","status":"DA_PHAT_HANH",
         "rowVersion":3}],"nextCursor":null}
        """
            .formatted(VERSION, PACK));
    ROUTES.put(
        v + "/2.1.0",
        """
        {"id":"%s","policyPackId":"%s","version":"2.1.0","status":"DA_PHAT_HANH","rowVersion":3,
         "packCode":"khoi-dang/quy-dinh-moi","contentHash":"%s",
         "manifest":{"inputs":{"x":{"type":"double"}},"params":{"he_so":2}}}
        """
            .formatted(VERSION, PACK, HASH));
    ROUTES.put(
        v + "/2.1.0/rules",
        """
        {"items":[
          {"id":"%s","ruleKey":"diem","expression":"x * 2.0","resultType":"DECIMAL",
           "rounding":{"digits":1,"mode":"NUA_LEN"},"legalRef":"BR-09-03","evalOrder":100,
           "overridable":false,"rowVersion":0},
          {"id":"%s","ruleKey":"dat","expression":"x > 1.0","resultType":"BOOLEAN",
           "overridable":false,"rowVersion":0}]}
        """
            .formatted(UUID.randomUUID(), UUID.randomUUID()));
    ROUTES.put(
        v + "/2.1.0/decision-tables",
        """
        {"items":[{"id":"%s","tableKey":"xep_loai","hitPolicy":"FIRST","inputs":[],"rules":[],
         "rowVersion":0}]}
        """
            .formatted(UUID.randomUUID()));
    ROUTES.put(v + "/2.1.0/decision-tables/xep_loai/dmn", "<definitions/>");
  }

  @AfterAll
  static void stop() throws Exception {
    server.stop(0);
    factory.destroy();
  }

  @Test
  void phanGiaiPhienBanGhim() {
    Optional<PinnedPolicy> p = source.resolve(PolicyBindingKey.of(TENANT, PERIOD, "DANG"));
    assertThat(p).isPresent();
    assertThat(p.get().versionId()).isEqualTo(VERSION);
    assertThat(p.get().contentHash()).isEqualTo(HASH);
    assertThat(p.get().frozen()).isTrue();
    assertThat(source.resolve(PolicyBindingKey.of(TENANT, PERIOD, "NHA_NUOC"))).isEmpty();
    assertThat(
            source.resolve(new PolicyBindingKey(TENANT, UUID.randomUUID(), "DANG", PERIOD, PERIOD)))
        .isEmpty();
  }

  @Test
  void taiNoiDungPhienBanTheoMaPhienBan() {
    PolicyContent c = source.load(TENANT, PACK, VERSION, null);
    assertThat(c.pinned().version()).isEqualTo("2.1.0");
    assertThat(c.pinned().contentHash()).isEqualTo(HASH);
    assertThat(c.pinned().packCode()).isEqualTo("khoi-dang/quy-dinh-moi");
    assertThat(c.manifest()).containsKey("params");
    assertThat(c.rules()).hasSize(2);
    assertThat(c.rules().getFirst().roundingDigits()).isEqualTo(1);
    assertThat(c.rules().get(1).roundingDigits()).isNull();
    assertThat(c.rules().get(1).evalOrder()).isZero();
    assertThat(c.tables()).singleElement().extracting(t -> t.dmnXml()).isEqualTo("<definitions/>");
    assertThat(source.load(TENANT, PACK, VERSION, "2.1.0").rules()).hasSize(2);
    // Mọi lời gọi nội dung mang tenant đích cho client dịch vụ (X-Tenant-Id).
    assertThat(TENANTS).containsOnly(TENANT.toString());
  }

  @Test
  void manifestDoiMangInputsVeAnhXaTheoTen() {
    Map<String, Object> m =
        HttpPolicyPackSource.manifest(
            Map.of(
                "inputs",
                List.of(
                    Map.of("name", "nhiem_vu", "type", "list<NhiemVu>"),
                    Map.of("name", "giu_chuc_vu", "type", "bool")),
                "ngu_canh",
                Map.of("nv", Map.of("type", "NhiemVu"))));
    assertThat(m.get("inputs"))
        .isEqualTo(
            Map.of(
                "nhiem_vu",
                Map.of("type", "list<NhiemVu>"),
                "giu_chuc_vu",
                Map.of("type", "bool")));
    assertThat(((Map<?, ?>) m.get("inputs")).keySet().stream().map(String::valueOf))
        .containsExactly("nhiem_vu", "giu_chuc_vu");
    assertThat(m.get("ngu_canh")).isEqualTo(Map.of("nv", Map.of("type", "NhiemVu")));
  }

  @Test
  void phienBanKhongTonTaiHoacSaiMa() {
    assertThatThrownBy(() -> source.load(TENANT, PACK, UUID.randomUUID(), null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> source.load(TENANT, PACK, UUID.randomUUID(), "2.1.0"))
        .isInstanceOf(IllegalStateException.class);
  }
}
