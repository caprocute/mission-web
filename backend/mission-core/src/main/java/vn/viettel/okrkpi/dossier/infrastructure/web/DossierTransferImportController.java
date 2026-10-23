package vn.viettel.okrkpi.dossier.infrastructure.web;

import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.dossier.application.transfer.CellTransferPartsService;
import vn.viettel.okrkpi.dossier.application.transfer.TransferImportService;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Cổng nội bộ SVC-13 của cell đích nhập một phần hồ sơ của gói chuyển liên cell (DV-13-10, cấu hình
 * {@code
 * okrkpi.integ.cell-transfer.parts[KET_QUA_XEP_LOAI|HO_SO_DA_KY|KET_LUAN_KIEM_DIEM].import-url} của
 * gateway): {@code POST /internal/v1/dossier/transfer-imports/{personId}?kind=}, thân là nội dung
 * phần. {@code Idempotency-Key} là {@code <mã gói>:<tên phần>}; 201 khi nhập mới, 200 khi đã nhập
 * cùng nội dung, 409 khi đã nhập nội dung khác, 422 khi phần không hợp lệ. Tenant lấy từ token dịch
 * vụ uỷ quyền ({@code X-Tenant-Id}); không nằm trong hợp đồng công khai, bảo vệ bằng xác thực dịch
 * vụ của mesh.
 */
@RestController
public class DossierTransferImportController {

  private final TransferImportService service;

  public DossierTransferImportController(TransferImportService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  @PostMapping("/internal/v1/dossier/transfer-imports/{personId}")
  public ResponseEntity<Void> importPart(
      @PathVariable("personId") UUID personId,
      @RequestParam("kind") String kind,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestHeader("X-Transfer-Package") UUID packageId,
      @RequestHeader("X-Source-Tenant") String sourceTenant,
      @RequestHeader("Content-Type") String mediaType,
      @RequestBody byte[] content) {
    String prefix = packageId + ":";
    if (idempotencyKey == null || !idempotencyKey.startsWith(prefix)) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
    }
    try {
      TransferImportService.Result r =
          service.importPart(
              TenantContext.require(),
              new TransferImportService.Part(
                  packageId,
                  idempotencyKey.substring(prefix.length()),
                  kind,
                  sourceTenant,
                  personId,
                  mediaType,
                  content),
              CellTransferPartsService.SYSTEM_ACTOR);
      return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK).build();
    } catch (TransferImportService.InvalidPartException e) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    } catch (TransferImportService.ConflictException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
    }
  }
}
