package vn.viettel.okrkpi.scoring.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.viettel.okrkpi.scoring.application.policy.PolicyPackCache;
import vn.viettel.okrkpi.scoring.application.port.OpenPeriods;
import vn.viettel.okrkpi.scoring.application.port.ScoreJobStore;
import vn.viettel.okrkpi.scoring.application.port.StoredScores;
import vn.viettel.okrkpi.scoring.domain.JobScope;
import vn.viettel.okrkpi.scoring.domain.OpenPeriod;
import vn.viettel.okrkpi.scoring.domain.PolicyBindingKey;
import vn.viettel.okrkpi.scoring.domain.ScoreJob;
import vn.viettel.okrkpi.scoring.domain.ScoreJobKind;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.Ids;

/**
 * Tính dần theo sự kiện (DV-07-04, TL-01 "SVC-07 scoring-service"): xếp tác vụ cho worker ({@link
 * ScoreJobWorker}) thay vì tính ngay trong consumer, để consumer giữ giao dịch ngắn (inbox và tác
 * dụng cùng giao dịch). EVT-06 và EVT-08 gộp vào một tác vụ {@code TAM_TINH} đang chờ của kỳ (tạo
 * hoặc cập nhật phạm vi hồ sơ), nên nhiều sự kiện dồn dập chỉ sinh một lượt đọc đầu vào; hồ sơ có
 * đầu vào không đổi không ghi và không phát EVT-10 ({@link ScoreRecorder}). EVT-09 mở tác vụ {@code
 * CHOT_KY} cho cả kỳ; EVT-22 mở {@code TINH_LAI_HAU_KIEM} chỉ cho phạm vi mở lại, dùng phiên bản
 * gói gốc (BR-13-06). Bên gọi đặt ngữ cảnh tenant và mở giao dịch.
 */
public class IncrementalScoring {

  /** Thứ tự luồng thử ghim gói khi chốt kỳ không mang phiên bản đóng băng. */
  private static final List<String> LOCK_FLOWS = List.of("DANG", "NHA_NUOC");

  private static final String NO_BINDING = "POLICY-404-NO-BINDING";

  private static final Logger LOG = LoggerFactory.getLogger(IncrementalScoring.class);

  /** Lý do của tác vụ tính dần theo sự kiện nhiệm vụ, minh chứng (khoá gộp phạm vi). */
  public static final String REASON_EVENT = "SU_KIEN_NHIEM_VU";

  static final String REASON_LOCK = "KHOA_KY";
  static final String REASON_REOPEN = "MO_LAI_HAU_KIEM";

  /** Tài khoản hệ thống ghi {@code created_by} của tác vụ do sự kiện mở. */
  static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

  private final StoredScores stored;
  private final OpenPeriods periods;
  private final ScoreJobStore jobs;
  private final PolicyPackCache policies;

  public IncrementalScoring(
      StoredScores stored, OpenPeriods periods, ScoreJobStore jobs, PolicyPackCache policies) {
    this.stored = Objects.requireNonNull(stored, "stored");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.jobs = Objects.requireNonNull(jobs, "jobs");
    this.policies = Objects.requireNonNull(policies, "policies");
  }

  /**
   * EVT-06 (nhiệm vụ đổi trạng thái) hoặc EVT-08 (minh chứng đã xác nhận) của nhiệm vụ {@code
   * taskId}. Hồ sơ bị ảnh hưởng là hồ sơ có nhiệm vụ trong tập đầu vào đã tính ở kỳ chưa chốt;
   * nhiệm vụ chưa có trong kết quả nào (ví dụ vừa giao) thì xếp tính cả các kỳ đang mở, vì API đầu
   * vào của lõi chưa lọc được theo nhiệm vụ (suy luận, wiki trang 14).
   *
   * @return tác vụ đã tạo hoặc gộp
   */
  public List<UUID> onTaskChanged(UUID tenantId, UUID taskId) {
    Map<UUID, Target> targets = new LinkedHashMap<>();
    for (StoredScores.Affected a : stored.withTask(taskId)) {
      targets
          .computeIfAbsent(a.periodId(), p -> new Target(p, a.periodBucket(), false))
          .add(a.profileId(), a.flow());
    }
    if (targets.isEmpty()) {
      for (OpenPeriod p : periods.of(tenantId)) {
        targets
            .computeIfAbsent(p.periodId(), id -> new Target(id, p.periodBucket(), true))
            .flows
            .add(p.flow());
      }
    }
    List<UUID> queued = new ArrayList<>();
    for (Target t : targets.values()) {
      queue(tenantId, t).ifPresent(queued::add);
    }
    return queued;
  }

  private Optional<UUID> queue(UUID tenantId, Target t) {
    Optional<ScoreJobStore.Pending> pending =
        jobs.pendingIncremental(tenantId, t.bucket, t.periodId, REASON_EVENT);
    if (pending.isPresent()) {
      JobScope scope = JobScope.parse(pending.get().scope());
      JobScope merged =
          t.wholePeriod ? scope.withProfiles(Set.of()) : scope.mergeProfiles(t.profiles);
      if (!merged.toDoc().equals(scope.toDoc())) {
        jobs.updateScope(tenantId, t.bucket, pending.get().id(), merged.toDoc(), SYSTEM_ACTOR);
      }
      return Optional.of(pending.get().id());
    }
    Optional<UUID> version = pinnedVersion(tenantId, t.periodId, t.flows);
    if (version.isEmpty()) {
      LOG.info("Kỳ {} chưa có phiên bản quy tắc ghim; bỏ qua tính dần", t.periodId);
      return Optional.empty();
    }
    JobScope scope =
        t.wholePeriod
            ? JobScope.of(REASON_EVENT)
            : JobScope.of(REASON_EVENT).withProfiles(t.profiles);
    return Optional.of(
        jobs.open(
            new ScoreJob(
                tenantId,
                Ids.newUuidV7(),
                t.periodId,
                t.bucket,
                ScoreJobKind.TAM_TINH,
                scope.toDoc(),
                version.get(),
                null,
                0,
                SYSTEM_ACTOR)));
  }

