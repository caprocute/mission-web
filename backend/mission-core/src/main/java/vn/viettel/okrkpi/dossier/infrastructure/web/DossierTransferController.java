package vn.viettel.okrkpi.dossier.infrastructure.web;

import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.dossier.application.transfer.DossierTransferService;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Cổng nội bộ SVC-13 thu thập phần hồ sơ của cán bộ được điều động liên cell (DV-13-10, cấu hình
 * {@code okrkpi.integ.cell-transfer.parts[HO_SO_DA_KY|KET_LUAN_KIEM_DIEM].collect-url} của
 * gateway): {@code GET /internal/v1/dossier/transfer-parts/{personId}/signed-dossiers} và {@code
 * .../conclusions}, 404 khi chưa có. Tenant lấy từ token dịch vụ uỷ quyền ({@code X-Tenant-Id});
 * không nằm trong hợp đồng công khai, bảo vệ bằng xác thực dịch vụ của mesh.
 */
@RestController
@RequestMapping("/internal/v1/dossier/transfer-parts/{personId}")
public class DossierTransferController {

  private final DossierTransferService service;

  public DossierTransferController(DossierTransferService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  @GetMapping("/signed-dossiers")
  public DossierTransferService.SignedDossiers signedDossiers(
      @PathVariable("personId") UUID personId) {
    return service
        .signedDossiers(TenantContext.require(), personId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  @GetMapping("/conclusions")
  public DossierTransferService.Conclusions conclusions(@PathVariable("personId") UUID personId) {
    return service
        .conclusions(TenantContext.require(), personId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }
}
