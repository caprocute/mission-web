package vn.viettel.okrkpi.dossier.application.transfer;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.dossier.application.comment.port.CommentSealer;
import vn.viettel.okrkpi.dossier.application.transfer.port.DossierHistory;
import vn.viettel.okrkpi.shared.audit.AuditEvent;
import vn.viettel.okrkpi.shared.audit.AuditEventType;
import vn.viettel.okrkpi.shared.audit.AuditLogger;
import vn.viettel.okrkpi.shared.audit.AuditOutcome;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Hai phần hồ sơ mang theo khi điều động cán bộ liên cell (DV-13-10). Hồ sơ đã ký chỉ đi ở dạng
 * siêu dữ liệu và băm (manifest, gói): tệp gốc ở lại SVC-08 của cell nguồn, là bằng chứng có thể
 * đối chiếu khi cần. Kết luận kiểm điểm chỉ lấy loại {@code KET_LUAN}, không mang người góp ý (ẩn
 * danh theo QĐ 366 không bị phá khi sang tenant khác); nội dung C2 được giải để gói mã hoá lại bằng
 * khoá của cell nhận, và mỗi lần đọc ghi sổ cái truy cập dữ liệu nhạy cảm.
 */
public class DossierTransferService {

  /** Người thực hiện của hệ thống trong sổ cái. */
  public static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

  static final String AUDIT_READ = "dossier.transfer.conclusions.read";

  /** Mô tả kỳ mang theo; mã kỳ của tenant nguồn không có nghĩa ở tenant đích. */
  public record Period(String code, String name, LocalDate startDate, LocalDate endDate) {}

  /** Siêu dữ liệu một gói hồ sơ đã ký, băm hex chữ thường. */
  public record SignedDossier(
      Period period,
      String code,
      String packageKind,
      int versionNo,
      String status,
      String manifestSha256,
      String packageSha256,
      Instant submittedAt) {}

  /** Phần "hồ sơ đã ký" của gói. */
  public record SignedDossiers(int version, UUID personId, List<SignedDossier> packages) {}

  /** Một kết luận kiểm điểm. */
  public record Conclusion(
      Period period, String kind, String phase, String content, LocalDate remediationDue) {}

  /** Phần "kết luận kiểm điểm" của gói. */
  public record Conclusions(int version, UUID personId, List<Conclusion> items) {}

  private final DossierHistory history;
  private final CommentSealer sealer;
  private final PeriodApi periods;
  private final AuditLogger audit;

  public DossierTransferService(
      DossierHistory history, CommentSealer sealer, PeriodApi periods, AuditLogger audit) {
    this.history = Objects.requireNonNull(history, "history");
    this.sealer = Objects.requireNonNull(sealer, "sealer");
    this.periods = periods;
    this.audit = Objects.requireNonNull(audit, "audit");
  }

  /** Rỗng khi cán bộ chưa có gói hồ sơ cá nhân nào đã ký. */
  public Optional<SignedDossiers> signedDossiers(TenantId tenant, UUID personId) {
    Objects.requireNonNull(personId, "personId");
    HexFormat hex = HexFormat.of();
    List<SignedDossier> out = new ArrayList<>();
    for (DossierHistory.SignedPackage p : history.signedPackages(tenant, personId)) {
      out.add(
          new SignedDossier(
              period(tenant, p.periodId()),
              p.code(),
              p.packageKind(),
              p.versionNo(),
              p.status(),
              p.manifestHash() == null ? null : hex.formatHex(p.manifestHash()),
              p.packageSha256() == null ? null : hex.formatHex(p.packageSha256()),
              p.submittedAt()));
    }
    return out.isEmpty()
        ? Optional.empty()
        : Optional.of(new SignedDossiers(1, personId, List.copyOf(out)));
  }

  /** Rỗng khi cán bộ chưa có kết luận kiểm điểm nào; có thì ghi sổ cái đọc C2. */
  public Optional<Conclusions> conclusions(TenantId tenant, UUID personId) {
    Objects.requireNonNull(personId, "personId");
    List<DossierHistory.Conclusion> rows = history.conclusions(tenant, personId);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    List<Conclusion> out = new ArrayList<>();
    for (DossierHistory.Conclusion c : rows) {
      out.add(
          new Conclusion(
              period(tenant, c.periodId()),
              c.kind(),
              c.phase(),
              sealer.open(tenant, c.id(), c.cipher()),
              c.remediationDue()));
    }
    audit.record(
        AuditEvent.builder(
                AuditEventType.SENSITIVE_DATA_ACCESS, ActorId.of(SYSTEM_ACTOR), AUDIT_READ)
            .outcome(AuditOutcome.SUCCESS)
            .tenant(tenant)
            .object("person", personId.toString())
            .classification(Classification.C2)
            .attribute("purpose", "CHUYEN_LIEN_CELL")
            .attribute("count", Integer.toString(out.size()))
            .build());
    return Optional.of(new Conclusions(1, personId, List.copyOf(out)));
  }

  private Period period(TenantId tenant, UUID periodId) {
    Optional<PeriodSnapshot> p =
        periods == null ? Optional.empty() : periods.findById(tenant, new PeriodId(periodId));
    return p.map(s -> new Period(s.code(), s.name(), s.startDate(), s.endDate()))
        .orElse(new Period(periodId.toString(), "", null, null));
  }
}
