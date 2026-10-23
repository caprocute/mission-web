package vn.viettel.okrkpi.appraisal.application.annual;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.appraisal.api.AppraisalSpecialCases;
import vn.viettel.okrkpi.appraisal.api.AppraisalSpecialCases.SpecialCase;
import vn.viettel.okrkpi.appraisal.api.AppraisalSpecialCases.Treatment;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualNotices;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualRatingRepository;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualRatingRepository.Flagged;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualRatingRepository.UnitGroup;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Lập hồ sơ năm khi quý IV khoá (QT-10 bước 2, CORE-04-10). Hồ sơ năm thuộc kỳ {@code NAM} chứa
 * ngày cuối quý IV, mỗi người một hồ sơ mỗi luồng, kèm dòng {@code annual_rating} ghi mức các quý,
 * cờ có quý KHTNV (BR-10-03, chặn HTXSNV năm) và cờ hai quý liên tiếp KHTNV (BR-10-04). Người thiếu
 * quý chỉ được lập khi có trường hợp đặc thù PL 6 trong năm (BR-10-01); quý IV chỉ cần có hồ sơ vì
 * kết quả quý IV gộp vào năm (PL A5).
 */
public class AnnualSetupService {

  private static final Logger LOG = LoggerFactory.getLogger(AnnualSetupService.class);

  static final String TX = "appraisalTransactionManager";

  /** Người thực hiện các bước do hệ thống chạy (như lập hồ sơ quý). */
  static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

  static final List<String> FLOWS = List.of("DANG", "NHA_NUOC");

  private final AnnualRatingRepository repository;
  private final PeriodApi periods;
  private final PolicyQueryApi policies;
  private final AppraisalSpecialCases specialCases;
  private final AnnualNotices notices;

  public AnnualSetupService(
      AnnualRatingRepository repository,
      PeriodApi periods,
      PolicyQueryApi policies,
      AppraisalSpecialCases specialCases) {
    this(repository, periods, policies, specialCases, null);
  }

  /** Như trên, kèm cảnh báo MSG-10-02 cho dòng năm có cờ bố trí lại (CORE-04-11). */
  public AnnualSetupService(
      AnnualRatingRepository repository,
      PeriodApi periods,
      PolicyQueryApi policies,
      AppraisalSpecialCases specialCases,
      AnnualNotices notices) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.periods = periods;
    this.policies = policies;
    this.specialCases = specialCases;
    this.notices = notices;
  }

  /**
   * EVT-09 của quý IV: lập hồ sơ năm; kỳ khác bỏ qua. Chạy lại an toàn.
   *
   * @return số hồ sơ năm mới
   */
  @Transactional(TX)
  public int onQuarterLocked(TenantId tenant, UUID periodId, int periodBucket, Instant lockedAt) {
    if (periodBucket % 10 != 4 || periods == null) {
      return 0;
    }
    Optional<PeriodSnapshot> annual =
        periods
            .findById(tenant, PeriodId.of(periodId))
            .flatMap(q -> periods.findCovering(tenant, "NAM", q.endDate()));
    if (annual.isEmpty()) {
      LOG.warn("Chưa có kỳ năm chứa quý IV {}, chưa lập hồ sơ năm", periodId);
      return 0;
    }
    UUID annualId = annual.get().id().value();
    int year = periodBucket / 10;
    Set<UUID> special = new HashSet<>();
    if (specialCases != null) {
      for (UUID p : List.of(annualId, periodId)) {
        specialCases.forPeriod(tenant, p).stream()
            .filter(c -> c.treatment() != Treatment.KHONG_LAP_HO_SO)
            .map(SpecialCase::personId)
            .forEach(special::add);
      }
    }
    int created = 0;
    for (String flow : FLOWS) {
      Map<UUID, UUID> packs =
          packs(tenant, annualId, flow, repository.unitGroups(tenant, periodBucket, flow));
      created +=
          repository.createAnnual(tenant, annualId, year, flow, packs, special, SYSTEM_ACTOR);
      if (notices != null) {
        // BR-10-04: cờ hai quý liên tiếp KHTNV vừa đặt thì báo người có thẩm quyền (MSG-10-02).
        for (Flagged f : repository.flaggedInThisTransaction(tenant, year, flow)) {
          notices.reassignFlagged(tenant, f.annualProfileId(), f.recipients());
        }
      }
    }
    return created;
  }

  /** Policy Pack năm theo đơn vị; đơn vị không có gói năm dùng gói quý IV (suy luận). */
  private Map<UUID, UUID> packs(
      TenantId tenant, UUID periodId, String flow, List<UnitGroup> groups) {
    Map<UUID, UUID> out = new LinkedHashMap<>();
    if (policies == null) {
      return out;
    }
    Map<List<UUID>, Optional<UUID>> cache = new HashMap<>();
    for (UnitGroup g : groups) {
      cache
          .computeIfAbsent(
              Arrays.asList(g.orgUnitId(), g.subjectGroupId()),
              k ->
                  policies
                      .resolve(
                          tenant, PeriodId.of(periodId), flow, g.subjectGroupId(), g.orgUnitId())
                      .map(PolicyQueryApi.ResolvedPack::versionId))
          .ifPresent(v -> out.putIfAbsent(g.orgUnitId(), v));
    }
    return out;
  }
}
