package vn.viettel.okrkpi.dossier.application.specialcase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.appraisal.api.AppraisalSpecialCases;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.dossier.application.comment.port.CommentSealer;
import vn.viettel.okrkpi.dossier.application.meeting.port.ReviewMeetingRepository.MeetingRow;
import vn.viettel.okrkpi.dossier.application.specialcase.port.SpecialCaseFactsSource;
import vn.viettel.okrkpi.dossier.application.specialcase.port.SpecialCaseRepository;
import vn.viettel.okrkpi.dossier.application.specialcase.port.SpecialCaseRepository.Filter;
import vn.viettel.okrkpi.dossier.application.specialcase.port.SpecialCaseRepository.GroupCount;
import vn.viettel.okrkpi.dossier.application.specialcase.port.SpecialCaseRepository.PackageScope;
import vn.viettel.okrkpi.dossier.application.specialcase.port.SpecialCaseRepository.SpecialCaseRow;
import vn.viettel.okrkpi.dossier.application.specialcase.port.SpecialCaseRuleSource;
import vn.viettel.okrkpi.dossier.domain.meeting.Participant;
import vn.viettel.okrkpi.dossier.domain.specialcase.AssignmentFact;
import vn.viettel.okrkpi.dossier.domain.specialcase.Detection;
import vn.viettel.okrkpi.dossier.domain.specialcase.PersonPeriodFacts;
import vn.viettel.okrkpi.dossier.domain.specialcase.SpecialCaseDetector;
import vn.viettel.okrkpi.dossier.domain.specialcase.SpecialCaseRule;
import vn.viettel.okrkpi.replica.api.AssignmentView;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.replica.api.OrgTree;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Trường hợp đặc thù PL 6 (CORE-06-05, QT-11 bước 2, BR-11-05, SCR-1102 vùng 2). MOD-06 tự đối
 * chiếu phân công và dữ kiện nhân sự của từng người với bảng {@code truong_hop_dac_thu} của Policy
 * Pack khi tạo hội nghị, khi nhận EVT-15; MOD-04 đọc kết quả qua cổng {@link AppraisalSpecialCases}
 * khi lập hồ sơ; mỗi (người, kỳ, mã) chỉ có một dòng. Dòng tự phát hiện chưa xác nhận được cập nhật
 * theo dữ kiện mới; dòng đã xác nhận hoặc nhập tay giữ nguyên. Dòng không còn khớp không bị xoá tự
 * động, cơ quan tổ chức xử lý khi xác minh (suy luận).
 */
public class SpecialCaseService implements AppraisalSpecialCases {

  private static final Logger LOG = LoggerFactory.getLogger(SpecialCaseService.class);

  static final String TX = "dossierTransactionManager";
  static final String MODULE = "DOSSIER";
  static final String TU_DONG = "TU_DONG";
  static final int MAX_NOTE = 2000;
  static final UUID SYSTEM = new UUID(0L, 0L);
  public static final String DETAIL_COLUMN = "dossier.special_case.detail_enc";

  private final SpecialCaseRepository cases;
  private final SpecialCaseRuleSource rules;
  private final SpecialCaseFactsSource facts;
  private final PeriodApi periods;
  private final Supplier<OrgDirectoryApi> directory;
  private final CommentSealer sealer;
  private final ObjectMapper json;
  private final Clock clock;

  /** Một trường hợp kèm cách xử lý theo gói và chi tiết đã giải mã (chỉ ở đọc một đối tượng). */
  public record CaseView(SpecialCaseRow row, String handling, String detail) {}

  /** Một nhóm theo mục PL 6. */
  public record GroupView(String caseCode, String legalRef, String handling, long count) {}

  /** Một trang và bảng gom nhóm (gom trên toàn bộ phạm vi, không theo trang). */
  public record Page(List<GroupView> groups, List<CaseView> items) {}

  /** Thông tin xác minh của cơ quan tổ chức ({@code verifyDossierSpecialCase}). */
  public record Verification(
      Boolean verified, String caseCode, Map<String, Object> detail, String handlingNote) {}

