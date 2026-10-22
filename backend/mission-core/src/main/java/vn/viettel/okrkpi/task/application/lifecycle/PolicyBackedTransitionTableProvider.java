package vn.viettel.okrkpi.task.application.lifecycle;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.ResolvedPack;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.lifecycle.port.TransitionTableProvider;
import vn.viettel.okrkpi.task.domain.lifecycle.Transition;
import vn.viettel.okrkpi.task.domain.lifecycle.TransitionTable;
import vn.viettel.okrkpi.task.domain.lifecycle.TransitionTables;

/**
 * Bảng chuyển từ Policy Pack khoá {@code task_lifecycle} (ADR-04): phân giải gói đã ghim cho kỳ qua
 * {@link PolicyQueryApi#resolve}, đọc bảng của đúng phiên bản đó, nên phát hành phiên bản mới không
 * đổi bảng của kỳ đã ghim. Bảng đệm theo (tenant, phiên bản gói) vì phiên bản đã phát hành là bất
 * biến; mọi guard CEL được biên dịch khi nạp. Tenant hoặc kỳ chưa có gói dùng {@link
 * TransitionTables#defaults()}.
 *
 * <p>Nhiệm vụ không mang luồng, nên dò lần lượt luồng {@code NHA_NUOC} rồi {@code DANG}; hai gói
 * mẫu dùng chung một bảng (DL-04) (suy luận).
 */
public class PolicyBackedTransitionTableProvider implements TransitionTableProvider {

  static final List<String> FLOWS = List.of("NHA_NUOC", "DANG");

  private record VersionKey(UUID tenant, UUID versionId) {}

  private record PeriodKey(UUID tenant, UUID periodId) {}

  private final PolicyQueryApi policies;
  private final CelGuardEvaluator guards;
  private final Cache<VersionKey, TransitionTable> tables;
  private final Cache<PeriodKey, Optional<ResolvedPack>> pins;
  private final TransitionTable defaults;

  public PolicyBackedTransitionTableProvider(
      PolicyQueryApi policies, CelGuardEvaluator guards, Duration pinTtl) {
    this.policies = Objects.requireNonNull(policies, "policies");
    this.guards = Objects.requireNonNull(guards, "guards");
    this.tables = Caffeine.newBuilder().maximumSize(10_000).build();
    this.pins = Caffeine.newBuilder().expireAfterWrite(pinTtl).maximumSize(100_000).build();
    this.defaults = compiled(TransitionTables.defaults());
  }

  @Override
  public TransitionTable tableFor(TenantId tenant, UUID periodId) {
    Optional<ResolvedPack> pack =
        pins.get(new PeriodKey(tenant.value(), periodId), k -> resolve(tenant, periodId));
    if (pack.isEmpty()) {
      return defaults;
    }
    ResolvedPack p = pack.get();
    return tables.get(
        new VersionKey(tenant.value(), p.versionId()),
        k ->
            policies
                .taskLifecycle(tenant, p.versionId())
                .map(m -> compiled(TransitionTables.fromPolicy(m, version(p))))
                .orElse(defaults));
  }

  private Optional<ResolvedPack> resolve(TenantId tenant, UUID periodId) {
    for (String flow : FLOWS) {
      Optional<ResolvedPack> p = policies.resolve(tenant, PeriodId.of(periodId), flow, null, null);
      if (p.isPresent()) {
        return p;
      }
    }
    return Optional.empty();
  }

  /** {@code mã-gói@phiên-bản}, vừa cột {@code state_machine_version} varchar(32). */
  static String version(ResolvedPack p) {
    String v = p.code() + "@" + p.version();
    return v.length() <= 32 ? v : v.substring(v.length() - 32);
  }

  private TransitionTable compiled(TransitionTable table) {
    for (Transition t : table.transitions()) {
      if (t.guardCel() != null) {
        guards.compile(t.guardCel());
      }
    }
    return table;
  }
}
