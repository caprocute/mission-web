package vn.viettel.okrkpi.appraisal.application.prospect;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.SealedText;
import vn.viettel.okrkpi.appraisal.application.prospect.port.ProspectBoardRepository;
import vn.viettel.okrkpi.appraisal.application.prospect.port.ProspectBoardRepository.Board;
import vn.viettel.okrkpi.appraisal.application.prospect.port.ProspectBoardRepository.Candidate;
import vn.viettel.okrkpi.appraisal.application.prospect.port.ProspectBoardRepository.Entry;
import vn.viettel.okrkpi.appraisal.application.prospect.port.ProspectBoardRepository.EntryWrite;
import vn.viettel.okrkpi.appraisal.application.prospect.port.ProspectEvents;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.TextSealer;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Danh sách phân nhóm triển vọng của một đơn vị trong kỳ năm (QT-10 bước 8, 9; SCR-1002;
 * CORE-04-12): xem kèm gợi ý, lưu nháp, trình tập thể, kết quả thông qua và ký số. Kiểm BR-10-07
 * (chỉ kỳ năm), BR-10-08 (lý do khi khác gợi ý, bắt buộc lúc trình), BR-10-09 (CEL, khi lưu và khi
 * trình) và thứ tự không trùng trong nhóm. Nhóm và lý do là C2: không ghi nhật ký, không đưa vào sự
 * kiện.
 */
public class ProspectBoardService {

  static final String TX = "appraisalTransactionManager";

  public static final String QUARTER = "APPRAISAL-409-PROSPECT-QUARTER";
  public static final String NOT_ELIGIBLE = "APPRAISAL-422-PROSPECT-NOT-ELIGIBLE";
  public static final String REASON_REQUIRED = "APPRAISAL-422-REASON-REQUIRED";
  public static final String INVALID_STATUS = "APPRAISAL-409-INVALID-STATUS";
  public static final String VERSION_CONFLICT = "APPRAISAL-409-VERSION-CONFLICT";
  static final String VALIDATION = "COMMON-422-VALIDATION";

  public static final String NHAP = "NHAP";
  public static final String DA_TRINH = "DA_TRINH";
  public static final String DA_KY = "DA_KY";

  static final String REASON_COLUMN = "appraisal.prospect_group.reason_enc";
  static final String REJECTION_COLUMN = "appraisal.prospect_board.rejection_reason_enc";
  static final Set<String> GROUPS = Set.of("NHOM_1", "NHOM_2", "NHOM_3", "NHOM_4");

  /** Chưa có gợi ý thì đặt tạm vào nhóm cần bồi dưỡng thêm (suy luận, wiki 14). */
  static final String DEFAULT_GROUP = "NHOM_3";

  static final int REASON_MIN = 20;
  static final int REASON_MAX = 1000;
  static final int PLAN_REF_MAX = 200;

  /** Một dòng gửi lên khi lưu nháp. */
  public record EntryInput(
      UUID personId,
      String groupCode,
      Integer rankInGroup,
      String reason,
      String trainingPlanRef) {}

  /** Một cán bộ trên bảng. */
  public record EntryView(
      UUID id,
      UUID personId,
      String personName,
      String annualRating,
      boolean reassignFlag,
      String groupCode,
      String suggestedGroup,
      Integer rankInGroup,
      String reason,
      String trainingPlanRef,
      Instant decidedAt) {}

  /** Bảng của đơn vị. */
  public record BoardView(
      UUID boardId,
      UUID periodId,
      UUID orgUnitId,
      String status,
      int boardVersion,
      long rowVersion,
      String rejectionReason,
      UUID policyVersionId,
      String flow,
      List<EntryView> entries) {}

  private final ProspectBoardRepository repository;
  private final PeriodApi periods;
  private final ProspectEligibility eligibility;
  private final TextSealer sealer;
  private final ProspectEvents events;