  public SpecialCaseService(
      SpecialCaseRepository cases,
      SpecialCaseRuleSource rules,
      SpecialCaseFactsSource facts,
      PeriodApi periods,
      Supplier<OrgDirectoryApi> directory,
      CommentSealer sealer,
      ObjectMapper json,
      Clock clock) {
    this.cases = Objects.requireNonNull(cases, "cases");
    this.rules = Objects.requireNonNull(rules, "rules");
    this.facts = Objects.requireNonNull(facts, "facts");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.directory = Objects.requireNonNull(directory, "directory");
    this.sealer = Objects.requireNonNull(sealer, "sealer");
    this.json = Objects.requireNonNull(json, "json");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  // ------------------------------------------------------------ phát hiện

  /**
   * Đối chiếu một người trong kỳ và ghi các trường hợp mới; kỳ đang chốt hoặc đã khoá chỉ trả các
   * dòng sẵn có. Trả mọi trường hợp của người trong kỳ sau khi ghi.
   */
  @Transactional(TX)
  public List<SpecialCaseRow> detect(
      TenantId tenant, PeriodSnapshot period, UUID personId, List<AssignmentFact> overlay) {
    UUID periodId = period.id().value();
    int bucket = bucket(period);
    if (period.isLocked()) {
      return cases.listForPerson(tenant, bucket, periodId, personId);
    }
    cases.lockPerson(tenant, periodId, personId);
    List<SpecialCaseRule> table = rules.rules(tenant, periodId);
    PersonPeriodFacts f =
        facts.collect(tenant, personId, period.startDate(), period.endDate(), overlay);
    Map<String, SpecialCaseRow> existing = new LinkedHashMap<>();
    for (SpecialCaseRow r : cases.listForPerson(tenant, bucket, periodId, personId)) {
      existing.put(r.caseCode(), r);
    }
    for (Detection d : SpecialCaseDetector.detect(table, f)) {
      SpecialCaseRow current = existing.get(d.code());
      if (current == null) {
        UUID id = UUID.randomUUID();
        CommentSealer.Sealed sealed = seal(tenant, id, d.detail());
        cases.insert(
            tenant,
            new SpecialCaseRow(
                id,
                periodId,
                bucket,
                personId,
                d.code(),
                TU_DONG,
                sealed == null ? null : sealed.cipher(),
                sealed == null ? null : sealed.dekId(),
                d.effect(),
                d.effectiveFrom(),
                d.effectiveTo(),
                d.rule().legalRef(),
                null,
                null,
                0),
            SYSTEM);
      } else if (TU_DONG.equals(current.detectedBy())
          && current.confirmedBy() == null
          && changed(current, d)) {
        CommentSealer.Sealed sealed = seal(tenant, current.id(), d.detail());
        cases.update(
            tenant,
            new SpecialCaseRow(
                current.id(),
                periodId,
                bucket,
                personId,
                current.caseCode(),
                TU_DONG,
                sealed == null ? null : sealed.cipher(),
                sealed == null ? null : sealed.dekId(),
                d.effect(),
                d.effectiveFrom(),
                d.effectiveTo(),
                d.rule().legalRef(),
                null,
                null,
                current.rowVersion()),
            current.rowVersion(),
            SYSTEM);
      }
    }
    return cases.listForPerson(tenant, bucket, periodId, personId);
  }

  /**
   * Việc chạy kèm khi tạo hội nghị (CORE-06-02): đối chiếu người được kiểm điểm, hoặc mọi thành
   * phần của hội nghị tập thể. Không mở giao dịch riêng: chạy trong giao dịch tạo hội nghị của bên
   * gọi, bên gọi tự đặt điểm lưu để lỗi ở đây không làm hỏng lệnh tạo.
   */
  public void meetingCreated(TenantId tenant, MeetingRow meeting) {
    Optional<PeriodSnapshot> period = periods.findById(tenant, PeriodId.of(meeting.periodId()));
    if (period.isEmpty()) {
      return;
    }
    List<UUID> persons =
        meeting.subjectPersonId() != null
            ? List.of(meeting.subjectPersonId())
            : meeting.participants().stream().map(Participant::personId).distinct().toList();
    for (UUID person : persons) {
      detect(tenant, period.get(), person, List.of());
    }
  }

  /**
   * EVT-15: phân công đổi rơi vào kỳ quý hoặc năm hiện hành thì đối chiếu lại người đó; phân công
   * vừa nhận đè lên bản sao tổ chức vì consumer bản sao có thể chưa kịp ghi.
   */
  @Transactional(TX)
  public void assignmentChanged(TenantId tenant, UUID personId, AssignmentFact changed) {
    Map<PeriodId, PeriodSnapshot> current = new LinkedHashMap<>();
    for (String cadence : List.of("QUY", "NAM")) {
      periods.findCurrent(tenant, cadence).ifPresent(p -> current.putIfAbsent(p.id(), p));
    }
    for (PeriodSnapshot p : current.values()) {
      if (!p.isLocked() && changed.overlaps(p.startDate(), p.endDate())) {
        detect(tenant, p, personId, List.of(changed));
      }
    }
  }

  /**
   * Cổng {@link AppraisalSpecialCases} của MOD-04 khi lập hồ sơ quý (consumer EVT-09, kỳ vừa khoá):
   * trả các trường hợp đã ghi của kỳ, không đối chiếu lại vì kỳ đã khoá thì không ghi. Hệ quả
   * {@code KHONG_XEP_LOAI} ứng với {@link Treatment#KHONG_XEP_LOAI}, {@code XEP_LOAI_TAI_DON_VI_CU}
   * với {@link Treatment#DON_VI_CU_DANH_GIA}; các hệ quả khác không đổi việc lập hồ sơ nên không
   * trả. TL-03 không có hệ quả nào ứng với {@link Treatment#KHONG_LAP_HO_SO}. Bảng không có cột
   * luồng nên trường hợp áp cả hai luồng.
   */
  @Override
  @Transactional(transactionManager = TX, readOnly = true)
  public List<SpecialCase> forPeriod(TenantId tenant, UUID periodId) {
    Optional<PeriodSnapshot> period = periods.findById(tenant, PeriodId.of(periodId));
    if (period.isEmpty()) {
      return List.of();
    }
    List<SpecialCase> out = new ArrayList<>();
    for (SpecialCaseRow r : cases.listForPeriod(tenant, bucket(period.get()), periodId)) {
      Treatment t = treatment(r.effect());
      if (t != null) {
        out.add(new SpecialCase(r.personId(), null, t));
      }
    }
    return out;
  }

  static Treatment treatment(String effect) {
    return switch (effect) {
      case "KHONG_XEP_LOAI" -> Treatment.KHONG_XEP_LOAI;
      case "XEP_LOAI_TAI_DON_VI_CU" -> Treatment.DON_VI_CU_DANH_GIA;
      default -> null;
    };
  }

  // ------------------------------------------------------------ đọc

  /**
   * SCR-1102 vùng 2: trường hợp của đơn vị trong kỳ, gom nhóm theo mã; không trả chi tiết C2. Đơn
   * vị mặc định là đơn vị của người gọi; người gọi chỉ xem đơn vị mình hoặc đơn vị cấp dưới.
   */
  @Transactional(transactionManager = TX, readOnly = true)
  public Page list(
      TenantId tenant,
      UUID actorUnit,
      UUID periodId,
      UUID unitId,
      UUID packageId,
      UUID after,
      int limit) {
    UUID unit = unitId != null ? unitId : actorUnit;
    UUID person = null;
    UUID period = periodId;
    if (packageId != null) {
      PackageScope scope =
          cases
              .packageScope(tenant, packageId)
              .orElseThrow(() -> invalid("packageId", "Gói hồ sơ không tồn tại."));
      if (unitId != null && !unitId.equals(scope.orgUnitId())) {
        throw invalid("packageId", "Gói hồ sơ không thuộc đơn vị đã chọn.");
      }
      unit = scope.orgUnitId();
      person = scope.personId();
      period = period == null ? scope.periodId() : period;
    }
    if (unit == null || !inScope(tenant, actorUnit, unit)) {
      throw outOfScope();
    }
    PeriodSnapshot snapshot =
        (period == null
                ? periods.findCurrent(tenant)
                : periods.findById(tenant, PeriodId.of(period)))
            .orElseThrow(() -> invalid("period", "Không tìm thấy kỳ đánh giá."));
    Filter filter = new Filter(snapshot.id().value(), bucket(snapshot), unit, person);
    Map<String, SpecialCaseRule> table = table(tenant, filter.periodId());
    List<GroupView> groups = new ArrayList<>();
    for (GroupCount g : cases.groups(tenant, filter)) {
      groups.add(
          new GroupView(g.caseCode(), g.legalRef(), handling(table, g.caseCode()), g.count()));
    }
    List<CaseView> items = new ArrayList<>();
    for (SpecialCaseRow r : cases.list(tenant, filter, after, limit)) {
      items.add(new CaseView(r, handling(table, r.caseCode()), null));
    }
    return new Page(groups, items);
  }

  /** Chi tiết một trường hợp, giải mã {@code detail_enc} (nhật ký truy cập C2 do bộ mã hoá ghi). */
  @Transactional(transactionManager = TX, readOnly = true)
  public CaseView get(TenantId tenant, UUID actorUnit, UUID id) {
    SpecialCaseRow row = cases.find(tenant, id).orElseThrow(NoSuchElementException::new);
    assertScope(tenant, actorUnit, row);
    return view(tenant, row);
  }

  // ------------------------------------------------------------ xác minh

  /**
   * Cơ quan tổ chức xác minh (QT-11 bước 2): xác nhận ghi người, thời điểm; bỏ xác nhận xoá hai cột
   * này. Đổi mã trường hợp lấy lại căn cứ và hệ quả theo bảng của gói. {@code handlingNote} chỉ
   * kiểm độ dài vì TL-03 chưa có cột lưu.
   */
  @Transactional(TX)
  public CaseView verify(
      TenantId tenant, UUID actor, UUID actorUnit, UUID id, long expectedVersion, Verification v) {
    SpecialCaseRow current = cases.lock(tenant, id).orElseThrow(NoSuchElementException::new);
    assertScope(tenant, actorUnit, current);
    if (current.rowVersion() != expectedVersion) {
      throw VersionConflictException.of(MODULE, expectedVersion, current.rowVersion());
    }
    Map<String, SpecialCaseRule> table = table(tenant, current.periodId());
    List<ProblemFieldError> errors = new ArrayList<>();
    if (v.verified() == null) {
      errors.add(field("verified", "Chưa chọn xác nhận hay không xác nhận."));
    }
    SpecialCaseRule rule = null;
    if (v.caseCode() != null && !v.caseCode().equals(current.caseCode())) {
      rule = table.get(v.caseCode());
      if (rule == null) {
        errors.add(field("caseCode", "Trường hợp đặc thù không có trong quy định áp dụng."));
      }
    }
    if (v.handlingNote() != null && v.handlingNote().length() > MAX_NOTE) {
      errors.add(field("handlingNote", "Ghi chú tối đa 2.000 ký tự."));
    }
    if (!errors.isEmpty()) {
      throw new BusinessException(
          "DOSSIER-422-VALIDATION", "Thông tin xác minh trường hợp đặc thù chưa hợp lệ.", errors);
    }
    try {
      periods.assertWritable(tenant, PeriodId.of(current.periodId()));
    } catch (PeriodLockedException e) {
      throw new BusinessException(
          "DOSSIER-409-PERIOD-LOCKED", "Kỳ đánh giá đã khoá, không xác minh được trường hợp này.");
    }
    if (rule != null) {
      cases.lockPerson(tenant, current.periodId(), current.personId());
      boolean taken =
          cases
              .listForPerson(tenant, current.periodBucket(), current.periodId(), current.personId())
              .stream()
              .anyMatch(r -> r.caseCode().equals(v.caseCode()));
      if (taken) {
        throw new BusinessException(
            "DOSSIER-409-INVALID-STATE", "Người này đã có trường hợp đặc thù cùng loại trong kỳ.");
      }
    }
    byte[] cipher = current.detailCipher();
    UUID dekId = current.dekId();
    if (v.detail() != null && !v.detail().isEmpty()) {
      CommentSealer.Sealed sealed = seal(tenant, current.id(), v.detail());
      cipher = sealed.cipher();
      dekId = sealed.dekId();
    }
    boolean verified = Boolean.TRUE.equals(v.verified());
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    SpecialCaseRow next =
        new SpecialCaseRow(
            current.id(),
            current.periodId(),
            current.periodBucket(),
            current.personId(),
            rule == null ? current.caseCode() : rule.code(),
            current.detectedBy(),
            cipher,
            dekId,
            rule == null ? current.effect() : rule.effect(),
            current.effectiveFrom(),
            current.effectiveTo(),
            rule == null ? current.legalRef() : rule.legalRef(),
            verified ? actor : null,
            verified ? now : null,
            current.rowVersion());
    long version = cases.update(tenant, next, expectedVersion, actor);
    if (version == 0) {
      throw VersionConflictException.of(MODULE, expectedVersion, current.rowVersion() + 1);
    }
    return view(tenant, next.withVersion(version));
  }

  // ------------------------------------------------------------ tiện ích

  /** Khoá phân vùng theo kỳ, cùng công thức với hội nghị kiểm điểm. */
  static int bucket(PeriodSnapshot p) {
    boolean yearly = ChronoUnit.DAYS.between(p.startDate(), p.endDate()) > 120;
    return p.startDate().getYear() * 10
        + (yearly ? 0 : (p.startDate().getMonthValue() - 1) / 3 + 1);
  }

  private CaseView view(TenantId tenant, SpecialCaseRow row) {
    String detail =
        row.detailCipher() == null
            ? null
            : sealer.open(tenant, DETAIL_COLUMN, row.id(), row.detailCipher());
    return new CaseView(row, handling(table(tenant, row.periodId()), row.caseCode()), detail);
  }

  private Map<String, SpecialCaseRule> table(TenantId tenant, UUID periodId) {
    Map<String, SpecialCaseRule> out = new LinkedHashMap<>();
    for (SpecialCaseRule r : rules.rules(tenant, periodId)) {
      out.put(r.code(), r);
    }
    return out;
  }

  private static String handling(Map<String, SpecialCaseRule> table, String code) {
    SpecialCaseRule r = table.get(code);
    return r == null ? null : r.handling();
  }

  private static boolean changed(SpecialCaseRow row, Detection d) {
    return !Objects.equals(row.effect(), d.effect())
        || !Objects.equals(row.effectiveFrom(), d.effectiveFrom())
        || !Objects.equals(row.effectiveTo(), d.effectiveTo())
        || !Objects.equals(row.legalRef(), d.rule().legalRef());
  }

  private CommentSealer.Sealed seal(TenantId tenant, UUID id, Map<String, Object> detail) {
    if (detail == null || detail.isEmpty()) {
      return null;
    }
    try {
      return sealer.seal(tenant, DETAIL_COLUMN, id, json.writeValueAsString(detail));
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Chi tiết trường hợp đặc thù không tuần tự được", e);
    }
  }

  /** Người gọi phụ trách đơn vị tổ chức một hội nghị của người, hoặc đơn vị công tác của người. */
  private void assertScope(TenantId tenant, UUID actorUnit, SpecialCaseRow row) {
    if (actorUnit == null) {
      throw outOfScope();
    }
    for (UUID unit :
        cases.meetingUnitsOf(tenant, row.periodBucket(), row.periodId(), row.personId())) {
      if (inScope(tenant, actorUnit, unit)) {
        return;
      }
    }
    OrgDirectoryApi org = directory.get();
    Optional<PeriodSnapshot> period = periods.findById(tenant, PeriodId.of(row.periodId()));
    if (org != null && period.isPresent()) {
      for (AssignmentView a : org.assignmentsOf(tenant, row.personId(), period.get().endDate())) {
        if (inScope(tenant, actorUnit, a.orgUnitId())) {
          return;
        }
      }
    }
    LOG.debug("Ngoài phạm vi trường hợp đặc thù id={}", row.id());
    throw outOfScope();
  }

  private boolean inScope(TenantId tenant, UUID actorUnit, UUID unit) {
    if (actorUnit == null) {
      return false;
    }
    if (actorUnit.equals(unit)) {
      return true;
    }
    OrgDirectoryApi org = directory.get();
    return org != null
        && (org.isDescendant(tenant, OrgTree.DANG, actorUnit, unit)
            || org.isDescendant(tenant, OrgTree.CHUYEN_MON, actorUnit, unit));
  }

  private static BusinessException outOfScope() {
    return new BusinessException(
        "COMMON-403-OUT-OF-SCOPE", "Đồng chí không có quyền xem trường hợp đặc thù này.");
  }

  private static BusinessException invalid(String name, String message) {
    return new BusinessException("COMMON-422-VALIDATION", message, List.of(field(name, message)));
  }

  private static ProblemFieldError field(String name, String message) {
    return new ProblemFieldError(name, message, null);
  }
}
