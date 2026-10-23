package vn.viettel.okrkpi.okr.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.okr.api.OkrApi;
import vn.viettel.okrkpi.okr.application.OkrReadService.KeyResultView;
import vn.viettel.okrkpi.okr.application.port.OkrReadRepository.KeyResultFilter;
import vn.viettel.okrkpi.okr.application.port.OkrReadRepository.ObjectiveRow;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Dịch vụ ứng dụng của {@code MOD-01 · OKR Engine} cài đặt cổng {@link OkrApi} và sử dụng {@link
 * PeriodApi} của {@code MOD-08 (config)}. Dữ liệu đọc từ schema {@code okr} qua {@link
 * OkrReadService} (CORE-01-02); bản đăng ký trong bộ nhớ giữ cho kiểm thử của module khác.
 */
@Service
public class OkrQueryService implements OkrApi {

  private final PeriodApi periodApi;
  private final ObjectProvider<OkrReadService> reads;
  private final ObjectProvider<OkrMilestoneService> milestoneService;
  private final Map<UUID, KeyResultSummary> summaries = new ConcurrentHashMap<>();

  public OkrQueryService(PeriodApi periodApi) {
    this(periodApi, null);
  }

  public OkrQueryService(PeriodApi periodApi, ObjectProvider<OkrReadService> reads) {
    this(periodApi, reads, null);
  }

  @Autowired
  public OkrQueryService(
      PeriodApi periodApi,
      ObjectProvider<OkrReadService> reads,
      ObjectProvider<OkrMilestoneService> milestoneService) {
    this.periodApi = Objects.requireNonNull(periodApi, "periodApi must not be null");
    this.reads = reads;
    this.milestoneService = milestoneService;
  }

  @Override
  public List<MilestoneSnapshot> listMilestones(TenantId tenantId, UUID keyResultId) {
    OkrMilestoneService service =
        milestoneService == null ? null : milestoneService.getIfAvailable();
    if (service == null) {
      return List.of();
    }
    return service.milestones(tenantId, keyResultId).stream()
        .map(
            v ->
                new MilestoneSnapshot(
                    v.row().seq(),
                    v.row().dueDate(),
                    v.row().recognitionCondition(),
                    v.row().achievedAt(),
                    v.row().evidenceRef(),
                    v.lateWorkingDays()))
        .toList();
  }

  public void registerKeyResult(KeyResultSummary summary) {
    Objects.requireNonNull(summary, "summary must not be null");
    periodApi.assertWritable(summary.tenantId(), summary.periodId());
    summaries.put(summary.id(), summary);
  }

  @Override
  public Optional<KeyResultSummary> findKeyResultById(TenantId tenantId, UUID keyResultId) {
    Objects.requireNonNull(tenantId, "tenantId must not be null");
    Objects.requireNonNull(keyResultId, "keyResultId must not be null");
    Optional<KeyResultSummary> registered =
        Optional.ofNullable(summaries.get(keyResultId))
            .filter(kr -> kr.tenantId().equals(tenantId));
    if (registered.isPresent()) {
      return registered;
    }
    return findKeyResult(tenantId, keyResultId)
        .map(
            k ->
                new KeyResultSummary(
                    k.id(),
                    k.tenantId(),
                    k.periodId(),
                    k.title(),
                    k.weight() == null ? 0 : k.weight().doubleValue(),
                    k.progress().movePointRight(2).doubleValue()));
  }

  @Override
  public Optional<KeyResultSnapshot> findKeyResult(TenantId tenantId, UUID keyResultId) {
    Objects.requireNonNull(tenantId, "tenantId must not be null");
    Objects.requireNonNull(keyResultId, "keyResultId must not be null");
    OkrReadService read = read();
    if (read == null) {
      return Optional.empty();
    }
    return read.keyResult(tenantId, keyResultId)
        .flatMap(
            k ->
                read.objectiveRow(tenantId, k.row().objectiveId())
                    .map(o -> snapshot(tenantId, k, o)));
  }

  @Override
  public List<KeyResultSnapshot> listApprovedKeyResults(
      TenantId tenantId, PeriodId periodId, UUID unitId) {
    Objects.requireNonNull(unitId, "unitId must not be null");
    return approvedKeyResults(tenantId, periodId, List.of(unitId), null);
  }

  @Override
  public List<KeyResultSnapshot> listResponsibleKeyResults(
      TenantId tenantId, PeriodId periodId, UUID personId) {
    Objects.requireNonNull(personId, "personId must not be null");
    // Bộ lọc người sở hữu của cổng đọc gồm cả người được giao check-in thay; phạm vi phụ trách chỉ
    // giữ KR của mục tiêu cá nhân của chính người đó hoặc mục tiêu tập thể do người đó soạn.
    return approvedKeyResults(tenantId, periodId, null, personId).stream()
        .filter(k -> k.ownerPersonId() == null || personId.equals(k.ownerPersonId()))
        .toList();
  }

  private List<KeyResultSnapshot> approvedKeyResults(
      TenantId tenantId, PeriodId periodId, List<UUID> unitIds, UUID ownerPersonId) {
    Objects.requireNonNull(tenantId, "tenantId must not be null");
    Objects.requireNonNull(periodId, "periodId must not be null");
    OkrReadService read = read();
    if (read == null) {
      return List.of();
    }
    Optional<PeriodSnapshot> period = periodApi.findById(tenantId, periodId);
    if (period.isEmpty()) {
      return List.of();
    }
    KeyResultFilter filter =
        new KeyResultFilter(
            periodId.value(),
            OkrReadService.buckets(period.get()),
            unitIds,
            ownerPersonId,
            null,
            null,
            true,
            false,
            null);
    List<KeyResultView> all = new java.util.ArrayList<>();
    String afterCode = null;
    UUID afterId = null;
    while (true) {
      List<KeyResultView> page = read.keyResults(tenantId, filter, afterCode, afterId, 200);
      all.addAll(page);
      if (page.size() < 200) {
        break;
      }
      afterCode = page.getLast().row().code();
      afterId = page.getLast().row().id();
    }
    Map<UUID, ObjectiveRow> objectives = new java.util.HashMap<>();
    return all.stream()
        .map(
            k ->
                snapshot(
                    tenantId,
                    k,
                    objectives.computeIfAbsent(
                        k.row().objectiveId(),
                        id -> read.objectiveRow(tenantId, id).orElseThrow())))
        .toList();
  }

  private OkrReadService read() {
    return reads == null ? null : reads.getIfAvailable();
  }

  private static KeyResultSnapshot snapshot(TenantId tenant, KeyResultView k, ObjectiveRow o) {
    BigDecimal weight = k.row().weight();
    return new KeyResultSnapshot(
        k.row().id(),
        tenant,
        PeriodId.of(k.row().periodId()),
        o.id(),
        o.status(),
        o.ownerType(),
        o.ownerUnitId(),
        o.ownerPersonId(),
        k.row().code(),
        k.row().title(),
        weight,
        k.progress(),
        k.row().dueDate(),
        k.row().riskFlag());
  }
}