  public ProspectBoardService(
      ProspectBoardRepository repository,
      PeriodApi periods,
      ProspectEligibility eligibility,
      TextSealer sealer,
      ProspectEvents events) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.eligibility = Objects.requireNonNull(eligibility, "eligibility");
    this.sealer = Objects.requireNonNull(sealer, "sealer");
    this.events = Objects.requireNonNull(events, "events");
  }

  /**
   * Bảng hiện hành; chưa lưu lần nào thì là bảng nháp phiên bản 0 theo gợi ý.
   *
   * @throws NoSuchElementException không có kỳ hoặc đơn vị không có hồ sơ năm
   * @throws BusinessException 409 {@link #QUARTER} khi gọi ở kỳ quý (BR-10-07, MSG-10-05)
   */
  @Transactional(transactionManager = TX, readOnly = true)
  public BoardView view(TenantId tenant, UUID periodId, UUID orgUnitId) {
    int bucket = annualBucket(tenant, periodId);
    List<Candidate> candidates = candidates(tenant, periodId, bucket, orgUnitId);
    Optional<Board> board = repository.find(tenant, bucket, orgUnitId);
    return view(tenant, periodId, orgUnitId, bucket, board.orElse(null), candidates);
  }

  /**
   * Lưu nháp toàn bộ danh sách trong một giao dịch (saveProspectGroupBoard).
   *
   * @throws BusinessException 409 khi lệch phiên bản hoặc danh sách không ở nháp; 422 khi dữ liệu
   *     sai hoặc vi phạm BR-10-09
   */
  @Transactional(TX)
  public BoardView save(
      TenantId tenant,
      UUID periodId,
      UUID orgUnitId,
      long expectedVersion,
      List<EntryInput> inputs,
      UUID actor) {
    int bucket = annualBucket(tenant, periodId);
    List<Candidate> candidates = candidates(tenant, periodId, bucket, orgUnitId);
    Board board = draft(tenant, periodId, bucket, orgUnitId, expectedVersion, actor);
    Map<UUID, Candidate> byPerson = byPerson(candidates);
    validate(inputs, byPerson);
    checkEligible(
        tenant,
        inputs.stream().map(i -> new Placement(i.personId(), i.groupCode())).toList(),
        byPerson);
    Map<UUID, Entry> existing = entriesByPerson(tenant, bucket, candidates);
    List<EntryWrite> rows = new ArrayList<>();
    for (EntryInput in : inputs) {
      Entry old = existing.get(in.personId());
      UUID id = old == null ? UUID.randomUUID() : old.id();
      String reason = blankToNull(in.reason());
      rows.add(
          new EntryWrite(
              id,
              in.personId(),
              in.groupCode(),
              in.rankInGroup(),
              reason == null ? null : sealer.seal(tenant, REASON_COLUMN, id, reason),
              blankToNull(in.trainingPlanRef())));
    }
    repository.saveEntries(tenant, periodId, bucket, rows, actor);
    long version = repository.touch(tenant, bucket, board.id(), actor);
    return view(
        tenant,
        periodId,
        orgUnitId,
        bucket,
        withVersion(board, board.status(), board.boardVersion(), version),
        candidates);
  }

  /**
   * Trình tập thể lãnh đạo (submitProspectGroupBoard): mọi cán bộ đã có nhóm, đủ lý do cho điều
   * chỉnh khác gợi ý, không vi phạm BR-10-09; khoá chỉnh sửa và phát EVT-11.
   */
  @Transactional(TX)
  public BoardView submit(
      TenantId tenant, UUID periodId, UUID orgUnitId, long expectedVersion, UUID actor) {
    int bucket = annualBucket(tenant, periodId);
    List<Candidate> candidates = candidates(tenant, periodId, bucket, orgUnitId);
    Board board = draft(tenant, periodId, bucket, orgUnitId, expectedVersion, actor);
    Map<UUID, Candidate> byPerson = byPerson(candidates);
    Map<UUID, Entry> entries = entriesByPerson(tenant, bucket, candidates);
    List<ProblemFieldError> missing = new ArrayList<>();
    List<ProblemFieldError> noReason = new ArrayList<>();
    List<Placement> placements = new ArrayList<>();
    Map<String, Set<Integer>> ranks = new HashMap<>();
    for (Candidate c : candidates) {
      Entry e = entries.get(c.personId());
      String field = "entries[" + c.personId() + "]";
      if (e == null || e.rankInGroup() == null) {
        missing.add(
            new ProblemFieldError(
                field + ".groupCode", "Đồng chí " + c.personName() + " chưa được xếp nhóm.", null));
        continue;
      }
      if (!ranks.computeIfAbsent(e.groupCode(), g -> new HashSet<>()).add(e.rankInGroup())) {
        missing.add(
            new ProblemFieldError(
                field + ".rankInGroup",
                "Thứ tự trong " + label(e.groupCode()) + " bị trùng.",
                null));
      }
      placements.add(new Placement(c.personId(), e.groupCode()));
      if (e.suggestedGroup() != null
          && !e.suggestedGroup().equals(e.groupCode())
          && e.reason() == null) {
        noReason.add(new ProblemFieldError(field + ".reason", reasonMessage(c), null));
      }
    }
    if (!missing.isEmpty()) {
      throw new BusinessException(VALIDATION, missing.get(0).message(), missing);
    }
    checkEligible(tenant, placements, byPerson);
    if (!noReason.isEmpty()) {
      throw new BusinessException(REASON_REQUIRED, noReason.get(0).message(), noReason);
    }
    long version = repository.markSubmitted(tenant, bucket, board.id(), actor);
    events.boardStepCompleted(tenant, board.id(), NHAP, DA_TRINH, actor, version);
    return view(
        tenant,
        periodId,
        orgUnitId,
        bucket,
        withVersion(board, DA_TRINH, board.boardVersion(), version),
        candidates);
  }

  /** Ghi mã tiến trình thông qua tập thể sau khi workflow-service nhận. */
  @Transactional(TX)
  public void recordProcess(TenantId tenant, BoardView board, String processInstanceId) {
    repository.recordProcess(
        tenant, annualBucket(tenant, board.periodId()), board.boardId(), processInstanceId);
  }

  /**
   * Tập thể không thông qua (QT-10 ngoại lệ 4): danh sách về nháp, lưu lý do (C2), lần trình sau là
   * phiên bản mới.
   */
  @Transactional(TX)
  public BoardView councilRejected(
      TenantId tenant, UUID periodId, UUID orgUnitId, String reason, UUID actor) {
    int bucket = annualBucket(tenant, periodId);
    Board board = submitted(tenant, periodId, bucket, orgUnitId, actor);
    SealedText sealed =
        blankToNull(reason) == null
            ? null
            : sealer.seal(tenant, REJECTION_COLUMN, board.id(), reason);
    long version = repository.markRejected(tenant, bucket, board.id(), sealed, actor);
    events.boardStepCompleted(tenant, board.id(), DA_TRINH, NHAP, actor, version);
    return view(tenant, periodId, orgUnitId);
  }

  /**
   * Tập thể đã thông qua: ghi yêu cầu ký danh sách đã mở ở esign-service; danh sách giữ {@code
   * DA_TRINH} tới khi nhận EVT-18.
   */
  @Transactional(TX)
  public void councilApproved(
      TenantId tenant, UUID periodId, UUID orgUnitId, UUID signRequestId, UUID actor) {
    Objects.requireNonNull(signRequestId, "signRequestId");
    int bucket = annualBucket(tenant, periodId);
    Board board = submitted(tenant, periodId, bucket, orgUnitId, actor);
    repository.markApproved(tenant, bucket, board.id(), signRequestId, actor);
  }

  /**
   * EVT-18 của yêu cầu ký danh sách: chuyển {@code DA_KY}, ghi thời điểm quyết định lên các dòng.
   *
   * @return {@code false} khi yêu cầu ký không thuộc danh sách phân nhóm nào
   */
  @Transactional(TX)
  public boolean onSigned(TenantId tenant, UUID signRequestId, Instant signedAt) {
    Optional<Board> found = repository.findBySignRequest(tenant, signRequestId);
    if (found.isEmpty()) {
      return false;
    }
    Board board = found.get();
    if (DA_TRINH.equals(board.status())) {
      long version = repository.markSigned(tenant, board, signedAt);
      events.boardStepCompleted(tenant, board.id(), DA_TRINH, DA_KY, board.updatedBy(), version);
    }
    return true;
  }

  private record Placement(UUID personId, String group) {}

  private int annualBucket(TenantId tenant, UUID periodId) {
    PeriodSnapshot period =
        periods.findById(tenant, PeriodId.of(periodId)).orElseThrow(NoSuchElementException::new);
    if (ChronoUnit.DAYS.between(period.startDate(), period.endDate()) <= 120) {
      throw new BusinessException(
          QUARTER, "Phân nhóm triển vọng chỉ thực hiện ở kỳ năm (Quy định khoản 12.1.5).");
    }
    return period.startDate().getYear() * 10;
  }

  private List<Candidate> candidates(TenantId tenant, UUID periodId, int bucket, UUID orgUnitId) {
    List<Candidate> out = repository.candidates(tenant, periodId, bucket, orgUnitId);
    if (out.isEmpty()) {
      throw new NoSuchElementException();
    }
    return out;
  }

  private Board draft(
      TenantId tenant,
      UUID periodId,
      int bucket,
      UUID orgUnitId,
      long expectedVersion,
      UUID actor) {
    Board board = repository.lockOrCreate(tenant, periodId, bucket, orgUnitId, actor);
    if (board.rowVersion() != expectedVersion) {
      throw new BusinessException(
          VERSION_CONFLICT, "Danh sách phân nhóm đã thay đổi, đồng chí vui lòng tải lại.");
    }
    if (!NHAP.equals(board.status())) {
      throw new BusinessException(
          INVALID_STATUS, "Danh sách phân nhóm đã trình tập thể, không chỉnh sửa được.");
    }
    return board;
  }

  private Board submitted(TenantId tenant, UUID periodId, int bucket, UUID orgUnitId, UUID actor) {
    Board board = repository.lockOrCreate(tenant, periodId, bucket, orgUnitId, actor);
    if (!DA_TRINH.equals(board.status())) {
      throw new BusinessException(
          INVALID_STATUS, "Danh sách phân nhóm không ở bước chờ tập thể thông qua.");
    }
    return board;
  }

  private void validate(List<EntryInput> inputs, Map<UUID, Candidate> byPerson) {
    List<ProblemFieldError> errors = new ArrayList<>();
    Set<UUID> seen = new HashSet<>();
    Map<String, Set<Integer>> ranks = new HashMap<>();
    for (int i = 0; i < inputs.size(); i++) {
      EntryInput in = inputs.get(i);
      String field = "entries[" + i + "]";
      if (in.personId() == null || !byPerson.containsKey(in.personId())) {
        errors.add(err(field + ".personId", "Cán bộ không thuộc danh sách phân nhóm của đơn vị."));
        continue;
      }
      if (!seen.add(in.personId())) {
        errors.add(err(field + ".personId", "Cán bộ xuất hiện hai lần trong danh sách."));
      }
      if (in.groupCode() == null || !GROUPS.contains(in.groupCode())) {
        errors.add(err(field + ".groupCode", "Nhóm không hợp lệ."));
        continue;
      }
      if (in.rankInGroup() == null || in.rankInGroup() < 1) {
        errors.add(err(field + ".rankInGroup", "Thứ tự trong nhóm phải từ 1."));
      } else if (!ranks
          .computeIfAbsent(in.groupCode(), g -> new HashSet<>())
          .add(in.rankInGroup())) {
        errors.add(
            err(field + ".rankInGroup", "Thứ tự trong " + label(in.groupCode()) + " bị trùng."));
      }
      String reason = blankToNull(in.reason());
      if (reason != null && (reason.length() < REASON_MIN || reason.length() > REASON_MAX)) {
        errors.add(err(field + ".reason", "Lý do điều chỉnh dài từ 20 đến 1.000 ký tự."));
      }
      if (in.trainingPlanRef() != null && in.trainingPlanRef().length() > PLAN_REF_MAX) {
        errors.add(
            err(field + ".trainingPlanRef", "Tham chiếu kế hoạch bồi dưỡng tối đa 200 ký tự."));
      }
    }
    if (!errors.isEmpty()) {
      throw new BusinessException(VALIDATION, errors.get(0).message(), errors);
    }
  }

  private void checkEligible(
      TenantId tenant, List<Placement> placements, Map<UUID, Candidate> byPerson) {
    List<ProblemFieldError> errors = new ArrayList<>();
    for (Placement p : placements) {
      Candidate c = byPerson.get(p.personId());
      if (!eligibility.eligible(
          tenant, c.policyVersionId(), p.group(), c.annualRating(), c.reassignFlag())) {
        errors.add(
            new ProblemFieldError(
                "entries[" + p.personId() + "].groupCode", notEligibleMessage(c, p.group()), null));
      }
    }
    if (!errors.isEmpty()) {
      throw new BusinessException(NOT_ELIGIBLE, errors.get(0).message(), errors);
    }
  }

  /** MSG-10-06. */
  static String notEligibleMessage(Candidate c, String group) {
    String why =
        "KHTNV".equals(c.annualRating())
            ? "xếp loại năm không hoàn thành nhiệm vụ"
            : "có đề xuất bố trí lại công việc";
    return "Cán bộ " + c.personName() + " " + why + " nên không thể đưa vào " + label(group) + ".";
  }

  /** MSG-10-07. */
  static String reasonMessage(Candidate c) {
    return "Đồng chí đã xếp " + c.personName() + " khác nhóm gợi ý. Vui lòng ghi lý do điều chỉnh.";
  }

  static String label(String group) {
    return group == null ? "nhóm" : "Nhóm " + group.substring(group.length() - 1);
  }

  private Map<UUID, Entry> entriesByPerson(
      TenantId tenant, int bucket, List<Candidate> candidates) {
    Map<UUID, Entry> out = new HashMap<>();
    for (Entry e :
        repository.entries(tenant, bucket, candidates.stream().map(Candidate::personId).toList())) {
      out.put(e.personId(), e);
    }
    return out;
  }

  private static Map<UUID, Candidate> byPerson(List<Candidate> candidates) {
    Map<UUID, Candidate> out = new LinkedHashMap<>();
    candidates.forEach(c -> out.put(c.personId(), c));
    return out;
  }

  private BoardView view(
      TenantId tenant,
      UUID periodId,
      UUID orgUnitId,
      int bucket,
      Board board,
      List<Candidate> candidates) {
    Map<UUID, Entry> entries = entriesByPerson(tenant, bucket, candidates);
    List<EntryView> out = new ArrayList<>();
    for (Candidate c : candidates) {
      Entry e = entries.get(c.personId());
      if (e == null) {
        out.add(
            new EntryView(
                null,
                c.personId(),
                c.personName(),
                c.annualRating(),
                c.reassignFlag(),
                DEFAULT_GROUP,
                null,
                null,
                null,
                null,
                null));
      } else {
        out.add(
            new EntryView(
                e.id(),
                c.personId(),
                c.personName(),
                c.annualRating(),
                c.reassignFlag(),
                e.groupCode(),
                e.suggestedGroup(),
                e.rankInGroup(),
                sealer.open(tenant, REASON_COLUMN, e.id(), e.reason()),
                e.trainingPlanRef(),
                e.decidedAt()));
      }
    }
    Candidate first = candidates.get(0);
    return new BoardView(
        board == null ? null : board.id(),
        periodId,
        orgUnitId,
        board == null ? NHAP : board.status(),
        board == null ? 1 : board.boardVersion(),
        board == null ? 0 : board.rowVersion(),
        board == null || board.rejectionReason() == null
            ? null
            : sealer.open(tenant, REJECTION_COLUMN, board.id(), board.rejectionReason()),
        first.policyVersionId(),
        first.flow(),
        out);
  }

  private static Board withVersion(Board b, String status, int boardVersion, long rowVersion) {
    return new Board(
        b.id(),
        b.periodId(),
        b.periodBucket(),
        b.orgUnitId(),
        status,
        boardVersion,
        rowVersion,
        b.rejectionReason(),
        b.signRequestId(),
        b.updatedBy());
  }

  private static ProblemFieldError err(String field, String message) {
    return new ProblemFieldError(field, message, null);
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }
}
