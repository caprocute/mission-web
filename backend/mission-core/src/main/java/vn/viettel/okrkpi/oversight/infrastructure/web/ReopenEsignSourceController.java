package vn.viettel.okrkpi.oversight.infrastructure.web;

import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenApprovalService;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Cổng nội bộ SVC-10 hỏi tài liệu cần ký của quyết định duyệt mở lại kỳ ({@code GET
 * /internal/v1/esign-sources/oversight/{id}?purpose=PHE_DUYET_MO_LAI}, cùng cách cổng của
 * appraisal). Tài liệu là băm nội dung quyết định đang chờ ký, dạng {@code JWS}; người ký duy nhất
 * là người duyệt. Không nằm trong hợp đồng công khai; bảo vệ bằng xác thực dịch vụ của mesh.
 */
@RestController
@RequestMapping("/internal/v1/esign-sources/oversight")
public class ReopenEsignSourceController {

  static final String PURPOSE = "PHE_DUYET_MO_LAI";
  static final String KIND = "QUYET_DINH_MO_LAI_KY";
  static final String FORMAT = "JWS";

  private final ReopenApprovalService service;

  public ReopenEsignSourceController(ReopenApprovalService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  /** Thân phản hồi theo {@code HttpSourceDocumentClient} của SVC-10. */
  public record Source(boolean ready, Set<UUID> signers, List<Doc> documents) {}

  /** Một tài liệu, băm hex chữ thường. */
  public record Doc(String kind, String ref, String sha256, String format) {}

  @GetMapping("/{id}")
  public ResponseEntity<Source> source(
      @PathVariable("id") UUID id, @RequestParam("purpose") String purpose) {
    if (!PURPOSE.equals(purpose)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    ReopenApprovalService.SignSource s =
        service
            .signSource(TenantContext.require(), id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    return ResponseEntity.ok(
        new Source(
            true,
            Set.of(s.signer()),
            List.of(new Doc(KIND, s.ref(), HexFormat.of().formatHex(s.sha256()), FORMAT))));
  }
}
