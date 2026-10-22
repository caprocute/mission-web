package vn.viettel.okrkpi.policy.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.policy.application.PolicyResolution.VersionContent;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Dịch vụ ứng dụng của {@code MOD-05 · Policy & Formula} cài đặt cổng {@link PolicyQueryApi}, có bộ
 * đệm trong pod (CORE-05-03): kết quả phân giải theo khoá {@code (tenant, kỳ, luồng, khối, đơn vị)}
 * sống {@link #RESOLUTION_TTL} và bị xoá theo tenant khi một phiên bản đổi trạng thái; nội dung
 * phiên bản theo {@code (tenant, phiên bản)} không hết hạn theo thời gian vì từ {@code
 * DAT_KIEM_THU} nội dung đã đóng băng. Hết hạn theo thời gian là lưới an toàn cho pod khác cho tới
 * khi lõi tiêu thụ EVT-13 (suy luận). Không có {@link PolicyResolution} (kiểm thử đơn vị) thì chỉ
 * còn bảng công thức đăng ký trong bộ nhớ.
 */
@Service
public class DefaultPolicyQueryService implements PolicyQueryApi {

  static final Duration RESOLUTION_TTL = Duration.ofSeconds(60);

  private final PeriodApi periodApi;
  private final PolicyResolution resolution;
  private final Map<PolicyKey, PolicyFormulaView> policies = new ConcurrentHashMap<>();
  private final Cache<ResolveKey, ResolvedPolicyPackView> resolutions =
      Caffeine.newBuilder().maximumSize(100_000).expireAfterWrite(RESOLUTION_TTL).build();
  private final Cache<ContentKey, VersionContent> contents =
      Caffeine.newBuilder().maximumSize(5_000).expireAfterAccess(Duration.ofHours(6)).build();

  public DefaultPolicyQueryService(PeriodApi periodApi) {
    this(periodApi, (PolicyResolution) null);
  }

  @Autowired
  public DefaultPolicyQueryService(
      PeriodApi periodApi, ObjectProvider<PolicyResolution> resolution) {
    this(periodApi, resolution.getIfAvailable());
  }

  DefaultPolicyQueryService(PeriodApi periodApi, PolicyResolution resolution) {
    this.periodApi = Objects.requireNonNull(periodApi, "periodApi must not be null");
    this.resolution = resolution;
  }

  public void registerPolicy(PolicyFormulaView view) {
    Objects.requireNonNull(view, "view must not be null");
    periodApi.assertWritable(view.tenantId(), view.periodId());
    policies.put(new PolicyKey(view.tenantId(), view.periodId(), view.code()), view);
  }

  @Override
  public Optional<PolicyFormulaView> findActivePolicy(
      TenantId tenantId, PeriodId periodId, String code) {
    Objects.requireNonNull(tenantId, "tenantId must not be null");
    Objects.requireNonNull(periodId, "periodId must not be null");
    Objects.requireNonNull(code, "code must not be null");
    return Optional.ofNullable(policies.get(new PolicyKey(tenantId, periodId, code)));
  }

  @Override
  public Optional<ResolvedPolicyPackView> resolve(
      TenantId tenantId, PeriodId periodId, String flow, UUID subjectGroupId, UUID orgUnitId) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(periodId, "periodId");
    Objects.requireNonNull(flow, "flow");
    if (resolution == null) {
      return Optional.empty();
    }
    ResolveKey key = new ResolveKey(tenantId, periodId, flow, subjectGroupId, orgUnitId);
    ResolvedPolicyPackView cached = resolutions.getIfPresent(key);
    if (cached != null) {
      return Optional.of(cached);
    }
    // Không đệm kết quả rỗng: kỳ chưa có gói sẽ có ngay khi gói được phát hành.
    Optional<ResolvedPolicyPackView> found =
        resolution.resolve(tenantId, periodId, flow, subjectGroupId, orgUnitId);
    found.ifPresent(v -> resolutions.put(key, v));
    return found;
  }

  @Override
  public Optional<Object> parameter(TenantId tenantId, UUID policyPackVersionId, String key) {
    Objects.requireNonNull(key, "key");
    return content(tenantId, policyPackVersionId).map(c -> c.parameters().get(key));
  }

  @Override
  public Map<String, Object> parameters(TenantId tenantId, UUID policyPackVersionId) {
    return content(tenantId, policyPackVersionId).map(VersionContent::parameters).orElse(Map.of());
  }

  @Override
  public List<TaskTransitionView> taskLifecycle(TenantId tenantId, UUID policyPackVersionId) {
    return content(tenantId, policyPackVersionId).map(VersionContent::lifecycle).orElse(List.of());
  }

  @Override
  public PolicyRulesView rules(TenantId tenantId, UUID policyPackVersionId) {
    return content(tenantId, policyPackVersionId)
        .map(VersionContent::rules)
        .orElseGet(() -> new PolicyRulesView(policyPackVersionId, List.of(), List.of()));
  }

  /** Nội dung phiên bản qua bộ đệm (dùng chung cho hai cổng của MOD-08). */
  public Optional<VersionContent> content(TenantId tenantId, UUID policyPackVersionId) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(policyPackVersionId, "policyPackVersionId");
    if (resolution == null) {
      return Optional.empty();
    }
    ContentKey key = new ContentKey(tenantId, policyPackVersionId);
    VersionContent cached = contents.getIfPresent(key);
    if (cached != null) {
      return Optional.of(cached);
    }
    Optional<VersionContent> loaded = resolution.content(tenantId, policyPackVersionId);
    loaded.ifPresent(c -> contents.put(key, c));
    return loaded;
  }

  /** Vô hiệu sau khi giao dịch đổi trạng thái ghi xong; chạy cả khi phát ngoài giao dịch. */
  @TransactionalEventListener(fallbackExecution = true)
  public void onStatusChanged(PolicyVersionStatusChanged event) {
    invalidate(event.tenantId());
    contents.invalidate(new ContentKey(event.tenantId(), event.versionId()));
  }

  /** Xoá kết quả phân giải đã đệm của tenant. */
  public void invalidate(TenantId tenantId) {
    resolutions.asMap().keySet().removeIf(k -> k.tenantId().equals(tenantId));
  }

  private record PolicyKey(TenantId tenantId, PeriodId periodId, String code) {}

  private record ResolveKey(
      TenantId tenantId, PeriodId periodId, String flow, UUID subjectGroupId, UUID orgUnitId) {}

  private record ContentKey(TenantId tenantId, UUID versionId) {}
}
