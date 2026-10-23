package vn.viettel.okrkpi.scoring.infrastructure.inputs;

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
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.client.RestClient;
import vn.viettel.okrkpi.scoring.application.port.ScoringInputs;
import vn.viettel.okrkpi.scoring.domain.CoreScoringInput;
import vn.viettel.okrkpi.shared.resilience.ResilientClientFactory;

/** Client đầu vào MOD-04 trên máy chủ HTTP giả theo lược đồ {@code appraisals.yaml}. */
class HttpScoringInputsTest {

  static final UUID TENANT = UUID.fromString("0192f1a2-0000-7000-8000-00000000a001");
  static final UUID PERIOD = UUID.fromString("0192f1a2-0000-7000-8000-0000000000bb");
  static final UUID PROFILE = UUID.fromString("0192f1a2-0000-7000-8000-0000000000c1");
  static final UUID TASK_1 = UUID.fromString("0192f1a2-0000-7000-8000-0000000000d1");
  static final UUID TASK_2 = UUID.fromString("0192f1a2-0000-7000-8000-0000000000d2");

  static final Map<String, String> ROUTES = new ConcurrentHashMap<>();
  private static HttpServer server;
  private static HttpScoringInputs inputs;

  @BeforeAll
  static void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        ex -> {
          String body = ROUTES.get(ex.getRequestURI().toString());
          byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(body == null ? 404 : 200, body == null ? -1 : bytes.length);
          if (body != null) {
            ex.getResponseBody().write(bytes);
          }
          ex.close();
        });
    server.start();
    ResilientClientFactory factory =
        new ResilientClientFactory(
            new StaticListableBeanFactory().getBeanProvider(RestClient.Builder.class),
            CircuitBreakerRegistry.ofDefaults(),
            RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build()),
            TimeLimiterRegistry.of(
                TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(3)).build()),
            BulkheadRegistry.ofDefaults());
    inputs =
        new HttpScoringInputs(
            factory.create(
                "appraisal-inputs", "http://127.0.0.1:" + server.getAddress().getPort()));
    String base = "/api/v1/appraisals/inputs";
    ROUTES.put(
        base + "?period=" + PERIOD + "&limit=200",
        """
        {"items":[{"profileId":"%s","personId":"%s","flow":"DANG","isHead":true,
          "policyPackVersionId":"%s",
          "generalCriteria":[{"criteriaCode":"TCC-01","met":true},{"criteriaCode":"TCC-02"}],
          "workItems":[{"taskId":"%s","standardScore":120,"difficulty":3,"taskGroup":"THEN_CHOT",
            "statusAtLock":"HOAN_THANH","qualityLevel":0.75,"subjectiveLateWorkdays":2,
            "coefficientLevels":{"K1":"CAO"}}],
          "workItemsNextCursor":"w2","incidents":[{"code":"SV-01"}]}],
         "nextCursor":"p2"}
        """
            .formatted(PROFILE, UUID.randomUUID(), UUID.randomUUID(), TASK_1));
    ROUTES.put(
        base + "/" + PROFILE + "/work-items?limit=200&cursor=w2",
        """
        {"items":[{"taskId":"%s","statusAtLock":"DANG_THUC_HIEN","deferredByDecision":true}]}
        """
            .formatted(TASK_2));
    ROUTES.put(base + "?period=" + PERIOD + "&limit=200&cursor=p2", "{\"items\":[]}");
    ROUTES.put(base + "?period=" + PERIOD + "&limit=200&cursor=rong", "{}");
  }

  @AfterAll
  static void stop() {
    server.stop(0);
  }

  @Test
  void docTrangVaDocTiepViecLa() {
    ScoringInputs.Page page = inputs.page(TENANT, PERIOD, null, ScoringInputs.PAGE_SIZE);
    assertThat(page.nextCursor()).isEqualTo("p2");
    CoreScoringInput in = page.items().get(0);
    assertThat(in.profileId()).isEqualTo(PROFILE);
    assertThat(in.head()).isTrue();
    assertThat(in.leadership()).isFalse();
    assertThat(in.incidents()).isEqualTo(1);
    assertThat(in.generalCriteria())
        .extracting(CoreScoringInput.Criterion::met)
        .containsExactly(true, false);
    assertThat(in.workItems())
        .extracting(CoreScoringInput.WorkItem::taskId)
        .containsExactly(TASK_1, TASK_2);
    CoreScoringInput.WorkItem first = in.workItems().get(0);
    assertThat(first.qualityLevel()).isEqualByComparingTo(new BigDecimal("0.75"));
    assertThat(first.subjectiveLateWorkdays()).isEqualTo(2);
    assertThat(first.objectiveLateWorkdays()).isZero();
    assertThat(in.workItems().get(1).deferredByDecision()).isTrue();

    ScoringInputs.Page last = inputs.page(TENANT, PERIOD, "p2", ScoringInputs.PAGE_SIZE);
    assertThat(last.items()).isEmpty();
    assertThat(last.nextCursor()).isNull();
  }

  @Test
  void trangRongHoacLoiLaLoiDoc() {
    assertThatThrownBy(() -> inputs.page(TENANT, PERIOD, "rong", ScoringInputs.PAGE_SIZE))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> inputs.page(TENANT, PERIOD, "khong-co", ScoringInputs.PAGE_SIZE))
        .isInstanceOf(RuntimeException.class);
  }
}
