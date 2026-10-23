package vn.viettel.okrkpi.policy.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.DecisionTableView;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.PolicyContent;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.ResolvedPack;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.RuleView;
import vn.viettel.okrkpi.policy.application.port.PolicyPackRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyResolutionRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyResolutionRepository.PublishedVersion;
import vn.viettel.okrkpi.policy.domain.EffectiveRange;
import vn.viettel.okrkpi.policy.domain.PackCandidate;
import vn.viettel.okrkpi.policy.domain.PackOrigin;
import vn.viettel.okrkpi.policy.domain.PackSelector;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Phân giải Policy Pack theo tenant, kỳ, luồng, khối, đơn vị (CORE-05-03, BR-08-07, X-PP-10) và đọc
 * tham số, vòng đời nhiệm vụ, quy tắc của phiên bản cho các mô-đun lõi. Kết quả phân giải đệm trong
 * pod theo khoá phân giải; nội dung đệm theo phiên bản chỉ khi phiên bản đã đóng băng. Phát hành
 * hoặc thu hồi gọi {@link #evict(TenantId)}.
 */
public class PolicyResolutionService {

  public static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);
  static final List<String> FLOWS = List.of("DANG", "NHA_NUOC");

  private record ResolveKey(
      TenantId tenant, UUID periodId, String flow, UUID subjectGroupId, UUID orgUnitId) {}

  private record VersionKey(TenantId tenant, UUID versionId) {}

  private record Snapshot(PublishedVersion version, Map<String, Object> manifest) {}

  private final PolicyResolutionRepository resolutions;
  private final PolicyPackRepository packs;
  private final Supplier<PeriodApi> periods;
  private final ObjectMapper json;
  private final Cache<ResolveKey, ResolvedPack> resolved;
  private final Cache<VersionKey, Snapshot> snapshots;
  private final Cache<VersionKey, PolicyContent> contents;

  public PolicyResolutionService(
      PolicyResolutionRepository resolutions,
      PolicyPackRepository packs,
      Supplier<PeriodApi> periods,
      ObjectMapper json,
      Duration ttl) {
    this.resolutions = Objects.requireNonNull(resolutions, "resolutions");
    this.packs = Objects.requireNonNull(packs, "packs");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.json = Objects.requireNonNull(json, "json");
    this.resolved = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(100_000).build();
    this.snapshots = Caffeine.newBuilder().expireAfterAccess(ttl).maximumSize(2_000).build();
    this.contents = Caffeine.newBuilder().expireAfterAccess(ttl).maximumSize(2_000).build();
  }

  /**
   * Gắn khớp cụ thể nhất; chưa có gắn thì chọn theo khoảng hiệu lực và ghim gắn toàn tenant cho kỳ
   * (BR-08-07). Rỗng khi kỳ không có hoặc không gói nào khớp ({@code POLICY-404-NO-BINDING}).
   */
  @Transactional(PolicyPackService.TX)
  public Optional<ResolvedPack> resolve(
      TenantId tenant, PeriodId periodId, String flow, UUID subjectGroupId, UUID orgUnitId) {
    ResolveKey key = new ResolveKey(tenant, periodId.value(), flow, subjectGroupId, orgUnitId);
    ResolvedPack hit = resolved.getIfPresent(key);
    if (hit != null) {
      return Optional.of(hit);
    }
    Optional<ResolvedPack> found =
        resolutions.matchBinding(tenant, periodId.value(), flow, subjectGroupId, orgUnitId);
    if (found.isEmpty() && pinFromRange(tenant, periodId, flow)) {
      found = resolutions.matchBinding(tenant, periodId.value(), flow, subjectGroupId, orgUnitId);
    }
    found.ifPresent(r -> resolved.put(key, r));
    return found;
  }

  /** Gói chọn theo khoảng hiệu lực cho kỳ bắt đầu ngày {@code periodStart} (X-PP-10). */
  @Transactional(transactionManager = PolicyPackService.TX, readOnly = true)
  public Optional<PackCandidate> selectByRange(
      TenantId tenant, String flow, LocalDate periodStart) {
    List<PackCandidate> candidates =
        resolutions.publishedVersions(tenant, flow).stream()
            .map(v -> candidate(tenant, v))
            .toList();
    return PackSelector.select(candidates, flow, periodStart);
  }

  /**
   * Ghim phiên bản hiệu lực cho kỳ vừa tạo (sự kiện "kỳ đã tạo" của MOD-08); kỳ {@code CA_HAI} ghim
   * riêng cho từng luồng. Trả số luồng đã ghim được.
   */
  @Transactional(PolicyPackService.TX)
  public int pinForPeriod(TenantId tenant, PeriodId periodId, String flow, LocalDate startDate) {
    int pinned = 0;
    for (String f : "CA_HAI".equals(flow) ? FLOWS : List.of(flow)) {
      Optional<PackCandidate> chosen = selectByRange(tenant, f, startDate);
      if (chosen.isPresent()) {
        resolutions.pinBinding(
            tenant,
            Ids.newUuidV7(),
            chosen.get().versionId(),
            periodId.value(),
            f,
            false,
            SYSTEM_ACTOR);
        pinned++;
      }
    }
    if (pinned > 0) {
      evict(tenant);
    }
    return pinned;
  }

  /** Giá trị mặc định ({@code mac_dinh}) của tham số {@code key} trong {@code params}. */
  @Transactional(transactionManager = PolicyPackService.TX, readOnly = true)
  public Optional<Object> parameter(TenantId tenant, UUID versionId, String key) {
    return snapshot(tenant, versionId)
        .map(s -> s.manifest().get("params"))
        .filter(Map.class::isInstance)
        .map(p -> ((Map<?, ?>) p).get(key))
        .map(v -> v instanceof Map<?, ?> m && m.containsKey("mac_dinh") ? m.get("mac_dinh") : v);
  }

  /** Khai báo {@code inputs} của manifest, dạng ánh xạ tên → khai báo hoặc dạng mảng. */
  @Transactional(transactionManager = PolicyPackService.TX, readOnly = true)
  public List<PolicyQueryApi.InputDeclaration> inputs(TenantId tenant, UUID versionId) {
    Object raw = snapshot(tenant, versionId).map(s -> s.manifest().get("inputs")).orElse(null);
    List<PolicyQueryApi.InputDeclaration> out = new java.util.ArrayList<>();
    if (raw instanceof Map<?, ?> map) {
      map.forEach((k, v) -> out.add(declaration(String.valueOf(k), v)));
    } else if (raw instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof Map<?, ?> m && m.get("name") != null) {
          out.add(declaration(String.valueOf(m.get("name")), m));
        }
      }
    }
    return List.copyOf(out);
  }

  private static PolicyQueryApi.InputDeclaration declaration(String name, Object decl) {
    if (decl instanceof Map<?, ?> m) {
      return new PolicyQueryApi.InputDeclaration(
          name,
          m.get("type") instanceof String t ? t : null,
          m.get("source") instanceof String src ? src : null);
    }
    return new PolicyQueryApi.InputDeclaration(name, decl instanceof String t ? t : null, null);
  }

  /**
   * Bảng {@code task_lifecycle}: khối trong manifest nếu khai báo trực tiếp, nếu không thì tham số
   * của quy tắc khoá {@code task_lifecycle}.
   */
  @Transactional(transactionManager = PolicyPackService.TX, readOnly = true)
  public Optional<Map<String, Object>> taskLifecycle(TenantId tenant, UUID versionId) {
    Optional<Snapshot> s = snapshot(tenant, versionId);
    if (s.isEmpty()) {
      return Optional.empty();
    }
    if (s.get().manifest().get("task_lifecycle") instanceof Map<?, ?> inline
        && inline.keySet().stream().anyMatch(k -> !"tep".equals(k))) {
      return Optional.of(cast(inline));
    }
    return content(tenant, versionId).stream()
        .flatMap(c -> c.rules().stream())
        .filter(r -> "task_lifecycle".equals(r.ruleKey()))
        .findFirst()
        .map(r -> read(r.paramsJson()));
  }

  @Transactional(transactionManager = PolicyPackService.TX, readOnly = true)
  public Optional<PolicyContent> content(TenantId tenant, UUID versionId) {
    VersionKey key = new VersionKey(tenant, versionId);
    PolicyContent hit = contents.getIfPresent(key);
    if (hit != null) {
      return Optional.of(hit);
    }
    Optional<PublishedVersion> v = resolutions.version(tenant, versionId);
    if (v.isEmpty()) {
      return Optional.empty();
    }
    PolicyContent content =
        new PolicyContent(
            versionId,
            v.get().version(),
            v.get().contentHash(),
            packs.rules(tenant, versionId).stream()
                .map(
                    r ->
                        new RuleView(
                            r.ruleKey(),
                            r.expression(),
                            r.resultType(),
                            r.roundingJson(),
                            r.legalRef(),
                            r.evalOrder(),
                            r.paramsJson(),
                            r.overridable()))
                .toList(),
            packs.decisionTables(tenant, versionId).stream()
                .map(
                    t ->
                        new DecisionTableView(
                            t.tableKey(),
                            t.hitPolicy(),
                            t.dmnXml(),
                            t.rulesJson(),
                            t.explanationTemplatesJson(),
                            t.legalRef()))
                .toList());
    if (v.get().status().frozen()) {
      contents.put(key, content);
    }
    return Optional.of(content);
  }

  /** Phiên bản theo mã gói và số phiên bản, kèm manifest (cổng schema của MOD-08). */
  @Transactional(transactionManager = PolicyPackService.TX, readOnly = true)
  public Optional<Map<String, Object>> manifestByCode(
      TenantId tenant, String code, String version) {
    return resolutions.versionByCode(tenant, code, version).map(v -> read(v.manifestJson()));
  }

  @Transactional(transactionManager = PolicyPackService.TX, readOnly = true)
  public Optional<PublishedVersion> versionByCode(TenantId tenant, String code, String version) {
    return resolutions.versionByCode(tenant, code, version);
  }

  @Transactional(transactionManager = PolicyPackService.TX, readOnly = true)
  public Optional<Map<String, Object>> manifest(TenantId tenant, UUID versionId) {
    return snapshot(tenant, versionId).map(Snapshot::manifest);
  }

  /** Bỏ mọi kết quả phân giải đã đệm của tenant (phát hành, thu hồi, ghim gắn mới). */
  public void evict(TenantId tenant) {
    resolved.asMap().keySet().removeIf(k -> k.tenant().equals(tenant));
    snapshots.asMap().keySet().removeIf(k -> k.tenant().equals(tenant));
    contents.asMap().keySet().removeIf(k -> k.tenant().equals(tenant));
  }

  private boolean pinFromRange(TenantId tenant, PeriodId periodId, String flow) {
    Optional<PeriodSnapshot> period = periods.get().findById(tenant, periodId);
    if (period.isEmpty()) {
      return false;
    }
    Optional<PackCandidate> chosen = selectByRange(tenant, flow, period.get().startDate());
    if (chosen.isEmpty()) {
      return false;
    }
    resolutions.pinBinding(
        tenant,
        Ids.newUuidV7(),
        chosen.get().versionId(),
        periodId.value(),
        flow,
        period.get().status() == PeriodStatus.DA_KHOA,
        SYSTEM_ACTOR);
    return true;
  }

  private Optional<Snapshot> snapshot(TenantId tenant, UUID versionId) {
    VersionKey key = new VersionKey(tenant, versionId);
    Snapshot hit = snapshots.getIfPresent(key);
    if (hit != null) {
      return Optional.of(hit);
    }
    Optional<Snapshot> loaded =
        resolutions.version(tenant, versionId).map(v -> new Snapshot(v, read(v.manifestJson())));
    loaded.filter(s -> s.version().status().frozen()).ifPresent(s -> snapshots.put(key, s));
    return loaded;
  }

  private PackCandidate candidate(TenantId tenant, PublishedVersion v) {
    Map<String, Object> manifest = snapshotOf(tenant, v);
    Object period =
        manifest.get("metadata") instanceof Map<?, ?> meta ? meta.get("hieu_luc") : null;
    Object fromKy = period instanceof Map<?, ?> p ? p.get("tu_ky") : null;
    Object toKy = period instanceof Map<?, ?> p ? p.get("den_ky") : null;
    LocalDate from =
        v.effectiveFrom() != null
            ? v.effectiveFrom()
            : EffectiveRange.periodBound(fromKy, false).orElse(null);
    return new PackCandidate(
        v.packId(),
        v.code(),
        PackOrigin.valueOf(v.origin()),
        v.flow(),
        v.versionId(),
        v.version(),
        v.contentHash(),
        v.bundleObjectKey(),
        new EffectiveRange(from, EffectiveRange.periodBound(toKy, true).orElse(null)));
  }

  private Map<String, Object> snapshotOf(TenantId tenant, PublishedVersion v) {
    VersionKey key = new VersionKey(tenant, v.versionId());
    Snapshot hit = snapshots.getIfPresent(key);
    if (hit != null) {
      return hit.manifest();
    }
    Snapshot s = new Snapshot(v, read(v.manifestJson()));
    snapshots.put(key, s);
    return s.manifest();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> cast(Map<?, ?> map) {
    return (Map<String, Object>) map;
  }

  private Map<String, Object> read(String text) {
    try {
      return json.readValue(
          text == null ? "{}" : text, new TypeReference<Map<String, Object>>() {});
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Manifest của phiên bản không đọc được", e);
    }
  }
}
