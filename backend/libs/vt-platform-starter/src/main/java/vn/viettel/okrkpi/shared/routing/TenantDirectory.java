package vn.viettel.okrkpi.shared.routing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.vtokr.registry.v1.TenantChanged;

/**
 * Danh bạ ánh xạ khách thuê sang cell và CSDL trong bộ nhớ ({@code registry.tenant_cell}, ADR-07).
 *
 * <p>Cấu trúc lưu trữ dùng {@link ConcurrentHashMap} không khoá đọc để bảo đảm thời gian tra cứu
 * mỗi yêu cầu dưới {@code 10 µs}. Danh bạ nạp dữ liệu từ {@link TenantRegistryClient} và nhận cập
 * nhật tức thì qua sự kiện Kafka {@code okr.registry.tenant.v1} ({@link TenantChanged}).
 */
public final class TenantDirectory {

  private static final Logger log = LoggerFactory.getLogger(TenantDirectory.class);
  private static final Duration DEFAULT_EVENT_TTL = Duration.ofMinutes(5);

  private final TenantRegistryClient registryClient;
  private final Clock clock;
  private final Duration eventTtl;
  private final ConcurrentHashMap<UUID, TenantCellRoute> byId = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, TenantCellRoute> byCode = new ConcurrentHashMap<>();

  public TenantDirectory(TenantRegistryClient registryClient) {
    this(registryClient, Clock.systemUTC(), DEFAULT_EVENT_TTL);
  }

