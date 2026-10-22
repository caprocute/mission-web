package vn.viettel.okrkpi.appraisal.application.decision;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.appraisal.application.decision.port.BatchDecisionRepository;
import vn.viettel.okrkpi.appraisal.application.decision.port.BatchDecisionRepository.BatchRating;
import vn.viettel.okrkpi.appraisal.application.decision.port.BatchDecisionRepository.BatchRow;
import vn.viettel.okrkpi.appraisal.application.decision.port.BatchDecisionRepository.LockCandidate;
import vn.viettel.okrkpi.appraisal.application.decision.port.DecisionNotices;
import vn.viettel.okrkpi.appraisal.application.profile.AppraisalProfileService;
import vn.viettel.okrkpi.appraisal.application.profile.AppraisalProfileService.Transitioned;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.ProfileRow;
import vn.viettel.okrkpi.appraisal.domain.profile.AppraisalStatus;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Hoàn tất bước quyết định (CORE-04-08, QT-09 bước 12 đến 14): nhận EVT-18 của lô (mục đích {@code
 * QUYET_DINH}, nguồn là mã lô), ghép từng chữ ký với dòng mức theo băm tài liệu, chuyển hồ sơ
 * {@code CHO_QUYET_DINH} → {@code DA_QUYET_DINH} → {@code DA_KY_SO} (EVT-11), phát EVT-12 và EVT-21
 * kèm hạn khiếu nại, đóng lô ({@code DA_KY} hoặc {@code LOI_MOT_PHAN}, MSG-09-16). Hết hạn khiếu
 * nại (tham số {@code khieu_nai.thoi_han}, mặc định 10 ngày làm việc) mà hồ sơ vẫn {@code DA_KY_SO}
 * thì khoá {@code DA_KHOA}; hồ sơ {@code KHIEU_NAI} không bị khoá.
 */
public class DecisionSigningService {

  static final String TX = "appraisalTransactionManager";
  static final String DEADLINE_PARAM = "khieu_nai.thoi_han";
  static final int DEFAULT_DEADLINE_DAYS = 10;

  /** Tác nhân hệ thống của lệnh khoá tự động. */
  public static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

  private final AppraisalProfileRepository profiles;
  private final BatchDecisionRepository batches;
  private final AppraisalProfileService steps;
  private final DecisionNotices notices;
  private final PolicyQueryApi policies;
  private final WorkingCalendarApi calendar;
  private final Clock clock;

