package vn.viettel.okrkpi.shared.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.env.MockEnvironment;
import vn.viettel.okrkpi.shared.config.PlatformDefaultsEnvironmentPostProcessor;

class ResilienceSupportTest {

  @Test
  void chiThaoTacIdempotentDuocThuLai() {
    HttpHeaders none = new HttpHeaders();
    HttpHeaders keyed = new HttpHeaders();
    keyed.set(ResilientClient.IDEMPOTENCY_KEY, "k-1");
    HttpHeaders blank = new HttpHeaders();
    blank.set(ResilientClient.IDEMPOTENCY_KEY, " ");

    assertThat(ResilientClient.isRetryable(HttpMethod.GET, none)).isTrue();
    assertThat(ResilientClient.isRetryable(HttpMethod.HEAD, none)).isTrue();
    assertThat(ResilientClient.isRetryable(HttpMethod.POST, none)).isFalse();
    assertThat(ResilientClient.isRetryable(HttpMethod.PUT, blank)).isFalse();
    assertThat(ResilientClient.isRetryable(HttpMethod.POST, keyed)).isTrue();
    assertThat(ResilientClient.isRetryable(HttpMethod.PATCH, keyed)).isTrue();
  }

  @Test
  void retryAfterLamTronLenToiThieuMotGiay() {
    DependencyUnavailableException.Reason r = DependencyUnavailableException.Reason.TIMEOUT;
    assertThat(
            new DependencyUnavailableException("x", r, Duration.ofMillis(1), null)
                .retryAfterSeconds())
        .isEqualTo(1);
    assertThat(
            new DependencyUnavailableException("x", r, Duration.ofMillis(20_001), null)
                .retryAfterSeconds())
        .isEqualTo(21);
    DependencyUnavailableException neg =
        new DependencyUnavailableException("x", r, Duration.ofSeconds(-3), null);
    assertThat(neg.retryAfter()).isEqualTo(Duration.ZERO);
    assertThat(neg.retryAfterSeconds()).isEqualTo(1);
    assertThat(new DependencyUnavailableException("x", r, null, null).retryAfter())
        .isEqualTo(Duration.ZERO);
    assertThat(neg.getMessage()).doesNotContain("http");
  }

  @Test
  void loiBoDemBoQua() {
    LenientCacheErrorHandler h = new LenientCacheErrorHandler();
    ConcurrentMapCache cache = new ConcurrentMapCache("okr");
    RuntimeException boom = new IllegalStateException("valkey down");
    assertThatCode(
            () -> {
              h.handleCacheGetError(boom, cache, "k");
              h.handleCachePutError(boom, cache, "k", "v");
              h.handleCacheEvictError(boom, cache, "k");
              h.handleCacheClearError(boom, null);
            })
        .doesNotThrowAnyException();
  }

  @Test
  void macDinhNapMotLanVoiDoUuTienThapNhat() {
    MockEnvironment env = new MockEnvironment();
    env.setProperty("spring.http.client.read-timeout", "60s");
    PlatformDefaultsEnvironmentPostProcessor epp = new PlatformDefaultsEnvironmentPostProcessor();
    epp.postProcessEnvironment(env, null);
    epp.postProcessEnvironment(env, null);

    assertThat(env.getProperty("spring.http.client.read-timeout")).isEqualTo("60s");
    assertThat(env.getProperty("spring.http.client.connect-timeout")).isEqualTo("500ms");
    assertThat(env.getProperty("resilience4j.circuitbreaker.configs.default.minimumNumberOfCalls"))
        .isEqualTo("20");
    assertThat(env.getPropertySources().stream().map(s -> s.getName()))
        .filteredOn(n -> n.startsWith(PlatformDefaultsEnvironmentPostProcessor.SOURCE_PREFIX))
        .hasSize(3);
    assertThat(epp.getOrder()).isEqualTo(Integer.MAX_VALUE);
  }
}
