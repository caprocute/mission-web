package vn.viettel.okrkpi.appraisal.application.setup;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.appraisal.api.AppraisalSpecialCases;
import vn.viettel.okrkpi.appraisal.api.AppraisalSpecialCases.SpecialCase;
import vn.viettel.okrkpi.appraisal.api.AppraisalSpecialCases.Treatment;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualRatingRepository;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualRatingRepository.QuarterRef;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualRecomputeRequests;
import vn.viettel.okrkpi.appraisal.application.profile.AppraisalProfileService;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.ProfileRow;
import vn.viettel.okrkpi.appraisal.application.setup.port.ProfileSetupRepository;
import vn.viettel.okrkpi.appraisal.application.setup.port.ProfileSetupRepository.NewSnapshot;
import vn.viettel.okrkpi.appraisal.application.setup.port.ProfileSetupRepository.PeriodLock;
import vn.viettel.okrkpi.appraisal.application.setup.port.ProfileSetupRepository.SnapshotHead;
import vn.viettel.okrkpi.appraisal.application.setup.port.ProfileSetupRepository.UnitGroup;
import vn.viettel.okrkpi.appraisal.application.setup.port.SetupNotices;
import vn.viettel.okrkpi.appraisal.domain.profile.AppraisalStatus;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.Jcs;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Điểm vào của QT-09 (CORE-04-02): EVT-09 lập hồ sơ quý {@code CHUA_LAP} theo tổ chức tại ngày khoá
 * kỳ; EVT-10 lưu ảnh chụp điểm và mở phiếu tự đánh giá. Không có API ghi ảnh chụp (BR-09-16).
 */
public class ProfileSetupService {

  /** Người thực hiện của các bước do hệ thống chạy. */
  public static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

  public static final List<String> FLOWS = List.of("DANG", "NHA_NUOC");

  static final String TX = "appraisalTransactionManager";
  static final String TAM_TINH = "TAM_TINH";

  private final AppraisalProfileService profiles;
  private final ProfileSetupRepository repository;
  private final SetupNotices notices;
  private final PolicyQueryApi policies;
  private final AppraisalSpecialCases specialCases;
  private final PeriodApi periods;
  private final Clock clock;
  private final AnnualRatingRepository annual;
  private final AnnualRecomputeRequests recompute;

  public ProfileSetupService(
      AppraisalProfileService profiles,
      ProfileSetupRepository repository,
      SetupNotices notices,
      PolicyQueryApi policies,
      AppraisalSpecialCases specialCases,
      PeriodApi periods,
      Clock clock) {
    this(profiles, repository, notices, policies, specialCases, periods, clock, null, null);
  }

