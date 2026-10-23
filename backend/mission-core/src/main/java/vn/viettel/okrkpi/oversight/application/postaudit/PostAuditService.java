package vn.viettel.okrkpi.oversight.application.postaudit;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.AccessDirectory;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.oversight.application.port.ComplaintSealer;
import vn.viettel.okrkpi.oversight.application.port.OversightAuditLog;
import vn.viettel.okrkpi.oversight.application.port.PostAuditRepository;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenRequestService.Actor;
import vn.viettel.okrkpi.oversight.domain.postaudit.PostAudit;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Đợt hậu kiểm (CORE-07-09, QT-13 bước 1 và 13, SCR-1301): khởi tạo, xem, danh sách và đóng với kết
 * luận, phát hiện C2, yêu cầu mở lại liên quan và tệp bằng chứng xuất từ sổ cái ({@code POST
 * /ledger/exports} của SVC-09). Người thẩm định (phạm vi {@code Cd}) chỉ thấy, chỉ lập đợt có đối
 * tượng là đơn vị trong cây đơn vị của mình và đợt do chính mình lập; cơ quan kiểm tra ({@code Tn})
 * thấy cả tenant và là người duy nhất được đóng. Mỗi bước ghi kiểm toán lên sổ cái qua outbox
 * (BR-13-07); lần giải mã phát hiện được {@code C2AccessAuditor} ghi sổ cái truy cập C2.
 */
public class PostAuditService {

  static final String TX = "oversightTransactionManager";
  static final String OBJECT_TYPE = "post_audit";
  static final String ACTION_CREATE = "TAO_DOT_HAU_KIEM";
  static final String ACTION_CLOSE = "DONG_DOT_HAU_KIEM";
  static final String ACTION_EVIDENCE = "GAN_BANG_CHUNG_SO_CAI";
  static final String COLUMN_FINDINGS = "oversight.post_audit.findings_enc";
  static final String ROLE_APPRAISER = "appraiser";
  static final String ROLE_INSPECTOR = "inspector";

  private final PostAuditRepository audits;
  private final PeriodApi periods;
  private final AccessDirectory directory;
  private final ComplaintSealer sealer;
  private final OversightAuditLog audit;
  private final Clock clock;