  public TenantDirectory(TenantRegistryClient registryClient, Clock clock, Duration eventTtl) {
    this.registryClient = Objects.requireNonNull(registryClient, "registryClient must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.eventTtl = Objects.requireNonNull(eventTtl, "eventTtl must not be null");
  }

  /** Làm ấm bộ nhớ đệm từ {@link TenantRegistryClient#fetchInitialRoutes()}. */
  public void warmUp() {
    List<TenantCellRoute> initial = registryClient.fetchInitialRoutes();
    for (TenantCellRoute route : initial) {
      put(route);
    }
    if (!initial.isEmpty()) {
      log.info("Warmed up TenantDirectory with {} tenant routes", initial.size());
    }
  }

  /**
   * Tra cứu định tuyến theo {@link UUID} khách thuê. Nếu có sẵn trong bộ nhớ đệm và chưa hết hạn,
   * trả về ngay lập tức ({@code O(1)}, {@code < 10 µs}). Nếu chưa có hoặc đã hết hạn, gọi {@link
   * TenantRegistryClient#resolveByTenantId(UUID)}.
   */
  public Optional<TenantCellRoute> findByTenantId(UUID tenantId) {
    Objects.requireNonNull(tenantId, "tenantId must not be null");
    Instant now = clock.instant();
    TenantCellRoute cached = byId.get(tenantId);
    if (cached != null && !cached.isExpiredAt(now)) {
      return Optional.of(cached);
    }
    Optional<TenantCellRoute> resolved = registryClient.resolveByTenantId(tenantId);
    if (resolved.isPresent()) {
      put(resolved.get());
      return resolved;
    }
    return Optional.ofNullable(cached);
  }

  /** Tra cứu định tuyến theo mã chữ khách thuê ({@code tenantCode}). */
  public Optional<TenantCellRoute> findByCode(String tenantCode) {
    Objects.requireNonNull(tenantCode, "tenantCode must not be null");
    String key = normalizeCode(tenantCode);
    Instant now = clock.instant();
    TenantCellRoute cached = byCode.get(key);
    if (cached != null && !cached.isExpiredAt(now)) {
      return Optional.of(cached);
    }
    Optional<TenantCellRoute> resolved = registryClient.resolveByCode(key);
    if (resolved.isPresent()) {
      put(resolved.get());
      return resolved;
    }
    return Optional.ofNullable(cached);
  }

  /**
   * Tra cứu định tuyến theo cả {@code tenantId} (ưu tiên) và {@code tenantCode} (dự phòng khi sự
   * kiện Kafka cập nhật theo mã chữ).
   */
  public Optional<TenantCellRoute> resolve(UUID tenantId, String tenantCode) {
    Instant now = clock.instant();
    if (tenantId != null) {
      TenantCellRoute cachedById = byId.get(tenantId);
      if (cachedById != null && !cachedById.isExpiredAt(now)) {
        return Optional.of(cachedById);
      }
    }
    if (tenantCode != null && !tenantCode.isBlank()) {
      TenantCellRoute cachedByCode = byCode.get(normalizeCode(tenantCode));
      if (cachedByCode != null && !cachedByCode.isExpiredAt(now)) {
        return Optional.of(cachedByCode);
      }
    }
    if (tenantId != null) {
      Optional<TenantCellRoute> resolved = findByTenantId(tenantId);
      if (resolved.isPresent()) {
        return resolved;
      }
    }
    if (tenantCode != null && !tenantCode.isBlank()) {
      return findByCode(tenantCode);
    }
    return Optional.empty();
  }

  /**
   * Cập nhật trực tiếp bản ghi định tuyến vào bộ nhớ đệm (tôn trọng {@code sourceVersion} nếu lớn
   * hơn 0).
   */
  public void put(TenantCellRoute route) {
    Objects.requireNonNull(route, "route must not be null");
    String codeKey = normalizeCode(route.tenantCode());
    TenantCellRoute existingByCode = byCode.get(codeKey);
    if (existingByCode != null
        && existingByCode.sourceVersion() > 0
        && route.sourceVersion() > 0
        && route.sourceVersion() < existingByCode.sourceVersion()) {
      return;
    }
    UUID effectiveId =
        route.tenantId() != null
            ? route.tenantId()
            : (existingByCode != null ? existingByCode.tenantId() : null);
    TenantCellRoute merged =
        effectiveId != null && route.tenantId() == null
            ? new TenantCellRoute(
                effectiveId,
                route.tenantCode(),
                route.cellCode(),
                route.cellApiBaseUrl(),
                route.tenancyModel(),
                route.databaseName(),
                route.status(),
                route.domains(),
                route.sourceVersion(),
                route.expiresAt())
            : route;
    byCode.put(codeKey, merged);
    if (merged.tenantId() != null) {
      byId.put(merged.tenantId(), merged);
    }
  }

  /**
   * Xử lý sự kiện Avro {@link TenantChanged} từ chủ đề Kafka {@code okr.registry.tenant.v1} để làm
   * mới bộ nhớ đệm tức thì mà không cần khởi động lại dịch vụ.
   */
  public void onTenantChanged(TenantChanged event) {
    Objects.requireNonNull(event, "event must not be null");
    String codeKey = normalizeCode(event.getTenantCode());
    TenantCellRoute existing = byCode.get(codeKey);
    if (existing != null
        && existing.sourceVersion() > 0
        && event.getSourceVersion() < existing.sourceVersion()) {
      log.debug(
          "Ignoring stale TenantChanged event for {} (version {} < {})",
          codeKey,
          event.getSourceVersion(),
          existing.sourceVersion());
      return;
    }
    TenancyModel model = TenancyModel.fromString(event.getTenancyModel());
    String cellCode =
        event.getCellCode() != null && !event.getCellCode().isBlank()
            ? event.getCellCode()
            : (existing != null ? existing.cellCode() : "cell-shared-01");
    String dbName =
        event.getDatabaseName() != null && !event.getDatabaseName().isBlank()
            ? event.getDatabaseName()
            : model.defaultDatabaseName(codeKey);
    UUID tenantId = existing != null ? existing.tenantId() : null;
    String cellApiBaseUrl = existing != null ? existing.cellApiBaseUrl() : null;
    TenantCellRoute updated =
        new TenantCellRoute(
            tenantId,
            codeKey,
            cellCode,
            cellApiBaseUrl,
            model,
            dbName,
            event.getStatus(),
            event.getDomains(),
            event.getSourceVersion(),
            clock.instant().plus(eventTtl));
    put(updated);
  }

  /** Xoá một khách thuê khỏi bộ nhớ đệm. */
  public void evict(UUID tenantId, String tenantCode) {
    if (tenantId != null) {
      TenantCellRoute removed = byId.remove(tenantId);
      if (removed != null) {
        byCode.remove(normalizeCode(removed.tenantCode()));
      }
    }
    if (tenantCode != null && !tenantCode.isBlank()) {
      TenantCellRoute removed = byCode.remove(normalizeCode(tenantCode));
      if (removed != null && removed.tenantId() != null) {
        byId.remove(removed.tenantId());
      }
    }
  }

  /** Bản chụp bất biến các định tuyến hiện có theo mã chữ (phục vụ chẩn đoán / kiểm thử). */
  public Map<String, TenantCellRoute> snapshotByCode() {
    return Map.copyOf(byCode);
  }

  private static String normalizeCode(String code) {
    return code.trim().toLowerCase(Locale.ROOT);
  }
}
