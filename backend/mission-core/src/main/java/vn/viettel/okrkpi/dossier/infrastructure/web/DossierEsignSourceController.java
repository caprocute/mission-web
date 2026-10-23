package vn.viettel.okrkpi.dossier.infrastructure.web;

import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.dossier.application.dossierpackage.DossierSigningService;
import vn.viettel.okrkpi.dossier.application.meeting.MeetingProceedingsService;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Cổng nội bộ SVC-10 hỏi tài liệu cần ký của MOD-06 ({@code GET
 * /internal/v1/esign-sources/dossier/{id}?purpose=}, suy luận ở wiki trang 14). CORE-06-04 phục vụ
 * mục đích {@code BIEN_BAN}: tài liệu là tệp PDF/A biên bản (băm {@code minutes_sha256}, ký {@code
 * PADES_LTA}), người ký là chủ trì và thư ký chưa ký. CORE-06-07 phục vụ mục đích {@code
 * GOI_HO_SO}: tài liệu là manifest đã chốt của gói chờ ký (băm {@code manifest_hash}, ký {@code
 * CADES_T} kèm dấu thời gian RFC 3161), người ký là người đã yêu cầu ký gói. Mục đích khác trả 404.
 * Không nằm trong hợp đồng công khai; bảo vệ bằng xác thực dịch vụ của mesh.
 */
@RestController
@RequestMapping("/internal/v1/esign-sources/dossier")
public class DossierEsignSourceController {

  static final String PURPOSE = "BIEN_BAN";
  static final String KIND = "BIEN_BAN_KIEM_DIEM";
  static final String FORMAT = "PADES_LTA";
  static final String PACKAGE_PURPOSE = "GOI_HO_SO";
  static final String PACKAGE_KIND = "MANIFEST_GOI_HO_SO";
  static final String PACKAGE_FORMAT = "CADES_T";

  private final MeetingProceedingsService service;
  private final ObjectProvider<DossierSigningService> signing;

  public DossierEsignSourceController(
      MeetingProceedingsService service, ObjectProvider<DossierSigningService> signing) {
    this.service = Objects.requireNonNull(service, "service");
    this.signing = Objects.requireNonNull(signing, "signing");
  }

  /** Thân phản hồi theo {@code HttpSourceDocumentClient} của SVC-10. */
  public record Source(boolean ready, Set<UUID> signers, List<Doc> documents) {}

  /** Một tài liệu, băm hex chữ thường. */
  public record Doc(String kind, String ref, String sha256, String format) {}

  @GetMapping("/{id}")
  public ResponseEntity<Source> source(
      @PathVariable("id") UUID id, @RequestParam("purpose") String purpose) {
    if (PACKAGE_PURPOSE.equals(purpose)) {
      return ResponseEntity.ok(packageSource(id));
    }
    if (!PURPOSE.equals(purpose)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    MeetingProceedingsService.SignSource s =
        service
            .signSource(TenantContext.require(), id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    List<Doc> documents =
        s.ready()
            ? List.of(
                new Doc(KIND, id.toString(), HexFormat.of().formatHex(s.minutesSha256()), FORMAT))
            : List.of();
    return ResponseEntity.ok(new Source(s.ready(), s.signers(), documents));
  }

  private Source packageSource(UUID id) {
    DossierSigningService svc = signing.getIfAvailable();
    if (svc == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    DossierSigningService.SignSource s =
        svc.signSource(TenantContext.require(), id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    List<Doc> documents =
        s.ready()
            ? List.of(
                new Doc(
                    PACKAGE_KIND,
                    id.toString(),
                    HexFormat.of().formatHex(s.manifestHash()),
                    PACKAGE_FORMAT))
            : List.of();
    return new Source(s.ready(), s.signers(), documents);
  }
}