  public PostAuditService(
      PostAuditRepository audits,
      PeriodApi periods,
      AccessDirectory directory,
      ComplaintSealer sealer,
      OversightAuditLog audit,
      Clock clock) {
    this.audits = Objects.requireNonNull(audits, "audits");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.directory = directory;
    this.sealer = sealer;
    this.audit = Objects.requireNonNull(audit, "audit");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Nội dung khởi tạo theo {@code createPostAudit}. */
  public record Draft(
      UUID periodId, String auditKind, String targetType, UUID targetId, String basis) {}

  /** Nội dung đóng theo {@code closePostAudit}. */
  public record Closing(
      String conclusion, String findings, UUID reopenRequestId, List<UUID> evidenceObjectIds) {
    public Closing {
      evidenceObjectIds = evidenceObjectIds == null ? List.of() : evidenceObjectIds;
    }
  }

  /** Đợt hậu kiểm kèm phát hiện đã giải mã (chỉ ở chi tiết và phản hồi đóng). */
  public record View(PostAudit audit, String findings) {}

  @Transactional(TX)
  public View create(TenantId tenant, Actor actor, Draft d) {
    List<ProblemFieldError> errors = new ArrayList<>();
    if (d.periodId() == null) {
      errors.add(field("periodId", "Chọn kỳ hậu kiểm."));
    }
    if (d.auditKind() == null || !PostAudit.KINDS.contains(d.auditKind())) {
      errors.add(field("auditKind", "Chọn loại hậu kiểm."));
    }
    if (d.targetType() == null || !PostAudit.TARGETS.contains(d.targetType())) {
      errors.add(field("targetType", "Chọn loại đối tượng hậu kiểm."));
    }
    if (d.targetId() == null) {
      errors.add(field("targetId", "Chọn đối tượng hậu kiểm."));
    }
    String basis = d.basis() == null || d.basis().isBlank() ? null : d.basis().strip();
    if (basis != null && basis.length() > PostAudit.MAX_BASIS) {
      errors.add(field("basis", "Căn cứ không quá 200 ký tự."));
    }
    if (!errors.isEmpty()) {
      throw new BusinessException(
          "COMMON-422-VALIDATION", "Đợt hậu kiểm chưa hợp lệ.", List.copyOf(errors));
    }
    PeriodSnapshot period =
        periods
            .findById(tenant, PeriodId.of(d.periodId()))
            .orElseThrow(() -> invalid("periodId", "Không tìm thấy kỳ đánh giá."));
    if (!actor.roles().contains(ROLE_INSPECTOR)) {
      // Phạm vi Cd: chỉ đơn vị trong cây đơn vị của người lập (suy luận, wiki trang 14).
      if (!"DON_VI".equals(d.targetType())
          || !unitsOf(tenant, actor.unitId()).contains(d.targetId())) {
        throw outOfScope();
      }
    }
    PostAudit a =
        new PostAudit(
            Ids.newUuidV7(),
            d.periodId(),
            bucket(period),
            d.auditKind(),
            d.targetType(),
            d.targetId(),
            basis,
            actor.userId(),
            null,
            null,
            null,
            null,
            List.of(),
            clock.instant(),
            null,
            0);
    audits.insert(tenant, a, actor.userId());
    audit.record(
        tenant, actor.userId(), ACTION_CREATE, OBJECT_TYPE, a.id().toString(), null, role(actor));
    return new View(a, null);
  }

  @Transactional(value = TX, readOnly = true)
  public View get(TenantId tenant, Actor actor, UUID id) {
    PostAudit a = visible(tenant, actor, id);
    return new View(a, findings(tenant, a));
  }

  @Transactional(value = TX, readOnly = true)
  public List<PostAudit> list(
      TenantId tenant,
      Actor actor,
      UUID periodId,
      Boolean open,
      PostAuditRepository.After after,
      int limit) {
    PostAuditRepository.Filter filter =
        actor.roles().contains(ROLE_INSPECTOR)
            ? new PostAuditRepository.Filter(periodId, open, null, List.of())
            : new PostAuditRepository.Filter(
                periodId, open, actor.userId(), unitsOf(tenant, actor.unitId()));
    return audits.list(tenant, filter, after, limit);
  }

  @Transactional(TX)
  public View close(TenantId tenant, Actor actor, UUID id, String ifMatch, Closing c) {
    PostAudit a = audits.lock(tenant, id).orElseThrow(NotFoundException::new);
    if (!actor.roles().contains(ROLE_INSPECTOR)) {
      throw new NotFoundException();
    }
    ETags.requireMatch(ifMatch, a.rowVersion(), "OVERSIGHT");
    if (!a.open()) {
      throw new BusinessException("OVERSIGHT-409-INVALID-STATE", "Đợt hậu kiểm đã đóng.");
    }
    List<ProblemFieldError> errors = new ArrayList<>();
    if (c.conclusion() == null || !PostAudit.CONCLUSIONS.contains(c.conclusion())) {
      errors.add(field("conclusion", "Chọn kết luận hậu kiểm."));
    }
    String findings = c.findings() == null ? "" : c.findings().strip();
    if (findings.isEmpty() || findings.length() > PostAudit.MAX_FINDINGS) {
      errors.add(field("findings", "Phát hiện, kết luận chi tiết cần từ 1 đến 16.000 ký tự."));
    }
    Set<UUID> evidence = new LinkedHashSet<>(c.evidenceObjectIds());
    if (evidence.contains(null)
        || evidence.size() != c.evidenceObjectIds().size()
        || evidence.size() > PostAudit.MAX_EVIDENCE) {
      errors.add(field("evidenceObjectIds", "Tệp bằng chứng không trùng và không quá 20 tệp."));
    }
    if (c.reopenRequestId() != null
        && !audits.reopenRequestOfPeriod(tenant, c.reopenRequestId(), a.periodId())) {
      errors.add(field("reopenRequestId", "Không tìm thấy yêu cầu mở lại trong kỳ hậu kiểm."));
    }
    if (!errors.isEmpty()) {
      throw new BusinessException(
          "COMMON-422-VALIDATION", "Báo cáo hậu kiểm chưa hợp lệ.", List.copyOf(errors));
    }
    if (sealer == null) {
      throw new SealingUnavailableException();
    }
    ComplaintSealer.Sealed sealed = sealer.seal(tenant, COLUMN_FINDINGS, a.id(), findings);
    PostAudit closed =
        a.close(
            c.conclusion(),
            sealed.cipher(),
            sealed.dekId(),
            c.reopenRequestId(),
            List.copyOf(evidence),
            clock.instant());
    if (!audits.close(tenant, closed, a.rowVersion(), actor.userId())) {
      throw new BusinessException(
          "OVERSIGHT-409-VERSION-CONFLICT", "Đợt hậu kiểm vừa được cập nhật, tải lại để tiếp tục.");
    }
    String objectId = a.id().toString();
    audit.record(tenant, actor.userId(), ACTION_CLOSE, OBJECT_TYPE, objectId, null, ROLE_INSPECTOR);
    for (UUID e : evidence) {
      audit.record(
          tenant,
          actor.userId(),
          ACTION_EVIDENCE,
          OBJECT_TYPE,
          objectId + ":" + e,
          null,
          ROLE_INSPECTOR);
    }
    return new View(closed, findings);
  }

  private PostAudit visible(TenantId tenant, Actor actor, UUID id) {
    PostAudit a = audits.find(tenant, id).orElseThrow(NotFoundException::new);
    if (actor.roles().contains(ROLE_INSPECTOR) || actor.userId().equals(a.inspectorId())) {
      return a;
    }
    if (actor.roles().contains(ROLE_APPRAISER)
        && "DON_VI".equals(a.targetType())
        && unitsOf(tenant, actor.unitId()).contains(a.targetId())) {
      return a;
    }
    throw new NotFoundException();
  }

  private String findings(TenantId tenant, PostAudit a) {
    if (a.findingsCipher() == null) {
      return null;
    }
    if (sealer == null) {
      throw new SealingUnavailableException();
    }
    return sealer.open(tenant, COLUMN_FINDINGS, a.id(), a.findingsCipher());
  }

  private List<UUID> unitsOf(TenantId tenant, UUID unit) {
    if (unit == null) {
      return List.of();
    }
    Set<UUID> out = new HashSet<>();
    out.add(unit);
    if (directory != null) {
      out.addAll(directory.subunitsOf(tenant, unit));
    }
    return List.copyOf(out);
  }

  /** {@code period_bucket} như {@code ReopenRequestService.bucket}. */
  static int bucket(PeriodSnapshot p) {
    boolean yearly = java.time.temporal.ChronoUnit.DAYS.between(p.startDate(), p.endDate()) > 120;
    return p.startDate().getYear() * 10
        + (yearly ? 0 : (p.startDate().getMonthValue() - 1) / 3 + 1);
  }

  private static String role(Actor actor) {
    return actor.roles().contains(ROLE_INSPECTOR) ? ROLE_INSPECTOR : ROLE_APPRAISER;
  }

  private static ProblemFieldError field(String name, String message) {
    return new ProblemFieldError(name, message, null);
  }

  private static BusinessException invalid(String name, String message) {
    return new BusinessException("COMMON-422-VALIDATION", message, List.of(field(name, message)));
  }

  private static BusinessException outOfScope() {
    return new BusinessException(
        "COMMON-403-OUT-OF-SCOPE", "Đối tượng hậu kiểm nằm ngoài đơn vị bạn phụ trách.");
  }

  /** Đợt không tồn tại hoặc người gọi không được xem (lớp REST trả 404). */
  public static final class NotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }

  /** Chưa có bộ mã hoá C2 (lớp REST trả 503). */
  public static final class SealingUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SealingUnavailableException() {
      super("Chưa cấu hình mã hoá dữ liệu nhạy cảm cho hậu kiểm");
    }
  }
}
