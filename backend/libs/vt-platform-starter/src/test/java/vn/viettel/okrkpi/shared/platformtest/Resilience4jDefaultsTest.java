package vn.viettel.okrkpi.shared.platformtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.functions.Either;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.yaml.snakeyaml.Yaml;
import vn.viettel.okrkpi.shared.error.BusinessException;

/**
 * Tiêu chí hoàn thành NEN-016: đọc registry CircuitBreaker, TimeLimiter, Retry, Bulkhead mà {@code
 * resilience4j-spring-boot3} dựng từ cấu hình mặc định của starter và so với khối YAML "Cấu hình
 * Resilience4j chuẩn" của TL-01 ({@code tl01/resilience4j.yml}, chép nguyên văn). Mỗi khoá trong
 * YAML phải được kiểm; khoá chưa biết cách kiểm làm kiểm thử đỏ.
 */
@SpringBootTest(classes = PlatformTestApplication.class)
class Resilience4jDefaultsTest {

  private static final String DEFAULT = "default";

  /** Ngoại lệ mẫu cho từng tên lớp trong {@code recordExceptions}/{@code ignoreExceptions}. */
  private static final Map<String, Throwable> SAMPLES = new LinkedHashMap<>();

  static {
    SAMPLES.put("java.io.IOException", new IOException("x"));
    SAMPLES.put("java.util.concurrent.TimeoutException", new TimeoutException("x"));
    SAMPLES.put(
        "org.springframework.web.client.HttpServerErrorException",
        HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "x", null, null, null));
    SAMPLES.put(
        "vn.viettel.okrkpi.shared.error.BusinessException",
        new BusinessException("TASK-409-PERIOD-LOCKED"));
    SAMPLES.put(
        "org.springframework.web.client.HttpClientErrorException",
        HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "x", null, null, null));
    SAMPLES.put("java.lang.IllegalStateException", new IllegalStateException("x"));
  }

  @Autowired private CircuitBreakerRegistry circuitBreakers;
  @Autowired private TimeLimiterRegistry timeLimiters;
  @Autowired private RetryRegistry retries;
  @Autowired private BulkheadRegistry bulkheads;
  @Autowired private Environment environment;

  @Test
  void circuitBreakerKhopTl01() {
    compare(
        "circuitbreaker",
        name ->
            name == null
                ? circuitBreakers.getDefaultConfig()
                : circuitBreakers.circuitBreaker(name).getCircuitBreakerConfig(),
        circuitBreakerGetters());
  }

  @Test
  void timeLimiterKhopTl01() {
    Map<String, Function<TimeLimiterConfig, Object>> g = new LinkedHashMap<>();
    g.put("timeoutDuration", TimeLimiterConfig::getTimeoutDuration);
    g.put("cancelRunningFuture", TimeLimiterConfig::shouldCancelRunningFuture);
    compare(
        "timelimiter",
        name ->
            name == null
                ? timeLimiters.getDefaultConfig()
                : timeLimiters.timeLimiter(name).getTimeLimiterConfig(),
        g);
  }

  @Test
  void bulkheadKhopTl01() {
    Map<String, Function<BulkheadConfig, Object>> g = new LinkedHashMap<>();
    g.put("maxConcurrentCalls", BulkheadConfig::getMaxConcurrentCalls);
    g.put("maxWaitDuration", BulkheadConfig::getMaxWaitDuration);
    compare(
        "bulkhead",
        name ->
            name == null
                ? bulkheads.getDefaultConfig()
                : bulkheads.bulkhead(name).getBulkheadConfig(),
        g);
  }

  @Test
  void retryKhopTl01() {
    Map<String, Object> expected = effective("retry", null);
    for (String name : new String[] {null, "vneid", "chua-khai-bao"}) {
      RetryConfig c =
          name == null ? retries.getDefaultConfig() : retries.retry(name).getRetryConfig();
      Set<String> checked = new HashSet<>();

      assertThat(c.getMaxAttempts()).isEqualTo(number(expected.get("maxAttempts")).intValue());
      checked.add("maxAttempts");

      // Thời gian chờ: 200 ms × 2^(n-1), ngẫu nhiên ±50% (khoảng giá trị, không so đúng một số).
      long wait = duration(expected.get("waitDuration")).toMillis();
      double multiplier = number(expected.get("exponentialBackoffMultiplier")).doubleValue();
      double factor = number(expected.get("randomizedWaitFactor")).doubleValue();
      assertThat(expected.get("enableExponentialBackoff")).isEqualTo(true);
      assertThat(expected.get("enableRandomizedWait")).isEqualTo(true);
      for (int attempt = 1; attempt <= 2; attempt++) {
        double base = wait * Math.pow(multiplier, attempt - 1);
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (int i = 0; i < 300; i++) {
          long v = c.getIntervalBiFunction().apply(attempt, Either.left(new IOException("x")));
          min = Math.min(min, v);
          max = Math.max(max, v);
        }
        assertThat(min)
            .as("lần %d", attempt)
            .isGreaterThanOrEqualTo((long) (base * (1 - factor)) - 1);
        assertThat(max).as("lần %d", attempt).isLessThanOrEqualTo((long) (base * (1 + factor)) + 1);
        assertThat(max - min).as("có ngẫu nhiên hoá").isGreaterThan((long) (base * factor / 2));
      }
      checked.addAll(
          List.of(
              "waitDuration",
              "enableExponentialBackoff",
              "exponentialBackoffMultiplier",
              "enableRandomizedWait",
              "randomizedWaitFactor"));

      Predicate<Throwable> retryable = c.getExceptionPredicate();
      assertThat(matching(retryable)).isEqualTo(new TreeSet<>(names(expected, "retryExceptions")));
      checked.add("retryExceptions");
      for (String ignored : names(expected, "ignoreExceptions")) {
        assertThat(retryable.test(sample(ignored))).as(ignored).isFalse();
      }
      checked.add("ignoreExceptions");

      assertThat(checked).containsAll(expected.keySet());
    }
  }

  @Test
  void cauHinhMacDinhCuaStarterLaNguyenVanTl01() throws IOException {
    Map<String, Object> tl01 = load("tl01/resilience4j.yml");
    Map<String, Object> starter =
        load("vn/viettel/okrkpi/shared/resilience/resilience4j-defaults.yml");
    assertThat(starter).isEqualTo(tl01);
  }

  /** Bản chép {@code tl01/resilience4j.yml} trùng khối YAML trong TL-01 (khi có tài liệu). */
  @Test
  void banChepTl01KhopTaiLieu() throws IOException {
    Path doc = tl01Document();
    assumeTrue(doc != null, "Không có bản Markdown TL-01 trên máy chạy kiểm thử");
    String md = Files.readString(doc, StandardCharsets.UTF_8);
    Matcher m =
        Pattern.compile("## Cấu hình Resilience4j chuẩn\\s+```yaml\\n(.*?)```", Pattern.DOTALL)
            .matcher(md);
    assertThat(m.find()).as("Không thấy khối YAML trong TL-01").isTrue();
    try (InputStream in = resource("tl01/resilience4j.yml")) {
      assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(m.group(1));
    }
  }

  @Test
  void timeoutMacDinhVaCongQuanTriTach() {
    assertThat(environment.getProperty("spring.http.client.connect-timeout")).isEqualTo("500ms");
    assertThat(environment.getProperty("spring.http.client.read-timeout")).isEqualTo("3s");
    assertThat(environment.getProperty("spring.datasource.hikari.connection-timeout"))
        .isEqualTo("2000");
    assertThat(environment.getProperty("spring.transaction.default-timeout")).isEqualTo("5s");
    assertThat(environment.getProperty("spring.jpa.properties.jakarta.persistence.query.timeout"))
        .isEqualTo("5000");
    assertThat(environment.getProperty("spring.data.redis.connect-timeout")).isEqualTo("200ms");
    assertThat(environment.getProperty("spring.data.redis.timeout")).isEqualTo("300ms");
    assertThat(
            environment.getProperty("spring.kafka.properties.socket.connection.setup.timeout.ms"))
        .isEqualTo("1000");
    assertThat(environment.getProperty("spring.kafka.producer.properties.delivery.timeout.ms"))
        .isEqualTo("30000");
    assertThat(environment.getProperty("management.server.port")).isEqualTo("8081");
    assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
        .isEqualTo("health,info,prometheus");
    assertThat(environment.getProperty("management.metrics.distribution.slo.http.server.requests"))
        .isEqualTo("50ms,100ms,300ms,800ms,2000ms");
  }

  // ---------------------------------------------------------------------------------------------

  private static Map<String, Function<CircuitBreakerConfig, Object>> circuitBreakerGetters() {
    Map<String, Function<CircuitBreakerConfig, Object>> g = new LinkedHashMap<>();
    g.put("slidingWindowType", c -> c.getSlidingWindowType().name());
    g.put("slidingWindowSize", CircuitBreakerConfig::getSlidingWindowSize);
    g.put("minimumNumberOfCalls", CircuitBreakerConfig::getMinimumNumberOfCalls);
    g.put("failureRateThreshold", c -> (double) c.getFailureRateThreshold());
    g.put("slowCallDurationThreshold", CircuitBreakerConfig::getSlowCallDurationThreshold);
    g.put("slowCallRateThreshold", c -> (double) c.getSlowCallRateThreshold());
    g.put(
        "waitDurationInOpenState",
        c -> Duration.ofMillis(c.getWaitIntervalFunctionInOpenState().apply(1)));
    g.put(
        "permittedNumberOfCallsInHalfOpenState",
        CircuitBreakerConfig::getPermittedNumberOfCallsInHalfOpenState);
    g.put(
        "automaticTransitionFromOpenToHalfOpenEnabled",
        CircuitBreakerConfig::isAutomaticTransitionFromOpenToHalfOpenEnabled);
    g.put("recordExceptions", c -> matching(c.getRecordExceptionPredicate()));
    g.put("ignoreExceptions", c -> matching(c.getIgnoreExceptionPredicate()));
    return g;
  }

  /**
   * So từng cấu hình: {@code configs.default} với cấu hình mặc định của registry, từng {@code
   * instances.*} (gộp lên default) với instance cùng tên, và một instance không khai báo với
   * default.
   */
  private <C> void compare(
      String section, Function<String, C> config, Map<String, Function<C, Object>> getters) {
    List<String> targets = new ArrayList<>();
    targets.add(null);
    targets.addAll(instances(section).keySet());
    targets.add("chua-khai-bao");
    for (String name : targets) {
      Map<String, Object> expected = effective(section, name);
      C actual = config.apply(name);
      for (Map.Entry<String, Object> e : expected.entrySet()) {
        Function<C, Object> getter = getters.get(e.getKey());
        if (getter == null) {
          fail("Khoá %s.%s của TL-01 chưa được kiểm", section, e.getKey());
        }
        Object got = getter.apply(actual);
        Object want = normalize(e.getValue());
        if (got instanceof Number g && want instanceof Number w) {
          got = g.doubleValue();
          want = w.doubleValue();
        }
        assertThat(got)
            .as("%s %s.%s", section, name == null ? DEFAULT : name, e.getKey())
            .isEqualTo(want);
      }
    }
  }

  /** Giá trị hiệu lực: default của TL-01, ghi đè bởi instance (bỏ {@code baseConfig}). */
  private static Map<String, Object> effective(String section, String instance) {
    Map<String, Object> root = tl01Section(section);
    Map<String, Object> result = new LinkedHashMap<>(map(map(root.get("configs")).get(DEFAULT)));
    if (instance != null) {
      Map<String, Object> own = map(instances(section).get(instance));
      if (own != null) {
        own.forEach(
            (k, v) -> {
              if (!"baseConfig".equals(k)) {
                result.put(k, v);
              }
            });
      }
    }
    return result;
  }

  private static Map<String, Object> instances(String section) {
    Map<String, Object> i = map(tl01Section(section).get("instances"));
    return i == null ? Map.of() : i;
  }

  private static Map<String, Object> tl01Section(String section) {
    try {
      return map(map(load("tl01/resilience4j.yml").get("resilience4j")).get(section));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Object normalize(Object v) {
    if (v instanceof String s && s.matches("\\d+(ms|s)")) {
      return duration(s);
    }
    if (v instanceof List<?> list) {
      TreeSet<String> s = new TreeSet<>();
      list.forEach(x -> s.add(String.valueOf(x)));
      return s;
    }
    if (v instanceof Integer || v instanceof Long) {
      return v instanceof Long l ? l.intValue() : v;
    }
    if (v instanceof Double d) {
      return d;
    }
    return v;
  }

  /** Lớp ngoại lệ mẫu mà predicate chấp nhận. */
  private static TreeSet<String> matching(Predicate<Throwable> predicate) {
    TreeSet<String> s = new TreeSet<>();
    SAMPLES.forEach(
        (name, t) -> {
          if (predicate.test(t)) {
            s.add(name);
          }
        });
    return s;
  }

  private static Throwable sample(String className) {
    Throwable t = SAMPLES.get(className);
    if (t == null) {
      fail("Chưa có ngoại lệ mẫu cho %s", className);
    }
    return t;
  }

  @SuppressWarnings("unchecked")
  private static List<String> names(Map<String, Object> m, String key) {
    return (List<String>) m.get(key);
  }

  private static Number number(Object v) {
    return (Number) v;
  }

  private static Duration duration(Object v) {
    String s = String.valueOf(v);
    return s.endsWith("ms")
        ? Duration.ofMillis(Long.parseLong(s.substring(0, s.length() - 2)))
        : Duration.ofSeconds(Long.parseLong(s.substring(0, s.length() - 1)));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object o) {
    return (Map<String, Object>) o;
  }

  private static Map<String, Object> load(String path) throws IOException {
    try (InputStream in = resource(path)) {
      return new Yaml().load(in);
    }
  }

  private static InputStream resource(String path) {
    InputStream in = Resilience4jDefaultsTest.class.getClassLoader().getResourceAsStream(path);
    if (in == null) {
      throw new IllegalStateException("Thiếu tài nguyên " + path);
    }
    return in;
  }

  private static Path tl01Document() {
    Path p = Path.of("/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md");
    return Files.isReadable(p) ? p : null;
  }
}