  public DecisionSigningService(
      AppraisalProfileRepository profiles,
      BatchDecisionRepository batches,
      AppraisalProfileService steps,
      DecisionNotices notices,
      PolicyQueryApi policies,
      WorkingCalendarApi calendar,
      Clock clock) {
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.batches = Objects.requireNonNull(batches, "batches");
    this.steps = Objects.requireNonNull(steps, "steps");
    this.notices = Objects.requireNonNull(notices, "notices");
    this.policies = policies;
    this.calendar = calendar;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * EVT-18 của lô quyết định. Sự kiện của lô đã đóng, của yêu cầu ký khác hoặc lặp lại bị bỏ qua;
   * chữ ký nào không khớp băm tài liệu thì hồ sơ đó giữ {@code CHO_QUYET_DINH}.
   *
   * @param signedHashes băm SHA-256 (hex chữ thường) các tài liệu đã ký
   * @return số hồ sơ đã chuyển {@code DA_KY_SO}
   */
  @Transactional(TX)
  public int onSigned(
      TenantId tenant,
      UUID batchId,
      UUID signRequestId,
      Set<String> signedHashes,
      Instant signedAt) {
    Optional<BatchRow> found = batches.find(tenant, batchId, true);
    if (found.isEmpty()) {
      return 0;
    }
    BatchRow b = found.get();
    boolean open =
        BatchDecisionService.NHAP.equals(b.status())
            || BatchDecisionService.CHO_KY.equals(b.status());
    if (!open || (b.signRequestId() != null && !b.signRequestId().equals(signRequestId))) {
      return 0;
    }
    Instant now = clock.instant();
    int bucket = b.periodBucket();
    if (b.signRequestId() == null) {
      batches.attachSignRequest(tenant, bucket, b.id(), signRequestId, b.deciderId(), now);
    }
    UUID decider = b.deciderId();
    int signed = 0;
    int pending = 0;
    for (BatchRating r : batches.ratings(tenant, bucket, b.id())) {
      if (r.signedAt() != null) {
        continue;
      }
      String hash = HexFormat.of().formatHex(BatchDecisionService.contentHash(b, r));
      Optional<ProfileRow> row = profiles.lock(tenant, r.profileId());
      if (!signedHashes.contains(hash)
          || row.isEmpty()
          || row.get().status() != AppraisalStatus.CHO_QUYET_DINH) {
        pending++;
        continue;
      }
      batches.markSigned(tenant, bucket, r.proposalId(), signRequestId, signedAt, now);
      steps.transition(tenant, r.profileId(), null, decider, (p, x) -> p.decide(decider));
      Transitioned done =
          steps.transition(tenant, r.profileId(), null, decider, (p, x) -> p.markSigned(decider));
      ProfileRow current = row.get();
      notices.decided(
          tenant,
          current,
          RatingLevel.valueOf(r.ratingCode()),
          decider,
          signRequestId,
          deadline(tenant, current.policyPackVersionId(), signedAt, new HashMap<>()),
          done.rowVersion());
      signed++;
    }
    String status = pending == 0 ? BatchDecisionService.DA_KY : BatchDecisionService.LOI_MOT_PHAN;
    batches.setStatus(tenant, bucket, b.id(), status, decider, now);
    if (pending == 0) {
      long version = batches.find(tenant, b.id(), false).map(BatchRow::rowVersion).orElse(0L);
      notices.batchCompleted(tenant, b.id(), decider, b.decisionNo(), b.profileCount(), version);
    }
    return signed;
  }

  /** Trang ứng viên khoá: hồ sơ đã ký số từ trước hôm nay. */
  @Transactional(transactionManager = TX, readOnly = true)
  public List<LockCandidate> candidates(TenantId tenant, UUID after, int limit) {
    return batches.signedProfiles(tenant, clock.instant(), after, limit);
  }

  /**
   * Khoá hồ sơ đã quá hạn khiếu nại ({@code today} sau ngày cuối của hạn). Hồ sơ đã rời {@code
   * DA_KY_SO} (đang khiếu nại, đã khoá) thì bỏ qua; chạy lại an toàn.
   *
   * @param packDays bộ nhớ đệm số ngày theo phiên bản Policy Pack trong một lượt chạy
   */
  @Transactional(TX)
  public boolean lockIfDue(
      TenantId tenant, LockCandidate c, LocalDate today, Map<UUID, Integer> packDays) {
    LocalDate deadline = deadline(tenant, c.policyPackVersionId(), c.decidedAt(), packDays);
    if (!today.isAfter(deadline)) {
      return false;
    }
    Optional<ProfileRow> row = profiles.lock(tenant, c.profileId());
    if (row.isEmpty() || row.get().status() != AppraisalStatus.DA_KY_SO) {
      return false;
    }
    steps.transition(tenant, c.profileId(), null, SYSTEM_ACTOR, (p, x) -> p.lock(SYSTEM_ACTOR));
    return true;
  }

  /** Ngày cuối của hạn khiếu nại: {@code n} ngày làm việc sau ngày ký (giờ Việt Nam). */
  LocalDate deadline(
      TenantId tenant, UUID packVersionId, Instant signedAt, Map<UUID, Integer> packDays) {
    LocalDate from =
        LocalDate.ofInstant(signedAt, vn.viettel.okrkpi.shared.kernel.Clock.BUSINESS_ZONE);
    int days =
        packVersionId == null
            ? DEFAULT_DEADLINE_DAYS
            : packDays.computeIfAbsent(packVersionId, v -> days(tenant, v));
    return calendar == null ? from.plusDays(days) : calendar.addWorkingDays(tenant, from, days);
  }

  private int days(TenantId tenant, UUID versionId) {
    if (policies == null) {
      return DEFAULT_DEADLINE_DAYS;
    }
    return policies
        .parameter(tenant, versionId, DEADLINE_PARAM)
        .filter(Number.class::isInstance)
        .map(v -> ((Number) v).intValue())
        .filter(v -> v > 0)
        .orElse(DEFAULT_DEADLINE_DAYS);
  }
}