  /**
   * @param annual dòng {@code annual_rating} của hồ sơ năm (CORE-04-10), có thể {@code null}
   * @param recompute yêu cầu tính lại năm khi ảnh chụp quý đổi, có thể {@code null}
   */
  public ProfileSetupService(
      AppraisalProfileService profiles,
      ProfileSetupRepository repository,
      SetupNotices notices,
      PolicyQueryApi policies,
      AppraisalSpecialCases specialCases,
      PeriodApi periods,
      Clock clock,
      AnnualRatingRepository annual,
      AnnualRecomputeRequests recompute) {
    this.annual = annual;
    this.recompute = recompute;
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.repository = Objects.requireNonNull(repository, "repository");
    this.notices = Objects.requireNonNull(notices, "notices");
    this.policies = policies;
    this.specialCases = specialCases;
    this.periods = periods;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Kết quả xử lý EVT-10. */
  public enum Outcome {
    OPENED,
    REPLACED,
    STORED,
    DUPLICATE,
    STALE,
    NO_PROFILE,
    LOCKED,
    /** Hồ sơ năm đã quyết định có mức năm mới khác: về bước quyết định (MSG-10-04). */
    REOPENED,
    /** Loại tác vụ không tạo ảnh chụp (kiểm tra tái lập). */
    IGNORED,
    /**
     * Luồng Nhà nước cần điểm tiêu chí chung và điểm KPI mà EVT-10 chưa mang; hồ sơ giữ {@code
     * CHUA_LAP} (QT-09 ngoại lệ 1, wiki trang 14 mục 59).
     */
    INCOMPLETE
  }

  /** Loại ảnh chụp hợp lệ ({@code ck_score_snapshot__kind}). */
  static final java.util.Set<String> SNAPSHOT_KINDS =
      java.util.Set.of(TAM_TINH, "CHOT_KY", "TINH_LAI_HAU_KIEM");

  static final String NHA_NUOC = "NHA_NUOC";

  /** Dữ liệu của EVT-10 {@code scoring.score.computed.v1}. */
  public record ScoreComputed(
      UUID scoreResultId,
      UUID profileId,
      String jobKind,
      BigDecimal totalScore,
      String autoRatingCode,
      byte[] inputHash,
      byte[] policyContentHash,
      Instant computedAt) {
    public ScoreComputed {
      Objects.requireNonNull(scoreResultId, "scoreResultId");
      Objects.requireNonNull(profileId, "profileId");
      Objects.requireNonNull(jobKind, "jobKind");
      Objects.requireNonNull(totalScore, "totalScore");
      Objects.requireNonNull(autoRatingCode, "autoRatingCode");
    }
  }

  /**
   * EVT-09: lập hồ sơ cho mọi người thuộc diện đánh giá theo từng luồng có Policy Pack. Kỳ không
   * phải quý thì bỏ qua (hồ sơ năm do CORE-04-10).
   *
   * @return số hồ sơ mới
   */
  @Transactional(TX)
  public int onPeriodLocked(TenantId tenant, UUID periodId, int periodBucket, Instant lockedAt) {
    if (!quarter(tenant, periodId, periodBucket)) {
      return 0;
    }
    PeriodLock lock =
        new PeriodLock(
            periodId, periodBucket, lockedAt, LocalDate.ofInstant(lockedAt, Clock.BUSINESS_ZONE));
    List<UnitGroup> groups = repository.unitGroups(tenant, lock.validDate());
    List<SpecialCase> cases =
        specialCases == null ? List.of() : specialCases.forPeriod(tenant, periodId);
    int created = 0;
    for (String flow : FLOWS) {
      Map<UUID, UUID> packs = packs(tenant, periodId, flow, groups);
      if (packs.isEmpty()) {
        continue;
      }
      Set<UUID> excluded = new HashSet<>();
      cases.stream()
          .filter(c -> c.treatment() == Treatment.KHONG_LAP_HO_SO && c.appliesTo(flow))
          .forEach(c -> excluded.add(c.personId()));
      created += repository.createProfiles(tenant, lock, flow, packs, excluded, SYSTEM_ACTOR);
    }
    return created;
  }

  /**
   * EVT-10: lưu ảnh chụp mới (hai bước), đặt ảnh chụp hiện hành; hồ sơ {@code CHUA_LAP} nhận kết
   * quả chính thức thì mở phiếu tự đánh giá, phát EVT-11 và EVT-21. Điểm tạm tính chỉ lưu ảnh chụp.
   */
  @Transactional(TX)
  public Outcome onScoreComputed(TenantId tenant, ScoreComputed e) {
    if (!SNAPSHOT_KINDS.contains(e.jobKind())) {
      return Outcome.IGNORED;
    }
    Optional<ProfileRow> found = profiles.find(tenant, e.profileId());
    if (found.isEmpty()) {
      return Outcome.NO_PROFILE;
    }
    ProfileRow row = found.get();
    if (row.status() == AppraisalStatus.DA_KHOA) {
      return Outcome.LOCKED;
    }
    boolean yearly = row.periodBucket() % 10 == 0;
    if (NHA_NUOC.equals(row.flow()) && !yearly) {
      return Outcome.INCOMPLETE;
    }
    if (repository.hasResult(tenant, row.periodBucket(), e.scoreResultId())) {
      return Outcome.DUPLICATE;
    }
    Optional<SnapshotHead> current =
        repository.currentSnapshot(tenant, row.periodBucket(), row.id());
    Instant computedAt = e.computedAt() == null ? clock.instant() : e.computedAt();
    if (current.isPresent() && current.get().computedAt().isAfter(computedAt)) {
      return Outcome.STALE;
    }
    RatingLevel rating = RatingLevel.valueOf(e.autoRatingCode());
    UUID snapshotId = UUID.randomUUID();
    repository.insertSnapshot(
        tenant,
        new NewSnapshot(
            snapshotId,
            row.periodId(),
            row.periodBucket(),
            row.id(),
            row.flow(),
            e.jobKind(),
            e.scoreResultId(),
            row.policyPackVersionId(),
            e.policyContentHash(),
            policyVersion(tenant, row.policyPackVersionId()),
            e.inputHash(),
            e.totalScore(),
            rating.name(),
            computedAt,
            snapshotHash(e, row),
            yearly ? quarterDetail(tenant, row) : null),
        current.map(SnapshotHead::id).orElse(null),
        SYSTEM_ACTOR);
    if (yearly && annual != null) {
      annual.recordScore(
          tenant,
          row.periodBucket() / 10,
          row.id(),
          e.totalScore(),
          rating.name(),
          SYSTEM_ACTOR,
          clock.instant());
    }
    if (row.status() == AppraisalStatus.CHUA_LAP && !TAM_TINH.equals(e.jobKind())) {
      AppraisalProfileService.Transitioned t =
          profiles.transition(
              tenant,
              row.id(),
              null,
              SYSTEM_ACTOR,
              (p, r) -> p.openSelfAssessment(snapshotId, rating, SYSTEM_ACTOR));
      notices.selfAssessmentOpened(tenant, row.id(), row.personId(), t.rowVersion());
      return Outcome.OPENED;
    }
    if (yearly && REDECIDABLE.contains(row.status()) && row.finalRating() != rating) {
      AppraisalProfileService.Transitioned t =
          profiles.transition(
              tenant,
              row.id(),
              null,
              SYSTEM_ACTOR,
              (p, r) -> {
                p.replaceSnapshot(snapshotId, rating);
                return p.reopenDecision(SYSTEM_ACTOR, REOPEN_REASON);
              });
      notices.annualRatingChanged(
          tenant, row.id(), recipients(row), row.finalRating(), rating, t.rowVersion());
      return Outcome.REOPENED;
    }
    repository.pointCurrent(
        tenant, row.periodBucket(), row.id(), snapshotId, SYSTEM_ACTOR, clock.instant());
    if (!yearly && row.status() != AppraisalStatus.CHUA_LAP && annual != null) {
      annual
          .annualProfileOf(tenant, row.periodBucket() / 10, row.personId(), row.flow())
          .ifPresent(
              id -> {
                if (recompute != null) {
                  recompute.request(tenant, id, row.id());
                }
              });
    }
    return row.status() == AppraisalStatus.CHUA_LAP ? Outcome.STORED : Outcome.REPLACED;
  }

  /** Lý do ghi ở bước đưa hồ sơ năm về quyết định (MSG-10-04). */
  static final String REOPEN_REASON = "Thi hành kết luận hậu kiểm: kết quả năm được tính lại.";

  /** Hồ sơ năm đã quyết định mà chưa khoá: còn đưa về bước quyết định được. */
  static final Set<AppraisalStatus> REDECIDABLE =
      Set.of(AppraisalStatus.DA_QUYET_DINH, AppraisalStatus.DA_KY_SO);

  private static List<UUID> recipients(ProfileRow row) {
    List<UUID> out = new java.util.ArrayList<>();
    out.add(row.personId());
    if (row.deciderId() != null && !row.deciderId().equals(row.personId())) {
      out.add(row.deciderId());
    }
    return out;
  }

  /**
   * BR-10-06: ảnh chụp năm tham chiếu ảnh chụp hiện hành của từng quý bằng mã, băm và phiên bản
   * Policy Pack đã dùng ({@code detail_json.quy}).
   */
  private String quarterDetail(TenantId tenant, ProfileRow row) {
    if (annual == null) {
      return null;
    }
    List<Map<String, Object>> quarters = new java.util.ArrayList<>();
    for (QuarterRef q :
        annual.quarters(tenant, row.periodBucket() / 10, row.personId(), row.flow())) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("quy", q.quarter());
      m.put("ho_so", q.profileId().toString());
      m.put("muc", q.finalRating());
      m.put("anh_chup", q.snapshotId() == null ? null : q.snapshotId().toString());
      m.put("bam", q.snapshotHash() == null ? null : Jcs.hex(q.snapshotHash()));
      m.put("diem", q.totalScore() == null ? null : q.totalScore().toPlainString());
      m.put("phien_ban", q.policyVersion());
      m.put("goi", q.policyPackVersionId() == null ? null : q.policyPackVersionId().toString());
      quarters.add(m);
    }
    return Jcs.write(Map.of("quy", quarters));
  }

