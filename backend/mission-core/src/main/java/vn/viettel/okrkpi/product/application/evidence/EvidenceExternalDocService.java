package vn.viettel.okrkpi.product.application.evidence;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.product.application.evidence.EvidenceSubmissionService.DraftInput;
import vn.viettel.okrkpi.product.application.evidence.EvidenceSubmissionService.EvidenceDetail;
import vn.viettel.okrkpi.product.application.evidence.EvidenceSubmissionService.EvidenceInput;
import vn.viettel.okrkpi.product.application.evidence.EvidenceSubmissionService.VersionDetail;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.EvidenceView;
import vn.viettel.okrkpi.product.application.evidence.port.IssuedDocuments;
import vn.viettel.okrkpi.shared.audit.AuditEvent;
import vn.viettel.okrkpi.shared.audit.AuditEventType;
import vn.viettel.okrkpi.shared.audit.AuditLogger;
import vn.viettel.okrkpi.shared.audit.AuditOutcome;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException.Reason;

/**
 * Minh chứng là văn bản đã ban hành trên vOffice (CORE-03-04, QT-07, SCR-0701 "Chọn văn bản",
 * BR-07-13): tìm văn bản của đơn vị người gọi qua SVC-13, nhận văn bản làm minh chứng khi chữ ký số
 * gốc hợp lệ, và mở phiên bản bằng tệp gốc SVC-13 chuyển thẳng sang SVC-08 (trình duyệt không tải
 * lại tệp). Lời gọi SVC-13 nằm ngoài giao dịch CSDL; SVC-13 lỗi thì trả 503 kèm {@code Retry-After}
 * và không ghi gì. Lần từ chối vì chữ ký được ghi nhật ký kiểm toán ngoài giao dịch.
 */
public class EvidenceExternalDocService {

  /** Minh chứng có nguồn là văn bản đã ban hành ({@code product.evidence.source_type}). */
  static final String VAN_BAN = "VAN_BAN";

  static final String AUDIT_REJECTED = "TU_CHOI_VAN_BAN_SAI_CHU_KY";
  static final String DEPENDENCY = "integration-gateway";
  static final int MAX_LIMIT = 200;
  private static final int LOOKUP_LIMIT = 50;

  /** Đơn vị công tác chính của cán bộ tại một ngày (bản sao tổ chức). */
  @FunctionalInterface
  public interface Units {
    Optional<UUID> unitOf(TenantId tenant, UUID personId, LocalDate on);
  }

  private final EvidenceSubmissionService submissions;
  private final IssuedDocuments documents;
  private final Units units;
  private final AuditLogger audit;
  private final Clock clock;

