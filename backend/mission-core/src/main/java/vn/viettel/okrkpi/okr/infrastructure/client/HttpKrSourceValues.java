package vn.viettel.okrkpi.okr.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import vn.viettel.okrkpi.okr.application.port.KrSourceValuePort;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.resilience.ResilientClient;

/**
 * {@link KrSourceValuePort} qua cổng nội bộ của integration-gateway (CORE-01-10): {@code GET
 * /internal/v1/integ/kr-source-values?keyResultId=&dataSource=} trả {@code {value, fetchedAt,
 * dataSource}}. DV-13-08 chưa công bố cổng nên đường dẫn và thân là suy luận (wiki trang 14).
 *
 * <p>Lời gọi đi qua {@link ResilientClient} instance {@value #INSTANCE}: bulkhead, time limiter 3
 * giây bao cả các lần thử lại, ngắt mạch theo cấu hình chuẩn TL-01. Mỗi lời gọi ghi timer {@value
 * #METRIC} gắn nhãn {@code data_source} và {@code outcome} để xem thời gian và tỷ lệ lỗi theo
 * nguồn.
 */
public class HttpKrSourceValues implements KrSourceValuePort {

  private static final Logger LOG = LoggerFactory.getLogger(HttpKrSourceValues.class);

  /** Tên instance Resilience4j. */
  public static final String INSTANCE = "integration-gateway";

  static final String PATH = "/internal/v1/integ/kr-source-values?keyResultId={k}&dataSource={d}";
  static final String TENANT_HEADER = "X-Tenant-Id";
  static final String METRIC = "okr.kr_source.fetch";

  private final ResilientClient http;
  private final Supplier<String> token;
  private final MeterRegistry meters;

  /**
   * @param meters có thể {@code null} khi không ghi chỉ số
   */
  public HttpKrSourceValues(ResilientClient http, Supplier<String> token, MeterRegistry meters) {
    this.http = Objects.requireNonNull(http, "http");
    this.token = Objects.requireNonNull(token, "token");
    this.meters = meters;
  }

  @Override
  public SourceValue fetch(TenantId tenant, UUID keyResultId, String dataSource) {
    long start = System.nanoTime();
    String outcome = "error";
    try {
      HttpHeaders h = new HttpHeaders();
      h.setBearerAuth(token.get());
      h.set(TENANT_HEADER, tenant.value().toString());
      h.setAccept(List.of(MediaType.APPLICATION_JSON));
      JsonNode body =
          http.exchange(HttpMethod.GET, PATH, null, h, JsonNode.class, keyResultId, dataSource);
      SourceValue v = parse(body, dataSource);
      outcome = "ok";
      return v;
    } catch (Unavailable e) {
      throw e;
    } catch (RuntimeException e) {
      // Chỉ ghi loại lỗi, không ghi giá trị số liệu.
      LOG.warn(
          "Không lấy được số liệu nguồn của KR {} ({})", keyResultId, e.getClass().getSimpleName());
      throw new Unavailable("Không lấy được số liệu nguồn của KR " + keyResultId, e);
    } finally {
      record(dataSource, outcome, System.nanoTime() - start);
    }
  }

  static SourceValue parse(JsonNode body, String dataSource) {
    if (body == null || !body.path("value").isNumber()) {
      throw new Unavailable("Nguồn không trả giá trị số", null);
    }
    BigDecimal value = body.get("value").decimalValue();
    Instant fetchedAt;
    try {
      String raw = body.path("fetchedAt").asText(null);
      fetchedAt = raw == null ? Instant.now() : OffsetDateTime.parse(raw).toInstant();
    } catch (DateTimeParseException e) {
      throw new Unavailable("Thời điểm lấy số liệu không hợp lệ", e);
    }
    String source = body.path("dataSource").asText(null);
    return new SourceValue(
        value, fetchedAt, source == null || source.isBlank() ? dataSource : source);
  }

  private void record(String dataSource, String outcome, long nanos) {
    if (meters == null) {
      return;
    }
    Timer.builder(METRIC)
        .description("Thời gian lấy số liệu nguồn của KR qua integration-gateway")
        .tag("data_source", dataSource == null ? "" : dataSource)
        .tag("outcome", outcome)
        .register(meters)
        .record(java.time.Duration.ofNanos(nanos));
  }
}