  // ---------------------------------------------------------------- hỗ trợ

  /** Kỳ quý: dài đúng ba tháng; không đọc được kỳ thì theo {@code period_bucket} (quý 1..4). */
  boolean quarter(TenantId tenant, UUID periodId, int periodBucket) {
    if (periods != null) {
      var period = periods.findById(tenant, PeriodId.of(periodId));
      if (period.isPresent()) {
        LocalDate start = period.get().startDate();
        return start.plusMonths(3).minusDays(1).equals(period.get().endDate());
      }
    }
    int q = periodBucket % 10;
    return q >= 1 && q <= 4;
  }

  private Map<UUID, UUID> packs(
      TenantId tenant, UUID periodId, String flow, List<UnitGroup> groups) {
    Map<UUID, UUID> packs = new LinkedHashMap<>();
    if (policies == null) {
      return packs;
    }
    Map<List<UUID>, Optional<UUID>> cache = new HashMap<>();
    for (UnitGroup g : groups) {
      Optional<UUID> version =
          cache.computeIfAbsent(
              java.util.Arrays.asList(g.orgUnitId(), g.subjectGroupId()),
              k ->
                  policies
                      .resolve(
                          tenant, PeriodId.of(periodId), flow, g.subjectGroupId(), g.orgUnitId())
                      .map(PolicyQueryApi.ResolvedPack::versionId));
      version.ifPresent(v -> packs.put(g.orgUnitId(), v));
    }
    return packs;
  }

  private String policyVersion(TenantId tenant, UUID versionId) {
    if (policies == null || versionId == null) {
      return "";
    }
    return policies
        .content(tenant, versionId)
        .map(PolicyQueryApi.PolicyContent::version)
        .orElse("");
  }

  static byte[] snapshotHash(ScoreComputed e, ProfileRow row) {
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("profileId", row.id().toString());
    doc.put("flow", row.flow());
    doc.put("scoreResultId", e.scoreResultId().toString());
    doc.put("kind", e.jobKind());
    doc.put("totalScore", e.totalScore().toPlainString());
    doc.put("autoRatingCode", e.autoRatingCode());
    doc.put("inputHash", e.inputHash() == null ? "" : Jcs.hex(e.inputHash()));
    doc.put(
        "policyContentHash", e.policyContentHash() == null ? "" : Jcs.hex(e.policyContentHash()));
    return Jcs.hash(doc);
  }
}
