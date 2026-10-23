package vn.viettel.okrkpi.config.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.config.api.FeatureFlagApi;
import vn.viettel.okrkpi.config.application.port.ConfigAudit;
import vn.viettel.okrkpi.config.application.port.FeatureFlagRepository;
import vn.viettel.okrkpi.config.domain.FeatureFlag;
import vn.viettel.okrkpi.config.domain.FeatureFlagCatalog;
import vn.viettel.okrkpi.config.domain.FeatureFlagRollout;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cờ tính năng theo tenant của MOD-08 (CORE-08-07, QT-18, OPS-04): danh sách cờ nền tảng gộp với
 * giá trị tenant đã đặt, bật tắt có {@code If-Match} và ghi sổ cái, và cổng {@link FeatureFlagApi}
 * cho các mô-đun khác đọc qua đệm theo tenant.
 */
public class FeatureFlagService implements FeatureFlagApi {

  static final String TX = "configTransactionManager";
  static final String AUDIT_ACTION = "DOI_CO_TINH_NANG";
  static final String OBJECT_TYPE = "config.feature_flag";

  private final FeatureFlagRepository flags;
  private final ConfigAudit audit;
  private final TransactionOperations reads;
  private final Clock clock;
  private final Cache<UUID, Map<String, FeatureFlag>> cache;

  public FeatureFlagService(
      FeatureFlagRepository flags,
      ConfigAudit audit,
      TransactionOperations reads,
      Clock clock,
      Duration ttl) {
    this.flags = Objects.requireNonNull(flags, "flags");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.reads = Objects.requireNonNull(reads, "reads");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.cache = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(100_000).build();
  }

  /** Mọi cờ nền tảng của tenant theo thứ tự mã cờ. */
  @Transactional(transactionManager = TX, readOnly = true)
  public List<FeatureFlag> list(TenantId tenantId) {
    return List.copyOf(merged(tenantId).values());
  }

  /**
   * Đặt trạng thái và quy tắc bật dần của một cờ.
   *
   * @param expectedVersion phiên bản trong {@code If-Match}; cờ tenant chưa đặt có phiên bản 0
   * @throws NoSuchElementException khi mã cờ không có trong danh mục nền tảng
   */
  @Transactional(TX)
  public FeatureFlag set(
      TenantId tenantId,
      UUID actorId,
      String key,
      long expectedVersion,
      boolean enabled,
      FeatureFlagRollout rollout,
      Instant validFrom,
      Instant validTo) {
    FeatureFlag base = FeatureFlagCatalog.defaultOf(key).orElseThrow();
    FeatureFlag current = flags.find(tenantId, key).map(f -> withDescription(f, base)).orElse(base);
    if (expectedVersion != current.rowVersion()) {
      throw VersionConflictException.of("CONFIG", expectedVersion, current.rowVersion());
    }
    FeatureFlag next;
    try {
      next = current.changed(Ids.newUuidV7(), enabled, rollout, validFrom, validTo);
    } catch (IllegalArgumentException e) {
      throw new BusinessException("COMMON-422-VALIDATION", e.getMessage());
    }
    if (!flags.save(tenantId, next, current.rowVersion(), actorId)) {
      FeatureFlag now = flags.find(tenantId, key).orElse(current);
      throw VersionConflictException.of("CONFIG", expectedVersion, now.rowVersion());
    }
    audit.changed(tenantId, actorId, AUDIT_ACTION, OBJECT_TYPE, key, next.id(), next.rowVersion());
    cache.invalidate(tenantId.value());
    return next;
  }

  @Override
  public boolean isEnabled(TenantId tenantId, String flagKey, FlagContext context) {
    Objects.requireNonNull(tenantId, "tenantId");
    FeatureFlag flag = cached(tenantId).get(flagKey);
    FlagContext ctx = context == null ? FlagContext.TENANT : context;
    return flag != null && flag.isOn(clock.instant(), ctx.userId(), ctx.unitAncestry());
  }

  /**
   * Bản đồ cờ hiệu lực của một người dùng ({@code getEffectiveFeatureFlags}, OPS-04): mọi cờ nền
   * tảng của tenant, mỗi cờ tính theo hiệu lực, đơn vị và tỷ lệ bật dần tại cùng một thời điểm.
   */
  public Effective effective(TenantId tenantId, FlagContext context) {
    Objects.requireNonNull(tenantId, "tenantId");
    FlagContext ctx = context == null ? FlagContext.TENANT : context;
    Instant now = clock.instant();
    Map<String, Boolean> result = new LinkedHashMap<>();
    cached(tenantId)
        .forEach((key, flag) -> result.put(key, flag.isOn(now, ctx.userId(), ctx.unitAncestry())));
    return new Effective(Map.copyOf(result), now);
  }

  /** Kết quả {@link #effective}: mã cờ sang bật hay tắt, kèm thời điểm tính. */
  public record Effective(Map<String, Boolean> flags, Instant evaluatedAt) {}

  private Map<String, FeatureFlag> cached(TenantId tenantId) {
    return cache.get(tenantId.value(), t -> reads.execute(s -> merged(tenantId)));
  }

  /** Xoá đệm cờ của tenant (khi pod khác đổi cờ). */
  public void evict(TenantId tenantId) {
    cache.invalidate(tenantId.value());
  }

  private Map<String, FeatureFlag> merged(TenantId tenantId) {
    Map<String, FeatureFlag> result = new LinkedHashMap<>();
    FeatureFlagCatalog.all().forEach(f -> result.put(f.key(), f));
    for (FeatureFlag stored : flags.findAll(tenantId)) {
      FeatureFlag base = result.get(stored.key());
      if (base != null) {
        result.put(stored.key(), withDescription(stored, base));
      }
    }
    return result;
  }

  private static FeatureFlag withDescription(FeatureFlag stored, FeatureFlag base) {
    return new FeatureFlag(
        stored.id(),
        stored.key(),
        base.description(),
        stored.enabled(),
        stored.rollout(),
        stored.validFrom(),
        stored.validTo(),
        stored.rowVersion());
  }
}