  /**
   * @param documents {@code null} khi chưa cấu hình SVC-13: tìm và nhận văn bản trả 503
   */
  public EvidenceExternalDocService(
      EvidenceSubmissionService submissions,
      IssuedDocuments documents,
      Units units,
      AuditLogger audit,
      Clock clock) {
    this.submissions = Objects.requireNonNull(submissions, "submissions");
    this.documents = documents;
    this.units = Objects.requireNonNull(units, "units");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Văn bản đã ban hành của đơn vị người gọi; người chưa có đơn vị nhận danh sách rỗng. */
  public IssuedDocuments.Page search(
      TenantId tenant, String q, int limit, String cursor, EvidenceActor actor) {
    Optional<UUID> unit = units.unitOf(tenant, actor.personId(), LocalDate.now(clock));
    if (unit.isEmpty()) {
      return new IssuedDocuments.Page(java.util.List.of(), null);
    }
    int size = Math.max(1, Math.min(limit, MAX_LIMIT));
    return documents().search(tenant, unit.get(), blankToNull(q), size, blankToNull(cursor));
  }

  /** Minh chứng mới; nguồn văn bản thì văn bản phải thuộc đơn vị và có chữ ký số gốc hợp lệ. */
  public EvidenceDetail newEvidence(TenantId tenant, EvidenceInput input, EvidenceActor actor) {
    if (VAN_BAN.equals(input.sourceType())) {
      String ref = blankToNull(input.externalDocRef());
      if (ref == null) {
        throw field("externalDocRef", "Vui lòng chọn văn bản đã ban hành");
      }
      UUID unit = unitOf(tenant, actor);
      IssuedDocuments.Doc doc =
          documents().search(tenant, unit, ref, LOOKUP_LIMIT, null).items().stream()
              .filter(d -> ref.equals(d.ref()))
              .findFirst()
              .orElseThrow(
                  () -> field("externalDocRef", "Không tìm thấy văn bản trong đơn vị của bạn"));
      requireSigned(tenant, null, ref, doc.signatureValid(), actor);
    }
    return submissions.createEvidence(tenant, input, actor);
  }

  /**
   * Phiên bản mới. Minh chứng nguồn văn bản: yêu cầu SVC-13 chuyển tệp gốc sang SVC-08 (gọi lại
   * cùng phiên bản trả cùng đối tượng), kiểm chữ ký của tệp đã chuyển rồi mở phiên bản {@code
   * VAN_BAN_HE_THONG}; nguồn khác đi đường lưu nháp thường.
   */
  public VersionDetail newVersion(
      TenantId tenant, UUID evidenceId, DraftInput input, EvidenceActor actor) {
    EvidenceView e = submissions.getEvidence(tenant, evidenceId).evidence();
    if (!VAN_BAN.equals(e.sourceType())) {
      return submissions.createVersion(tenant, evidenceId, input, actor);
    }
    if (e.externalDocRef() == null) {
      throw field("externalDocRef", "Minh chứng chưa gắn văn bản đã ban hành");
    }
    UUID unit = unitOf(tenant, actor);
    int next = (e.currentVersionNo() == null ? 0 : e.currentVersionNo()) + 1;
    IssuedDocuments.Transferred original =
        documents()
            .transfer(
                tenant,
                unit,
                e.externalDocRef(),
                actor.personId(),
                "chuyen-van-ban-" + evidenceId + "-" + next)
            .orElseThrow(
                () -> field("externalDocRef", "Không tìm thấy văn bản trong đơn vị của bạn"));
    requireSigned(tenant, evidenceId, e.externalDocRef(), original.signatureValid(), actor);
    return submissions.openExternalDocVersion(tenant, evidenceId, input, original, actor);
  }

  private void requireSigned(
      TenantId tenant, UUID evidenceId, String ref, boolean valid, EvidenceActor actor) {
    if (valid) {
      return;
    }
    audit.record(
        AuditEvent.builder(
                AuditEventType.ADMIN_ACTION, ActorId.of(actor.personId()), AUDIT_REJECTED)
            .outcome(AuditOutcome.DENIED)
            .tenant(tenant)
            .object("evidence", evidenceId == null ? "" : evidenceId.toString())
            .classification(Classification.C1)
            .attribute("externalDocRef", ref)
            .attribute("rule", EvidenceSubmissionService.EXTERNAL_RULE)
            .build());
    throw EvidenceSubmissionService.signatureInvalid();
  }

  private UUID unitOf(TenantId tenant, EvidenceActor actor) {
    return units
        .unitOf(tenant, actor.personId(), LocalDate.now(clock))
        .orElseThrow(() -> field("externalDocRef", "Bạn chưa thuộc đơn vị nào để chọn văn bản"));
  }

  private IssuedDocuments documents() {
    if (documents == null) {
      throw new DependencyUnavailableException(
          DEPENDENCY, Reason.IO_ERROR, Duration.ofSeconds(30), null);
    }
    return documents;
  }

  private static BusinessException field(String name, String message) {
    return new BusinessException(
        EvidenceSubmissionService.VALIDATION,
        message,
        java.util.List.of(new ProblemFieldError(name, message, null)));
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }
}
