package vn.viettel.okrkpi.integ.application;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.integ.application.IndicatorSource.Reading;
import vn.viettel.okrkpi.integ.application.port.Connectors;
import vn.viettel.okrkpi.integ.application.port.MappingXrefs;
import vn.viettel.okrkpi.integ.application.port.MappingXrefs.Located;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.EntityType;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Use case "lấy số liệu nguồn" cho MOD-01 (DV-13-08, QT-03 BR-03-04, API công khai {@code
 * getOkrKeyResultSourceValue} do MOD-01 phục vụ): tìm chỉ số ngoài gắn với KR qua {@code
 * mapping_xref} loại {@code CHI_SO}, gọi hệ nguồn trong thời hạn (mặc định 2,5 giây để MOD-01 còn
 * thời gian trả trong 3 giây) và ngắt mạch riêng cho mỗi tenant và kết nối. Hết hạn, mạch mở hay
 * nguồn lỗi đều thành {@link SourceUnavailableException} để MOD-01 trả 502 và cho nhập tay.
 */
public class KrSourceValueService {

  /** Giá trị trả MOD-01: {@code dataSource} là tên nguồn MOD-01 gửi, hoặc mã kết nối. */
  public record SourceValue(
      java.math.BigDecimal value, Instant fetchedAt, String dataSource, String connectorCode) {}

  private record Target(Connector connector, String externalId) {}

  private final Connectors connectors;
  private final MappingXrefs xrefs;
  private final Map<String, IndicatorSource> sources = new HashMap<>();
  private final TransactionOperations tx;
  private final Clock clock;
  private final TimeLimiter limiter;
  private final CircuitBreakerRegistry breakers;
  private final ExecutorService executor;

  public KrSourceValueService(
      Connectors connectors,
      MappingXrefs xrefs,
      Collection<IndicatorSource> sources,
      TransactionOperations tx,
      Clock clock,
      Duration timeout,
      CircuitBreakerRegistry breakers,
      ExecutorService executor) {
    this.connectors = Objects.requireNonNull(connectors, "connectors");
    this.xrefs = Objects.requireNonNull(xrefs, "xrefs");
    for (IndicatorSource s : sources) {
      if (this.sources.putIfAbsent(s.connectorCode(), s) != null) {
        throw new IllegalStateException("Trùng nguồn chỉ số cho kết nối " + s.connectorCode());
      }
    }
    this.tx = Objects.requireNonNull(tx, "tx");
    this.clock = Objects.requireNonNull(clock, "clock");
    if (timeout == null || timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("Thời hạn lấy số liệu nguồn phải dương");
    }
    this.limiter =
        TimeLimiter.of(
            TimeLimiterConfig.custom().timeoutDuration(timeout).cancelRunningFuture(true).build());
    this.breakers = Objects.requireNonNull(breakers, "breakers");
    this.executor = Objects.requireNonNull(executor, "executor");
  }

  /**
   * Số liệu hiện tại của KR {@code keyResultId}; {@link NoSuchElementException} khi KR chưa gắn chỉ
   * số ở kết nối đang bật nào (MOD-01 coi như nguồn không dùng được).
   */
  public SourceValue read(TenantId tenant, UUID keyResultId, String dataSource) {
    Objects.requireNonNull(tenant, "tenant");
    Objects.requireNonNull(keyResultId, "keyResultId");
    if (dataSource != null && dataSource.length() > 255) {
      throw new IllegalArgumentException("Tên nguồn tối đa 255 ký tự");
    }
    LocalDate today = LocalDate.now(clock);
    Target target =
        TenantScope.call(
            tenant, () -> tx.execute(s -> target(tenant, keyResultId, dataSource, today)));
    if (target == null) {
      throw new NoSuchElementException("KR chưa gắn chỉ số ở hệ nguồn đang bật");
    }
    Connector connector = target.connector();
    IndicatorSource source = sources.get(connector.code());
    CircuitBreaker breaker = breakers.circuitBreaker(connector.code() + ":" + tenant.value());
    Reading reading;
    try {
      reading =
          breaker.executeCallable(
              () ->
                  limiter.executeFutureSupplier(
                      () -> executor.submit(() -> source.read(connector, target.externalId()))));
    } catch (CallNotPermittedException e) {
      throw new SourceUnavailableException(
          SourceUnavailableException.Reason.CIRCUIT_OPEN,
          "Nguồn " + connector.code() + " đang ngắt mạch",
          e);
    } catch (TimeoutException e) {
      throw new SourceUnavailableException(
          SourceUnavailableException.Reason.TIMEOUT,
          "Nguồn " + connector.code() + " quá thời hạn",
          e);
    } catch (Exception e) {
      throw new SourceUnavailableException(
          SourceUnavailableException.Reason.ERROR, "Nguồn " + connector.code() + " lỗi", e);
    }
    if (reading == null || reading.value() == null) {
      throw new SourceUnavailableException(
          SourceUnavailableException.Reason.ERROR,
          "Nguồn " + connector.code() + " không trả giá trị",
          null);
    }
    return new SourceValue(
        reading.value(),
        reading.measuredAt() == null ? clock.instant() : reading.measuredAt(),
        dataSource == null || dataSource.isBlank() ? connector.code() : dataSource,
        connector.code());
  }

  /** Kết nối đang bật có nguồn chỉ số; ưu tiên kết nối trùng mã với tên nguồn MOD-01 gửi. */
  private Target target(TenantId tenant, UUID keyResultId, String dataSource, LocalDate today) {
    List<Located> found =
        xrefs.byInternalAnyConnector(tenant, EntityType.CHI_SO, keyResultId, today);
    Target first = null;
    for (Located l : found) {
      Connector c = connectors.findById(tenant, l.connectorId()).orElse(null);
      if (c == null || !c.enabled() || !c.direction().inbound() || !sources.containsKey(c.code())) {
        continue;
      }
      Target t = new Target(c, l.mapping().externalId());
      if (dataSource != null && c.code().equals(dataSource.strip().toUpperCase(Locale.ROOT))) {
        return t;
      }
      if (first == null) {
        first = t;
      }
    }
    return first;
  }
}