  /**
   * EVT-09 kỳ đã khoá: tác vụ {@code CHOT_KY} tính cả kỳ trên ảnh chụp đóng băng bằng phiên bản
   * ghim của từng hồ sơ (BR-08-07). Sự kiện trùng không tạo tác vụ thứ hai ({@code
   * trigger_event_id}).
   */
  public UUID onPeriodLocked(
      UUID tenantId, UUID eventId, UUID periodId, int periodBucket, List<UUID> frozenVersions) {
    UUID version =
        frozenVersions.isEmpty() ? pinAnyFlow(tenantId, periodId) : frozenVersions.get(0);
    return jobs.open(
        new ScoreJob(
            tenantId,
            Ids.newUuidV7(),
            periodId,
            periodBucket,
            ScoreJobKind.CHOT_KY,
            JobScope.of(REASON_LOCK).toDoc(),
            version,
            eventId,
            0,
            SYSTEM_ACTOR));
  }

  /**
   * Phiên bản ghim cho kỳ khi EVT-09 không mang phiên bản đóng băng: thử luồng Đảng rồi luồng Nhà
   * nước, vì kỳ {@code CA_HAI} có thể mới có gói của một luồng. Trước đây chỉ thử luồng Đảng nên kỳ
   * chỉ có gói Nhà nước nhận {@code POLICY-404-NO-BINDING}, sự kiện vào hàng thư chết và hồ sơ đứng
   * mãi ở {@code CHUA_LAP}.
   */
  private UUID pinAnyFlow(UUID tenantId, UUID periodId) {
    BusinessException last = null;
    for (String flow : LOCK_FLOWS) {
      try {
        return policies.pin(PolicyBindingKey.of(tenantId, periodId, flow)).versionId();
      } catch (BusinessException e) {
        if (!NO_BINDING.equals(e.getMessage())) {
          throw e;
        }
        last = e;
      }
    }
    throw last;
  }

  /**
   * EVT-22 kỳ mở lại hậu kiểm: tác vụ {@code TINH_LAI_HAU_KIEM} chỉ cho hồ sơ hoặc đơn vị trong
   * phạm vi, bằng phiên bản gói của kết quả gốc; kết quả cũ giữ nguyên vì kết quả chỉ ghi thêm
   * (BR-13-06).
   *
   * @param scopeType {@code TOAN_KY}, {@code DON_VI} hoặc {@code HO_SO}
   * @return trống khi kỳ chưa có kết quả nào để tính lại
   */
  public Optional<UUID> onPeriodReopened(
      UUID tenantId, UUID eventId, UUID periodId, String scopeType, List<UUID> scopeIds) {
    Optional<Integer> bucket = stored.bucketOf(periodId);
    if (bucket.isEmpty()) {
      return Optional.empty();
    }
    List<StoredScores.Stored> originals = stored.originals(periodId, bucket.get());
    if (originals.isEmpty()) {
      return Optional.empty();
    }
    Map<String, String> versions = new LinkedHashMap<>();
    for (StoredScores.Stored s : originals) {
      versions.put(s.flow(), s.policyPackVersionId() + ":" + s.policyContentHash());
    }
    JobScope scope = JobScope.of(REASON_REOPEN).withPolicyHashes(versions);
    switch (scopeType) {
      case "TOAN_KY" -> {}
      case "DON_VI" -> scope = scope.withUnits(scopeIds);
      case "HO_SO" -> scope = scope.withProfiles(scopeIds);
      default -> throw new IllegalArgumentException("Phạm vi mở lại không hợp lệ: " + scopeType);
    }
    if (!"TOAN_KY".equals(scopeType) && scopeIds.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        jobs.open(
            new ScoreJob(
                tenantId,
                Ids.newUuidV7(),
                periodId,
                bucket.get(),
                ScoreJobKind.TINH_LAI_HAU_KIEM,
                scope.toDoc(),
                originals.get(0).policyPackVersionId(),
                eventId,
                0,
                SYSTEM_ACTOR)));
  }

  private Optional<UUID> pinnedVersion(UUID tenantId, UUID periodId, Set<String> flows) {
    List<String> order = new ArrayList<>(flows);
    if (order.isEmpty()) {
      order.addAll(List.of("DANG", "NHA_NUOC"));
    }
    for (String flow : order) {
      try {
        return Optional.of(policies.pin(PolicyBindingKey.of(tenantId, periodId, flow)).versionId());
      } catch (RuntimeException noBinding) {
        // Thử luồng còn lại.
      }
    }
    return Optional.empty();
  }

  /** Kỳ cần tính dần và các hồ sơ bị ảnh hưởng. */
  private static final class Target {
    final UUID periodId;
    final int bucket;
    final boolean wholePeriod;
    final Set<UUID> profiles = new LinkedHashSet<>();
    final Set<String> flows = new LinkedHashSet<>();

    Target(UUID periodId, int bucket, boolean wholePeriod) {
      this.periodId = periodId;
      this.bucket = bucket;
      this.wholePeriod = wholePeriod;
    }

    void add(UUID profileId, String flow) {
      profiles.add(profileId);
      flows.add(flow);
    }
  }
}
